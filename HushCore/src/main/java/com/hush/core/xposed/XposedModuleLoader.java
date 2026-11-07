package com.hush.core.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hush.app.BActivityThread;
import com.hush.core.system.pm.BXposedManagerService;
import com.hush.entity.pm.InstalledModule;
import com.hush.fake.frameworks.BXposedManager;
import com.hush.utils.Diag;

import dalvik.system.PathClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;

/**
 * Xposed 模块加载器。
 *
 * <p>模块装在容器的 {@code USER_XPOSED} 用户空间里（跟普通 App 分开），
 * 这里在 guest 进程创建 Application 之前把它们拉起来：
 *
 * <ol>
 *   <li>读 {@code xposed-module.conf}，拿到总开关和每个模块的启用状态；</li>
 *   <li>对每个启用的模块，建一个以 guest ClassLoader 为父的 PathClassLoader；</li>
 *   <li>读 {@code assets/xposed_init} 里的入口类名，实例化；</li>
 *   <li>{@code IXposedHookZygoteInit} 立刻回调一次，{@code IXposedHookLoadPackage}
 *       带着包名/进程名回调 —— 模块在这一步调 {@code findAndHookMethod}，
 *       最终落到 {@link XposedEngine} → HushHook。</li>
 * </ol>
 *
 * <p>注意：HushHook 必须在 {@code enableIO} 之前把总闸打开才会初始化，
 * 所以 {@link #needed()} 在 bind 早期就会被调用一次，结果在这里复用。
 */
public final class XposedModuleLoader {

    private static volatile Boolean sNeeded;
    private static volatile boolean sLoaded;

    private XposedModuleLoader() {
    }

    /**
     * 这个进程需不需要拉起 Xposed（总开关 + 至少一个模块启用）。
     * 结果缓存，bind 早期和 newApplication 各调一次也只走一次 IPC。
     */
    public static boolean needed() {
        Boolean cached = sNeeded;
        if (cached != null) {
            return cached;
        }
        // HMS Core / 账号 kit / 应用市场是容器的基础设施，不是「用户应用」。
        // 模块（尤其作用域为空 = 全部应用的那种）挂进来会改账号 SDK 的内部
        // 方法，直接把登录链路打穿。这里从总闸层面就不给它们开引擎。
        if (isHmsInfra(BActivityThread.getAppPackageName())) {
            sNeeded = false;
            Diag.log("xp", "HMS 系统包不启用模块 " + BActivityThread.getAppPackageName());
            return false;
        }
        // 优先直接读 xposed-module.conf：这一步发生在 bind 很早的位置
        // （enableIO 之前，总闸必须在这里定下来），能不走跨进程就尽量不走。
        boolean needed = false;
        boolean decided = false;
        String currentPkg = null;
        try {
            currentPkg = BActivityThread.getAppPackageName();
        } catch (Throwable ignored) {
        }
        try {
            File conf = com.hush.core.env.BEnvironment.getXPModuleConf();
            if (conf != null && conf.exists()) {
                android.os.Parcel parcel = null;
                try {
                    parcel = com.hush.utils.FileUtils.readToParcel(conf);
                    com.hush.entity.pm.XposedConfig config =
                            new com.hush.entity.pm.XposedConfig(parcel);
                    decided = true;
                    if (config.enable) {
                        for (Map.Entry<String, Boolean> entry : config.moduleState.entrySet()) {
                            if (entry.getValue() == null || !entry.getValue()) {
                                continue;
                            }
                            // 作用域为空 = 全部应用；否则只有当前包名命中才需要引擎。
                            List<String> scope = config.moduleScope.get(entry.getKey());
                            if (scope == null || scope.isEmpty()
                                    || currentPkg == null || scope.contains(currentPkg)) {
                                needed = true;
                                break;
                            }
                        }
                    }
                } finally {
                    if (parcel != null) {
                        parcel.recycle();
                    }
                }
            }
        } catch (Throwable t) {
            Diag.log("xp", "读 xposed-module.conf 失败 " + t);
        }

        if (!decided) {
            try {
                if (BXposedManager.get().isXPEnable()) {
                    List<InstalledModule> modules = BXposedManager.get().getInstalledModules();
                    for (InstalledModule module : modules) {
                        if (module != null && module.enable
                                && inScope(module, currentPkg)) {
                            needed = true;
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                Diag.log("xp", "needed() 查询失败 " + t);
            }
        }
        Diag.log("xp", "needed=" + needed + (decided ? "(file)" : "(ipc)"));
        sNeeded = needed;
        return needed;
    }

    /** 模块是否允许作用于该包名。空作用域 = 全部应用（默认）。 */
    /**
     * 容器内置的华为基础服务。模块一律不进这些进程：
     * 它们跑的是 HMS 自己的账号 / 支付 / 设备服务，任何第三方 hook 都可能
     * 让 getCallingPackage、签名校验、设备指纹直接失配。
     */
    public static boolean isHmsInfra(String pkg) {
        if (pkg == null) return false;
        return com.hush.core.HmsCore.isHmsPackage(pkg)
                || "com.huawei.appmarket".equals(pkg);
    }

    private static boolean inScope(InstalledModule module, String packageName) {
        List<String> scope = module.scope;
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        if (packageName == null) {
            return true;
        }
        return scope.contains(packageName);
    }

    /** 已加载模块的运行期记录，供 Application.onCreate 前补调延迟入口。 */
    private static final List<ModuleRecord> sRecords = new ArrayList<>();
    private static volatile boolean sDeferredDone;
    /** Class#getProtectionDomain 桥接只装一次。 */
    private static volatile boolean sPdBridgeInstalled;
    /** 已装过 zll 探针的模块 apk，按 apk 去重（不同模块是不同的 Class 对象）。 */
    private static final java.util.Set<String> sZllHooked =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** ProtectionDomain 桥接的命中/未命中计数，只用于前几条日志。 */
    private static volatile int sPdHit;
    private static volatile int sPdMiss;
    /** 模块 apk（虚拟安装路径）→ 容器内 payload 副本（真实可读路径）。 */
    private static final java.util.Map<String, String> sPayloadOf =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class ModuleRecord {
        final ClassLoader loader;
        de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam param;
        final List<String> entries = new ArrayList<>();

        ModuleRecord(ClassLoader loader) {
            this.loader = loader;
        }
    }

    public static synchronized void load(Context context) {
        load(context, context == null ? null : context.getClassLoader());
    }

    /**
     * @param guestLoader 承载 guest（光遇）业务类的 ClassLoader。
     *
     * <p>不能想当然用 {@code context.getClassLoader()}：加固壳是在
     * {@code attachBaseContext} 里才把 LoadedApk 的 mClassLoader 换成自己的
     * DexClassLoader 的。模块在 {@code newApplication} 阶段被拉起时，
     * 这个值可能还是壳外层的 PathClassLoader，拿去 findClass 必然找不到业务类。
     * 调用方拿到更准的 loader 时应当显式传进来。
     */
    public static synchronized void load(Context context, ClassLoader guestLoader) {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        try {
            if (isHmsInfra(BActivityThread.getAppPackageName())) {
                Diag.log("xp", "HMS 系统包不加载模块 " + BActivityThread.getAppPackageName());
                return;
            }
            if (!needed()) {
                Diag.log("xp", "未启用或没有启用的模块");
                return;
            }
            List<InstalledModule> all = BXposedManager.get().getInstalledModules();
            String packageName = BActivityThread.getAppPackageName();
            List<InstalledModule> enabled = new ArrayList<>();
            int outOfScope = 0;
            for (InstalledModule module : all) {
                if (module == null || !module.enable) {
                    continue;
                }
                if (!inScope(module, packageName)) {
                    outOfScope++;
                    continue;
                }
                enabled.add(module);
            }
            if (enabled.isEmpty()) {
                if (outOfScope > 0) {
                    Diag.log("xp", "有 " + outOfScope + " 个启用模块，但都不在作用域 "
                            + packageName + " 内");
                } else {
                    Diag.log("xp", "没有启用的模块");
                }
                return;
            }
            if (!XposedEngine.ensureHookReady()) {
                Diag.log("xp", "HushHook 未就绪，跳过模块加载（模块数=" + enabled.size() + "）");
                return;
            }
            // 先装日志镜像：模块在 handleLoadPackage 里就可能把失败信息写进
            // Log / 加载 so，晚一步就漏掉最关键的开头。
            GuestLog.install();
            // ZLL 系模块靠 Class#getProtectionDomain 定位自己的 apk。必须在任何
            // 模块类的静态初始化之前装好，否则它只会看到一条 codeSource err。
            installProtectionDomainBridge();

            ClassLoader appClassLoader = guestLoader != null ? guestLoader : context.getClassLoader();
            String processName = BActivityThread.getAppProcessName();
            // 模块的提权调用（Runtime.exec("su")）在容器里必然 ENOENT，
            // 先装一层虚拟 root shell，让它的功能脚本能真正跑起来。
            com.hush.core.RootShim.install(context, packageName);
            List<IXposedHookLoadPackage> loadPackageHooks = new ArrayList<>();
            int entryCount = 0;
            Diag.log("xp", "guest loader=" + BActivityThread.describeLoader(appClassLoader)
                    + " host loader=" + BActivityThread.describeLoader(XposedModuleLoader.class.getClassLoader()));

            for (InstalledModule module : enabled) {
                try {
                    // 作用域：空 = 全部应用；非空则只在本进程包名命中时加载。
                    // 这一步必须在读 APK、装桥接之前，避免无关应用白白承担开销。
                    if (!inScope(module, packageName)) {
                        Diag.log("xp", "模块 " + module.packageName
                                + " 不在作用域 " + packageName + "，跳过");
                        continue;
                    }
                    ApplicationInfo info = module.getApplication();
                    if (info == null || info.sourceDir == null) {
                        Diag.log("xp", "模块路径缺失: " + module.packageName);
                        continue;
                    }
                    String apkPath = info.sourceDir;
                    if (!new File(apkPath).exists()) {
                        Diag.log("xp", "模块 APK 不存在: " + apkPath);
                        continue;
                    }
                    String payload = stageModuleApk(context, apkPath, module.packageName);
                    if (payload != null) {
                        sPayloadOf.put(apkPath, payload);
                    }
                    auditPayload(apkPath);
                    ModuleProbe.addModuleApk(apkPath);
                    ClassLoader loader = openModule(apkPath, appClassLoader, context);
                    installZllBridge(loader, apkPath);
                    // 身份层必须早于入口类实例化：HookInit 的 <clinit> 里就可能
                    // 做签名/包名自检，晚一步就只剩下一条「抵制盗版」。
                    ModuleIdentity.add(module.packageName, apkPath);
                    ModuleIdentity.install();
                    ModuleRecord record = new ModuleRecord(loader);
                    sRecords.add(record);
                    Diag.log("xp", "模块 APK path=" + apkPath + " 声明包名=" + module.packageName
                            + " APK真实包名=" + archivePackage(apkPath));
                    List<String> entries = entryClasses(module, apkPath);
                    record.entries.addAll(entries);
                    if (entries.isEmpty()) {
                        // 没有 xposed_init 也继续：下面扫 dex 找实现接口的类。
                        Diag.log("xp", "模块没有入口类声明，尝试扫描 dex: " + module.packageName);
                    } else {
                        Diag.log("xp", "模块包名=" + module.packageName + " 声明入口=" + entries);
                    }
                    int okBefore = entryCount;
                    for (String className : entries) {
                        if (instantiate(className, loader, apkPath, loadPackageHooks)) {
                            entryCount++;
                        }
                    }
                    if (entryCount == okBefore) {
                        // xposed_init 写的类名在 dex 里找不到：可能是模块被加固/换过
                        // 入口写法，直接扫一遍 dex 找真正实现 IXposedHookLoadPackage 的类。
                        for (String className : scanHookClasses(loader, entries)) {
                            if (instantiate(className, loader, apkPath, loadPackageHooks)) {
                                entryCount++;
                                Diag.log("xp", "改用扫描到的入口 " + className);
                            }
                        }
                    }
                    Diag.log("xp", "module " + module.packageName
                            + (entryCount > okBefore ? " 已加载" : " 未取到可用入口"));
                } catch (Throwable t) {
                    Diag.log("xp", "模块加载失败 " + module.packageName + " " + t);
                }
            }

            // 参数类型必须用 callbacks.XC_LoadPackage.LoadPackageParam：
            // 模块编译期链接的就是这个描述符，换成别的类型模块类直接链接失败。
            de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam param =
                    new de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam(null);
            param.packageName = packageName;
            param.processName = processName;
            param.classLoader = appClassLoader;
            param.appInfo = context.getApplicationInfo();
            param.isFirstApplication = packageName != null && packageName.equals(processName);
            param.modulePath = null;
            for (ModuleRecord record : sRecords) {
                record.param = param;
            }

            for (IXposedHookLoadPackage hook : loadPackageHooks) {
                try {
                    hook.handleLoadPackage(param);
                } catch (Throwable t) {
                    Diag.log("xp", "handleLoadPackage 失败 " + t);
                }
            }

            // 模块接口回调完之后再装探针：此时 GameHook 已经初始化、hook 已挂，
            // 接下来才进入真正的运行期（Activity 生命周期 → maybeBoot）。
            for (ModuleRecord record : sRecords) {
                ModuleProbe.install(record.loader, record.entries);
            }

            Diag.log("xp", "加载完成 作用域=" + packageName + " 进程=" + processName
                    + " 入口=" + entryCount + " 已挂 hook=" + XposedEngine.hookCount());
            dumpModuleState();
            if (entryCount > 0) {
                installReflectProbe();
                XposedEngine.reportNeverTriggered(12000L);
            }
        } catch (Throwable t) {
            Diag.log("xp", "load 总体失败\n" + Diag.stack(t));
        }
    }

    // ── 反射探针 ────────────────────────────────────────────────────────

    /** 在所有已加载模块的 loader 里找类；找不到返回 null。 */
    public static Class<?> classInAnyModule(String name) {
        synchronized (XposedModuleLoader.class) {
            for (ModuleRecord record : sRecords) {
                try {
                    Class<?> c = Class.forName(name, false, record.loader);
                    if (c != null) {
                        return c;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean sProbeInstalled;
    private static volatile boolean sProbeMuted;
    private static final java.util.Set<String> sMissReported =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final ThreadLocal<Boolean> sInMiss = new ThreadLocal<Boolean>();

    /**
     * 模块自己写的反射（不经过 XposedHelpers）失败时是静默的 ——
     * 典型表现就是「面板正常、点功能没反应、日志里一个错都没有」。
     *
     * <p>这里只在 {@code Class.forName} 抛 ClassNotFoundException 时记一行，
     * 带上模块的调用方类名。不 hook {@code ClassLoader#loadClass}：它是所有类加载的
     * 必经之路，回调里一旦碰到没加载的类就会无限递归。
     */
    private static void installReflectProbe() {
        if (sProbeInstalled) {
            return;
        }
        sProbeInstalled = true;
        try {
            // 先把回调里要用到的类加载好，避免在 forName 回调里再触发类加载。
            sMissReported.add("");
            sMissReported.remove("");
            Class<?> hookClass = de.robv.android.xposed.XC_MethodHook.class;
            hookClass.getName();
            java.lang.reflect.Method forName = Class.class.getDeclaredMethod(
                    "forName", String.class, boolean.class, ClassLoader.class);
            forName.setAccessible(true);
            de.robv.android.xposed.XposedBridge.hookMethod(forName,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sProbeMuted) {
                                return;
                            }
                            Throwable t = param.getThrowable();
                            if (!(t instanceof ClassNotFoundException)) {
                                return;
                            }
                            reportMiss((String) param.args[0], (ClassLoader) param.args[2]);
                        }
                    });
            Diag.log("xp", "反射探针已安装 Class.forName");
        } catch (Throwable t) {
            Diag.log("xp", "反射探针安装失败 " + t);
        }
    }

    private static void reportMiss(String className, ClassLoader loader) {
        if (className == null || sMissReported.size() > 300 || sInMiss.get() != null) {
            return;
        }
        if (!sMissReported.add(className)) {
            return;
        }
        sInMiss.set(Boolean.TRUE);
        try {
            String caller = "";
            StackTraceElement[] frames = Thread.currentThread().getStackTrace();
            for (StackTraceElement frame : frames) {
                String cls = frame.getClassName();
                if (cls.startsWith("java.") || cls.startsWith("android.")
                        || cls.startsWith("com.hush.") || cls.startsWith("de.robv.")) {
                    continue;
                }
                caller = cls + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                break;
            }
            Diag.log("xph", "类不存在 " + className + " 调用方=" + caller
                    + " loader=" + (loader == null ? "null" : loader.getClass().getSimpleName()));
        } catch (Throwable ignored) {
        } finally {
            sInMiss.remove();
        }
    }

    /**
     * Application.onCreate 之前再走一遍「延迟入口」。
     *
     * <p>这类模块（Star 就是）的入口只做一件事：hook 加固壳 StubApp 的某个方法，
     * 等壳解密完、ClassLoader 换好之后，在 afterHookedMethod 里才去调真正的
     * {@code XxxHook.handleLoadPackage}。容器里如果壳走的是系统安装路径，
     * 那个方法可能在模块加载前就已经调用过，hook 永远不触发 —— 表现就是
     * 「模块注入了、hook 也挂上了，但一个功能都不生效」。
     *
     * <p>这里在壳初始化完成之后补一次：只碰「有静态 hooked 标志且还是 false」
     * 的 Hook 类，模块自己已经跑过就不会重复执行。
     */
    public static void onApplicationCreate(android.app.Application app) {
        if (sDeferredDone) {
            return;
        }
        sDeferredDone = true;
        if (sRecords.isEmpty()) {
            return;
        }
        ClassLoader appLoader = null;
        try {
            appLoader = app == null ? null : app.getClassLoader();
        } catch (Throwable ignored) {
        }
        Diag.log("xp", "app onCreate loader=" + BActivityThread.describeLoader(appLoader));
        for (ModuleRecord record : sRecords) {
            try {
                retryDeferredEntries(record, appLoader);
            } catch (Throwable t) {
                Diag.log("xp", "补调延迟入口失败 " + t);
            }
        }
        dumpModuleState();
    }

    /** 打印每个模块里已知状态字段，下一轮日志一眼看出卡在哪一步。 */
    private static void dumpModuleState() {
        for (ModuleRecord record : sRecords) {
            for (String name : record.entries) {
                try {
                    Class<?> c = Class.forName(name, false, record.loader);
                    java.lang.reflect.Field f = c.getDeclaredField("SUPPORTED_PACKAGES");
                    f.setAccessible(true);
                    Diag.log("xp", "状态 " + name + ".SUPPORTED_PACKAGES=" + f.get(null));
                } catch (Throwable ignored) {
                }
            }
            for (String hook : listDeferredCandidates(record)) {
                try {
                    Class<?> c = Class.forName(hook, false, record.loader);
                    java.lang.reflect.Field f = c.getDeclaredField("hooked");
                    f.setAccessible(true);
                    Diag.log("xp", "状态 " + hook + ".hooked=" + f.get(null));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 模块里「带静态 hooked 标志」的 Hook 类：这类类自己会防重复执行。 */
    private static List<String> listDeferredCandidates(ModuleRecord record) {
        List<String> found = new ArrayList<>();
        for (String name : allClassNames(record.loader)) {
            if (record.entries.contains(name) || !looksLikeHookClass(name)) {
                continue;
            }
            try {
                Class<?> c = Class.forName(name, false, record.loader);
                java.lang.reflect.Field f = c.getDeclaredField("hooked");
                if (f.getType() == boolean.class) {
                    found.add(name);
                }
            } catch (Throwable ignored) {
            }
        }
        return found;
    }

    private static void retryDeferredEntries(ModuleRecord record, ClassLoader appLoader) {
        for (String name : listDeferredCandidates(record)) {
            try {
                Class<?> c = Class.forName(name, false, record.loader);
                java.lang.reflect.Field flag = c.getDeclaredField("hooked");
                flag.setAccessible(true);
                if (Boolean.TRUE.equals(flag.get(null))) {
                    Diag.log("xp", "延迟入口已执行 " + name);
                    continue;
                }
                java.lang.reflect.Method m = c.getDeclaredMethod("handleLoadPackage",
                        de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam.class);
                m.setAccessible(true);
                Object instance = null;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    instance = ctor.newInstance();
                }
                if (record.param != null && appLoader != null) {
                    // 壳替换后的 loader 才是业务类的真实加载器。
                    record.param.classLoader = appLoader;
                }
                Diag.log("xp", "补调延迟入口 " + name + " loader="
                        + BActivityThread.describeLoader(record.param == null ? null : record.param.classLoader));
                m.invoke(instance, record.param);
                Diag.log("xp", "补调完成 " + name + " hooked=" + flag.get(null)
                        + " 已挂 hook=" + XposedEngine.hookCount());
            } catch (Throwable t) {
                Diag.log("xp", "补调 " + name + " 失败 " + t + causeOf(t));
            }
        }
    }

    private static boolean looksLikeHookClass(String name) {
        if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("kotlin.")
                || name.startsWith("androidx.") || name.startsWith("dalvik.")
                || name.startsWith("org.jetbrains.")) {
            return false;
        }
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return simple.endsWith("Hook") || simple.contains("Hook$");
    }

    /** 反射遍历模块 dex 的全部类名（只读 entries，不加载类）。 */
    private static List<String> allClassNames(ClassLoader loader) {
        List<String> names = new ArrayList<>();
        try {
            java.lang.reflect.Field pathListField =
                    dalvik.system.BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            java.lang.reflect.Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            for (Object element : elements) {
                if (element == null) {
                    continue;
                }
                java.lang.reflect.Field dexFileField = element.getClass().getDeclaredField("dexFile");
                dexFileField.setAccessible(true);
                dalvik.system.DexFile dexFile = (dalvik.system.DexFile) dexFileField.get(element);
                if (dexFile == null) {
                    continue;
                }
                java.util.Enumeration<String> en = dexFile.entries();
                while (en.hasMoreElements()) {
                    names.add(en.nextElement());
                }
            }
        } catch (Throwable t) {
            Diag.log("xp", "遍历 dex 类名失败 " + t);
        }
        return names;
    }


    /**
     * 反射实例化入口类并回调。返回 true 表示这个类确实是模块入口。
     *
     * <p>失败时把 cause 一起打出来 —— {@code ClassNotFoundException} 本身不带信息，
     * 真正的原因（dex 打不开 / 父加载器冲突 / 类被加固隐藏）都在 cause 里。
     */
    private static boolean instantiate(String className, ClassLoader loader, String apkPath,
                                       List<IXposedHookLoadPackage> hooks) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, loader);
        } catch (Throwable t) {
            // 带初始化失败时退一步：<clinit> 里引用了别的类也会把整次加载拖垮，
            // 先确认类本身在不在 —— 这一步能把「类不存在」和「类存在但初始化失败」
            // 彻底分开，省得每次都要靠猜。
            Diag.log("xp", "入口类加载失败 " + className + " " + t + causeOf(t) + " "
                    + describeApk(apkPath) + " " + probeXposedApi(loader));
            try {
                clazz = Class.forName(className, false, loader);
                Diag.log("xp", "入口类可加载(不初始化)，改为延迟初始化 " + className);
            } catch (Throwable t2) {
                Diag.log("xp", "入口类确实取不到 " + className + " " + t2 + causeOf(t2));
                return false;
            }
        }
        try {
            Object instance = clazz.newInstance();
            boolean recognized = false;
            if (instance instanceof IXposedHookZygoteInit) {
                recognized = true;
                try {
                    ((IXposedHookZygoteInit) instance).initZygote(
                            new IXposedHookZygoteInit.StartupParam(apkPath, false));
                } catch (Throwable t) {
                    Diag.log("xp", "initZygote 失败 " + className + " " + t);
                }
            }
            if (instance instanceof IXposedHookLoadPackage) {
                recognized = true;
                hooks.add((IXposedHookLoadPackage) instance);
            }
            if (!recognized) {
                Diag.log("xp", "入口未实现 Xposed 接口: " + className);
            }
            return recognized;
        } catch (Throwable t) {
            Diag.log("xp", "入口实例化失败 " + className + " " + t + causeOf(t));
            return false;
        }
    }

    /**
     * 接口可见性探针：直接回答「模块的 IXposedHookLoadPackage 能不能解析到」。
     *
     * <p>模块的类 implements 的是宿主 dex 里的接口。解析不到时 ART 不会报
     * NoClassDefFoundError，而是让 findClass 返回 null，外面只看到 CNFE，
     * 和「dex 里没这个类」完全一样。这里把三个 loader 各试一遍，一眼定位。
     */
    private static String probeXposedApi(ClassLoader loader) {
        String api = "de.robv.android.xposed.IXposedHookLoadPackage";
        StringBuilder sb = new StringBuilder("接口可见性[");
        ClassLoader host = XposedModuleLoader.class.getClassLoader();
        sb.append("module=").append(visible(api, loader));
        sb.append(" host=").append(visible(api, host));
        ClassLoader p = loader == null ? null : loader.getParent();
        sb.append(" parent=").append(p == null ? "null" : p.getClass().getSimpleName()
                + "/" + visible(api, p));
        return sb.append(']').toString();
    }

    private static String visible(String cls, ClassLoader loader) {
        if (loader == null) {
            return "null";
        }
        try {
            Class.forName(cls, false, loader);
            return "ok";
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    private static String causeOf(Throwable t) {
        Throwable cause = t.getCause();
        return cause == null ? "" : " cause=" + cause;
    }

    /**
     * 扫描模块 dex，找真正实现 Xposed 接口的类。
     *
     * <p>只在 xposed_init 声明的入口加载失败时调用。模块被加固、或 xposed_init
     * 与代码对不上时，这一步能把模块救回来；如果连一个候选都没有，日志里的
     * 「扫描 dex 类数」就直接说明这个 APK 的 dex 是空壳。
     */
    private static List<String> scanHookClasses(ClassLoader loader, List<String> known) {
        List<String> found = new ArrayList<>();
        int total = 0;
        java.util.Map<String, Integer> packages = new java.util.HashMap<>();
        StringBuilder biz = new StringBuilder();
        try {
            java.lang.reflect.Field pathListField =
                    dalvik.system.BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            java.lang.reflect.Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            for (Object element : elements) {
                if (element == null) {
                    continue;
                }
                java.lang.reflect.Field dexFileField = element.getClass().getDeclaredField("dexFile");
                dexFileField.setAccessible(true);
                dalvik.system.DexFile dexFile = (dalvik.system.DexFile) dexFileField.get(element);
                if (dexFile == null) {
                    Diag.log("xp", "dexElement 无 dexFile（oat 没生成成功）");
                    continue;
                }
                java.util.Enumeration<String> en = dexFile.entries();
                while (en.hasMoreElements()) {
                    String name = en.nextElement();
                    total++;
                    // 包分布比类名列表更能说明问题：一眼看出这个 dex 属于哪个 App。
                    int cut = name.indexOf('.', name.indexOf('.') + 1);
                    if (cut > 0) {
                        String prefix = name.substring(0, cut);
                        Integer c = packages.get(prefix);
                        packages.put(prefix, c == null ? 1 : c + 1);
                    }
                    if (biz.length() < 400
                            && (name.startsWith("com.sky") || name.startsWith("com.netease"))) {
                        biz.append(name).append(' ');
                    }
                    if (known.contains(name) || !isHookCandidate(name)) {
                        continue;
                    }
                    try {
                        Class<?> c = Class.forName(name, false, loader);
                        if (IXposedHookLoadPackage.class.isAssignableFrom(c)
                                || IXposedHookZygoteInit.class.isAssignableFrom(c)) {
                            found.add(name);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Diag.log("xp", "扫描 dex 失败 " + t + causeOf(t));
        }
        Diag.log("xp", "扫描 dex 类数=" + total + " 候选=" + found);
        Diag.log("xp", "dex 包分布 " + topPackages(packages, 8));
        if (biz.length() > 0) {
            Diag.log("xp", "dex 内业务类 " + biz);
        }
        return found;
    }

    private static String topPackages(java.util.Map<String, Integer> packages, int limit) {
        java.util.List<java.util.Map.Entry<String, Integer>> list =
                new ArrayList<>(packages.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
            @Override
            public int compare(java.util.Map.Entry<String, Integer> a,
                               java.util.Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < limit; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(list.get(i).getKey()).append('=').append(list.get(i).getValue());
        }
        return sb.toString();
    }

    private static boolean isHookCandidate(String name) {
        if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("kotlin.")
                || name.startsWith("androidx.") || name.startsWith("dalvik.")
                || name.startsWith("org.jetbrains.")) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("hook") || lower.contains("xposed") || lower.endsWith("init");
    }

    /**
     * 模块专用 ClassLoader：宿主类加载器当 parent，guest 当兜底。
     *
     * <p>为什么不能直接拿 guest ClassLoader 当 parent：模块 implements 的
     * {@code de.robv.android.xposed.IXposedHookLoadPackage} 在宿主 dex 里。
     * 容器里 guest 的 parent 链不保证能走到宿主 ClassLoader，一旦走不到，
     * ART 在 DefineClass 阶段解析不到这个接口，会静默放弃整个类 ——
     * 外面看到的就是一个 cause 等于它自己的 ClassNotFoundException，
     * 表现得和「模块里没有这个类」一模一样。
     *
     * <p>只用宿主也不够：部分模块会在 handleLoadPackage 之外直接
     * {@code Class.forName("com.netease.sky.xxx")}，那要靠 guest 兜底。
     */
    private static final class ModuleClassLoader extends dalvik.system.DexClassLoader {

        private final ClassLoader guest;
        private final String moduleApk;

        ModuleClassLoader(String dexPath, String optDir, ClassLoader host, ClassLoader guest) {
            super(dexPath, optDir, null, host);
            this.guest = guest;
            this.moduleApk = dexPath;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            try {
                return super.loadClass(name, resolve);
            } catch (ClassNotFoundException e) {
                if (guest != null) {
                    return guest.loadClass(name);
                }
                throw e;
            }
        }

        /** 供 ProtectionDomain 桥接取「这个 loader 对应哪个 apk」。 */
        String moduleApk() {
            return moduleApk;
        }

        /**
         * 让模块自己的 {@code System.loadLibrary("xxx")} 能找到 APK 里的 so。
         *
         * <p>ART 解析 native 库时用的是「调用方类的 ClassLoader」：模块类由这里加载，
         * 所以最终会走到这个方法。{@code DexClassLoader} 的默认实现只看
         * {@code nativeLibraryDirectories}，而构造时传的是 null —— 模块 APK 里的
         * {@code lib/arm64-v8a/*.so} 一个都找不到，模块只会收到
         * {@code UnsatisfiedLinkError}，然后被自己的 try/catch 吞掉。
         *
         * <p>这里把 so 从 APK 解到 cache 再返回绝对路径，等效于系统安装时
         * 解压 nativeLib 目录。
         */
        @Override
        public String findLibrary(String name) {
            String hit = super.findLibrary(name);
            if (hit != null) {
                return hit;
            }
            return extractLibrary(moduleApk, name);
        }
    }

    /** 从模块 APK 的 lib/arm64-v8a 里解出 {@code lib<name>.so}，返回可加载的绝对路径。 */
    private static String extractLibrary(String apkPath, String name) {
        if (apkPath == null || name == null) {
            return null;
        }
        String fileName = name.startsWith("lib") ? name + ".so" : "lib" + name + ".so";
        java.util.zip.ZipFile zip = null;
        try {
            File apk = new File(apkPath);
            if (!apk.isFile()) {
                return null;
            }
            zip = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry entry = zip.getEntry("lib/arm64-v8a/" + fileName);
            if (entry == null) {
                return null;
            }
            File dir = new File(hostCacheDir(), "xp_lib/"
                    + Integer.toHexString(apkPath.hashCode()));
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File out = new File(dir, fileName);
            if (!out.exists() || out.length() != entry.getSize()) {
                java.io.InputStream in = zip.getInputStream(entry);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                try {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.flush();
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                    try { fos.close(); } catch (Throwable ignored) {}
                }
                out.setReadable(true, false);
                out.setExecutable(true, false);
            }
            Diag.log("xp", "模块 native 库已就绪 " + out.getAbsolutePath()
                    + " size=" + out.length());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Diag.log("xp", "模块 native 库解压失败 " + fileName + " " + t);
            return null;
        } finally {
            try { if (zip != null) zip.close(); } catch (Throwable ignored) {}
        }
    }

    /** 宿主真实的 cache 目录（不受 guest IO 重定向影响）。 */
    private static File hostCacheDir() {
        try {
            File dir = com.hush.HushCore.getContext().getCacheDir();
            if (dir != null) {
                return dir;
            }
        } catch (Throwable ignored) {
        }
        return new File("/data/local/tmp");
    }

    /**
     * 把 ZLL 系模块的 APK 投放到目标应用的 cache 目录。
     *
     * <p>这类模块（NPatch 派生）不是「加载自己就完事」，它要先找到一个带
     * {@code assets/zll/release.manifest} + {@code lib/arm64-v8a/libWIDGETS_EXPERT.so}
     * 的 APK，用内置 Ed25519 公钥验签，通过后才把 libfeature.so 解出来 load。
     *
     * <p>它找 APK 的顺序是：
     * <ol>
     *   <li>{@code ModuleSource.class.getProtectionDomain().getCodeSource()} ——
     *       Android 上这个方法恒定返回 null，此路必然失败；</li>
     *   <li>{@code context.getApplicationInfo().sourceDir} —— 拿到的是宿主（光遇）的
     *       base.apk，既没有 manifest 也没有 zll 签名，被 skip；</li>
     *   <li>{@code context.getCacheDir()} 下的一级子目录 + 宿主包名，再递归扫
     *       {@code *.apk}（深度 6）。</li>
     * </ol>
     *
     * <p><b>路径必须带宿主包名</b>：它扫描的是
     * {@code new File(cacheDir 的一级子目录, context.getPackageName())}，
     * 不是模块自己的包名。早先放到 {@code npatch/<模块包名>/} 时，文件明明
     * 存在却永远扫不到，日志只剩 {@code No authenticated module source}。
     * 正确落点是 {@code npatch/<宿主包名>/base.apk}。
     */
    private static String stageModuleApk(Context context, String apkPath, String modulePkg) {
        try {
            if (!zipHasEntry(apkPath, "assets/zll/release.manifest")) {
                return null;
            }
            String hostPkg = context.getPackageName();
            File dir = new File(context.getCacheDir(), "npatch/" + hostPkg);
            if (!dir.exists() && !dir.mkdirs()) {
                Diag.log("xp", "模块载荷目录创建失败 " + dir);
                return null;
            }
            File dst = new File(dir, "base.apk");
            File src = new File(apkPath);
            if (dst.exists() && dst.length() == src.length()) {
                // 长度相等不代表内容相等：上一版容器投放过同名文件，版本可能不同。
                // ZLL 自己会按 release.manifest 逐条比 size，陈旧副本必然报
                // "Release file size mismatch"，所以这里先自检，不符就重投。
                int bad = auditPayload(dst.getAbsolutePath());
                if (bad == 0) {
                    Diag.log("xp", "模块载荷已存在 " + dst.getAbsolutePath() + " size=" + dst.length()
                            + " (host=" + hostPkg + " module=" + modulePkg + ")");
                    return dst.getAbsolutePath();
                }
                Diag.log("xp", "载荷副本陈旧，准备重投 " + dst.getAbsolutePath()
                        + " 不符=" + bad + " size=" + dst.length());
                if (!dst.delete()) {
                    Diag.log("xp", "载荷副本删除失败 " + dst);
                }
            }
            java.io.InputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
            int bad = auditPayload(dst.getAbsolutePath());
            Diag.log("xp", "模块载荷已投放 " + dst.getAbsolutePath() + " size=" + dst.length()
                    + " 不符=" + bad + " (host=" + hostPkg + ")");
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            Diag.log("xp", "模块载荷投放失败 " + t);
            return null;
        }
    }

    /**
     * zll（NPatch 派生）模块的入口校验探针。
     *
     * <p>它的 {@code ModuleSource.i()} 会按 {@code assets/zll/release.manifest}
     * 逐条比 entry 的 size 和 sha256，任何一条对不上就抛
     * {@code IOException(Release file size mismatch)}，整个 i() 返回 null，
     * {@code ensureLoaded=false}，native 引擎根本不启动。
     *
     * <p>容器里 payload 副本只要与模块 apk 有一点差异（旧版残留、写盘不完全），
     * 就会卡死在这一步。所以这里做两件事：
     * <ol>
     *   <li>记录它实际选中的 apk 和要校验的条目名（下次出问题不用再猜）；</li>
     *   <li>跳过 {@code l(String)} 的纯校验逻辑。它只读 entry 做比对，没有副作用；
     *       真正解压 so 的 {@code d(File)} 还会再校验一次 size。</li>
     * </ol>
     */
    private static synchronized void installZllBridge(ClassLoader loader, String apkPath) {
        try {
            if (loader == null || apkPath == null) return;
            if (!zipHasEntry(apkPath, "assets/zll/release.manifest")) return;
            if (!sZllHooked.add(apkPath)) return;
            Class<?> ms = Class.forName("com.sky.zll.ModuleSource", false, loader);
            int hooked = 0;
            for (java.lang.reflect.Constructor<?> ctor : ms.getDeclaredConstructors()) {
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(ctor,
                            new de.robv.android.xposed.XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    // 第二个参数是 Z，含义是「宿主上下文，需要自解压到 codeCache」。
                                    // 模块自己算的是 !("com.sky.zll".equals(ctx.getPackageName()))，
                                    // 而容器为了骗过模块的盗版校验把 getPackageName() 返回成了
                                    // com.sky.zll —— 于是这里变成 false，模块改走 nativeLibraryDir，
                                    // 去 /data/app/.../lib/arm64 找 libWIDGETS_EXPERT.so（容器里不存在），
                                    // 1 毫秒内就抛 IOException(Release file size mismatch)。
                                    // 强制成 true，让它走真机上那条「解压到 codeCacheDir/modlibs」的路。
                                    try {
                                        if (param.args.length >= 2 && param.args[1] instanceof Boolean) {
                                            boolean b = (Boolean) param.args[1];
                                            if (!b) {
                                                param.args[1] = Boolean.TRUE;
                                                Diag.log("xp", "zll 构造参数 b=false -> 强制 true（改走自解压分支）");
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object file = firstFileField(param.thisObject);
                                        Diag.log("xp", "zll 选中源 " + file
                                                + (file instanceof File
                                                ? " size=" + ((File) file).length() : ""));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                    hooked++;
                } catch (Throwable t) {
                    Diag.log("xp", "zll 构造器探针失败 " + t);
                }
            }
            for (java.lang.reflect.Method m : ms.getDeclaredMethods()) {
                if (!"l".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || pt[0] != String.class
                        || m.getReturnType() != void.class) {
                    continue;
                }
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(m,
                            new de.robv.android.xposed.XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    try {
                                        Object name = param.args.length > 0 ? param.args[0] : null;
                                        Object file = firstFileField(param.thisObject);
                                        Diag.log("xp", "zll 跳过校验 " + name + " apk=" + file);
                                        param.setResult(null);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                    hooked++;
                } catch (Throwable t) {
                    Diag.log("xp", "zll 校验探针失败 " + t);
                }
            }
            // 引导结果诊断：失败时把两个暂存目录的真实状态写出来，
            // 下次不用再靠猜「是没解压出来，还是解压出来长度不对」。
            try {
                Class<?> boot = Class.forName("com.sky.zll.a3", false, loader);
                for (java.lang.reflect.Method bm : boot.getDeclaredMethods()) {
                    if (!"b".equals(bm.getName())) continue;
                    Class<?>[] pt = bm.getParameterTypes();
                    if (pt.length != 1 || pt[0] != android.content.Context.class
                            || bm.getReturnType() != boolean.class) {
                        continue;
                    }
                    de.robv.android.xposed.XposedBridge.hookMethod(bm,
                            new de.robv.android.xposed.XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    // 模块的暂存目录只在异常分支里清理。上一次启动如果被
                                    // 杀在解压中间，这两个目录会留到下一次，而它对
                                    // mkdirs() 返回 false 的处理是直接抛「Cannot create
                                    // staging」——一次失败之后永远失败。这里每次先清干净。
                                    try {
                                        android.content.Context ctx =
                                                (android.content.Context) param.args[0];
                                        File base = ctx.getCodeCacheDir();
                                        for (String name : new String[]{"modlibs", "modfonts"}) {
                                            File dir = new File(base, name);
                                            if (dir.exists()) {
                                                deleteTree(dir);
                                                Diag.log("xp", "zll 清空旧暂存 " + dir);
                                            }
                                        }
                                    } catch (Throwable t) {
                                        Diag.log("xp", "zll 清空暂存失败 " + t);
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object r = param.getResult();
                                        boolean ok = r instanceof Boolean && (Boolean) r;
                                        dumpStaging((android.content.Context) param.args[0], ok);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                    hooked++;
                }
            } catch (Throwable t) {
                Diag.log("xp", "zll 引导探针失败 " + t);
            }
            Diag.log("xp", "zll 探针已安装 方法=" + hooked + " apk=" + apkPath);
        } catch (Throwable t) {
            Diag.log("xp", "zll 探针安装失败 " + t);
        }
    }

    /** 递归删除一个目录，失败不抛。 */
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteTree(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 打印 zll 暂存目录（codeCacheDir/modlibs、modfonts）的真实落盘情况。 */    private static void dumpStaging(android.content.Context ctx, boolean ok) {
        try {
            File base = ctx.getCodeCacheDir();
            StringBuilder sb = new StringBuilder();
            sb.append("zll 引导结果=").append(ok).append(" codeCache=").append(base)
                    .append(" exists=").append(base != null && base.exists());
            for (String name : new String[]{"modlibs", "modfonts"}) {
                File dir = new File(base, name);
                sb.append(" | ").append(name).append(" exists=").append(dir.isDirectory());
                File[] list = dir.listFiles();
                if (list == null) {
                    sb.append(" list=null");
                    continue;
                }
                sb.append(" n=").append(list.length);
                for (File f : list) {
                    sb.append(" ").append(f.getName()).append(":").append(f.length());
                }
            }
            Diag.log("xp", sb.toString());
        } catch (Throwable t) {
            Diag.log("xp", "zll 暂存目录诊断失败 " + t);
        }
    }

    /** 取模块对象里的第一个 File 字段。字段名被混淆过，按类型找更稳。 */
    private static Object firstFileField(Object self) {
        if (self == null) return null;
        for (java.lang.reflect.Field f : self.getClass().getDeclaredFields()) {
            if (f.getType() == File.class) {
                try {
                    f.setAccessible(true);
                    return f.get(self);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 让模块类的 {@code Class#getProtectionDomain()} 返回一个带 CodeSource 的
     * ProtectionDomain。
     *
     * <p>起因是 ZLL 系（NPatch 派生）模块的定位逻辑：
     * <pre>
     * ModuleSource.class.getProtectionDomain().getCodeSource().getLocation()
     * </pre>
     * 这条在真机上返回模块自己的 apk 路径；容器里模块类由 {@link ModuleClassLoader}
     * 加载，ART 给的是 null，模块只打进一条
     * {@code ModuleSource: codeSource err java.lang.NullPointerException} 然后退化到
     * 「扫 cacheDir 找 npatch 载荷」——它接着用宿主 apk 的路径去做完整性校验，
     * 于是 {@code authenticated native bootstrap failed: IOException(Release file size mismatch)}，
     * {@code ensureLoaded=false}，功能全废。
     *
     * <p>这里按模块的 apk 造一个 CodeSource（证书从 APK 签名取，模块若校验
     * {@code getCertificates()} 也能对上），让模块走回真机那条路。
     */
    private static synchronized void installProtectionDomainBridge() {
        if (sPdBridgeInstalled) return;
        sPdBridgeInstalled = true;
        try {
            java.lang.reflect.Method target = Class.class.getDeclaredMethod("getProtectionDomain");
            de.robv.android.xposed.XposedBridge.hookMethod(target,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object self = param.thisObject;
                                if (!(self instanceof Class)) return;
                                Class<?> clazz = (Class<?>) self;
                                ClassLoader cl = clazz.getClassLoader();
                                if (!(cl instanceof ModuleClassLoader)) return;
                                // 关键：ART 对 DexClassLoader 定义的类返回的是一个
                                // 非 null 的 ProtectionDomain，但里面的 CodeSource 是
                                // null。所以不能只看 result == null —— 上一版就是
                                // 这么写的，结果一次都没命中，模块仍然看到 codeSource=null。
                                Object result = param.getResult();
                                if (result instanceof java.security.ProtectionDomain) {
                                    java.security.CodeSource cs =
                                            ((java.security.ProtectionDomain) result).getCodeSource();
                                    if (cs != null && cs.getLocation() != null) {
                                        return;
                                    }
                                }
                                String apk = ((ModuleClassLoader) cl).moduleApk();
                                String payload = sPayloadOf.get(apk);
                                // 优先用容器内真实存在的 payload 副本：虚拟安装路径
                                // /data/app/... 在容器里 isFile() 可能为 false，模块
                                // 拿到也没法打开。
                                java.security.ProtectionDomain pd =
                                        buildProtectionDomain(payload != null ? payload : apk);
                                if (pd != null) {
                                    param.setResult(pd);
                                    if (sPdHit++ < 3) {
                                        Diag.log("xp", "ProtectionDomain 命中 "
                                                + clazz.getName() + " -> "
                                                + pd.getCodeSource().getLocation());
                                    }
                                } else if (sPdMiss++ < 3) {
                                    Diag.log("xp", "ProtectionDomain 未构造 " + clazz.getName()
                                            + " apk=" + apk + " payload=" + payload);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Diag.log("xp", "ProtectionDomain 桥接已安装");
        } catch (Throwable t) {
            Diag.log("xp", "ProtectionDomain 桥接失败 " + t);
        }
    }

    private static java.security.ProtectionDomain buildProtectionDomain(String apkPath) {
        if (apkPath == null) return null;
        try {
            File apk = new File(apkPath);
            if (!apk.isFile()) return null;
            java.security.cert.Certificate[] certs = null;
            try {
                android.content.pm.PackageInfo pi = com.hush.HushCore.getContext()
                        .getPackageManager()
                        .getPackageArchiveInfo(apkPath, android.content.pm.PackageManager.GET_SIGNATURES);
                if (pi != null && pi.signatures != null && pi.signatures.length > 0) {
                    java.security.cert.CertificateFactory cf =
                            java.security.cert.CertificateFactory.getInstance("X.509");
                    certs = new java.security.cert.Certificate[pi.signatures.length];
                    for (int i = 0; i < pi.signatures.length; i++) {
                        certs[i] = cf.generateCertificate(
                                new java.io.ByteArrayInputStream(pi.signatures[i].toByteArray()));
                    }
                }
            } catch (Throwable ignored) {
            }
            java.security.CodeSource cs =
                    new java.security.CodeSource(apk.toURI().toURL(), certs);
            // permissions 不能传 null：模块如果顺手取 getPermissions() 会 NPE。
            java.security.Permissions perms = new java.security.Permissions();
            perms.setReadOnly();
            return new java.security.ProtectionDomain(cs, perms);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 payload 和 manifest 逐条对一遍，只比大小（不解压，速度够快）。
     * ZLL 报 {@code Release file size mismatch} 时，这条日志能直接指出是哪个 entry。
     */
    private static int auditPayload(String apkPath) {
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apkPath);
            java.util.zip.ZipEntry man = zf.getEntry("assets/zll/release.manifest");
            if (man == null) return -1;
            String text;
            java.io.InputStream in = zf.getInputStream(man);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                text = bos.toString("UTF-8");
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
            StringBuilder bad = new StringBuilder();
            int total = 0, miss = 0;
            for (String line : text.split("\n")) {
                if (!line.startsWith("file\t")) continue;
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                total++;
                String name = p[1];
                long want;
                try { want = Long.parseLong(p[2].trim()); } catch (Throwable t) { continue; }
                java.util.zip.ZipEntry e = zf.getEntry(name);
                long got = e == null ? -1 : e.getSize();
                if (got != want) {
                    miss++;
                    if (bad.length() > 0) bad.append(", ");
                    bad.append(name).append(": manifest=").append(want)
                            .append(" apk=").append(got);
                }
            }
            Diag.log("xp", "payload 校验 " + apkPath + " 条目=" + total
                    + " 不符=" + miss + (miss > 0 ? " [" + bad + "]" : ""));
            return miss;
        } catch (Throwable t) {
            Diag.log("xp", "payload 校验失败 " + t);
            return -1;
        } finally {
            try { if (zf != null) zf.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean zipHasEntry(String apkPath, String entryName) {
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apkPath);
            return zf.getEntry(entryName) != null;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (zf != null) zf.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 打开模块 APK。
     *
     * <p>优先 DexClassLoader + 显式 oat 目录：容器里 /data/dalvik-cache 不一定可写，
     * PathClassLoader 遇到写不了 oat 会留一个空 dex 元素，loadClass 直接
     * ClassNotFoundException，看起来就像"模块里没这个类"。
     */
    private static ClassLoader openModule(String apkPath, ClassLoader parent, Context context) {
        // oat 目录必须是「真实存在且可写」的路径。guest 的 code_cache 在 Java 层
        // 看着正常，但那是 IO 重定向出来的虚拟路径，ART 写 oat 时不一定落得下去；
        // 落不下去的后果是所有类都 ClassNotFoundException。宿主自己的目录最稳。
        java.util.List<File> dirs = new ArrayList<>();
        File hostCache = hostCodeCache();
        if (hostCache != null) {
            dirs.add(new File(hostCache, "xp"));
        }
        try {
            dirs.add(new File(context.getCodeCacheDir(), "xp"));
            dirs.add(new File(context.getCacheDir(), "xp"));
        } catch (Throwable ignored) {
        }
        // parent 必须能解析 de.robv.android.xposed.*：这些类在宿主 dex 里，
        // 只有加载 HushCore 的 ClassLoader 一定看得到。
        ClassLoader host = XposedModuleLoader.class.getClassLoader();
        for (File dir : dirs) {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    continue;
                }
                return new ModuleClassLoader(apkPath, dir.getAbsolutePath(), host, parent);
            } catch (Throwable t) {
                Diag.log("xp", "DexClassLoader@" + dir + " 失败 " + t);
            }
        }
        Diag.log("xp", "没有可写 oat 目录，退回 PathClassLoader");
        return new PathClassLoader(apkPath, host);
    }

    /** 宿主 Application 的 code_cache：真实路径，不受容器 IO 重定向影响。 */
    private static File hostCodeCache() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object app = at.getMethod("getApplication").invoke(thread);
            if (app instanceof android.app.Application) {
                return ((android.app.Application) app).getCodeCacheDir();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 模块 APK 概况：类加载失败时用它区分「dex 不在」和「类名写错」。 */
    private static String describeApk(String apkPath) {
        java.util.zip.ZipFile zip = null;
        try {
            File f = new File(apkPath);
            StringBuilder sb = new StringBuilder("apk=").append(f.length() / 1024).append("KB dex=[");
            zip = new java.util.zip.ZipFile(f);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            boolean first = true;
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry entry = en.nextElement();
                String n = entry.getName();
                if (n.startsWith("classes") && n.endsWith(".dex")) {
                    if (!first) sb.append(',');
                    // 带上体积：单 dex 只有几十 KB 说明这是加固壳，真实代码不在里面。
                    sb.append(n).append(':').append(entry.getSize() / 1024).append("KB");
                    first = false;
                }
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "apk=?(" + t + ")";
        } finally {
            try {
                if (zip != null) zip.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** APK 里 AndroidManifest 声明的包名：和模块记录对不上就说明路径接错了。 */
    private static String archivePackage(String apkPath) {
        try {
            android.content.pm.PackageInfo info = com.hush.HushCore.getContext()
                    .getPackageManager().getPackageArchiveInfo(apkPath, 0);
            return info == null ? "读不到" : info.packageName;
        } catch (Throwable t) {
            return "!(" + t + ")";
        }
    }

    /** 入口类名：以 APK 内的 xposed_init 为准，读不到才用安装时缓存的 main。 */
    private static List<String> entryClasses(InstalledModule module, String apkPath) {
        List<String> list = new ArrayList<>();
        // 先读文件：模块升级过、或安装时解析到的还是旧包，缓存里的 main 会指错类。
        String main = readXposedInit(apkPath);
        if (main == null || main.trim().isEmpty()) {
            main = module.main;
        }
        if (main == null) {
            return list;
        }
        for (String line : main.split("\\r?\\n")) {
            String name = line.trim();
            if (!name.isEmpty() && !name.startsWith("#")) {
                list.add(name);
            }
        }
        return list;
    }

    private static String readXposedInit(String apkPath) {
        java.util.zip.ZipFile zip = null;
        java.io.BufferedReader reader = null;
        try {
            zip = new java.util.zip.ZipFile(new File(apkPath));
            java.util.zip.ZipEntry entry = zip.getEntry("assets/xposed_init");
            if (entry == null) {
                return null;
            }
            reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(zip.getInputStream(entry), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
            }
            try {
                if (zip != null) zip.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 给宿主 UI 用于提示当前进程已经挂了多少 hook（调试用）。 */
    public static int hookCount() {
        return XposedEngine.hookCount();
    }

    /** 让 BXposedManagerService 的常量被引用，避免被裁剪。 */
    static void touch() {
        BXposedManagerService.get();
    }
}
