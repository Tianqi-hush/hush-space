package com.hush.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import com.hush.activation.SdkBridge;
import com.hush.utils.Diag;
import android.webkit.WebView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.hush.env.android.app.ActivityThreadAppBindDataContext;
import com.hush.env.android.app.BRActivity;
import com.hush.env.android.app.BRActivityManagerNative;
import com.hush.env.android.app.BRActivityThread;
import com.hush.env.android.app.BRActivityThreadActivityClientRecord;
import com.hush.env.android.app.BRActivityThreadAppBindData;
import com.hush.env.android.app.BRActivityThreadNMR1;
import com.hush.env.android.app.BRActivityThreadQ;
import com.hush.env.android.app.BRContextImpl;
import com.hush.env.android.app.BRLoadedApk;
import com.hush.env.android.app.BRService;
import com.hush.env.android.content.BRBroadcastReceiver;
import com.hush.env.android.content.BRContentProviderClient;
import com.hush.env.android.graphics.BRCompatibility;
import com.hush.env.android.security.net.config.BRNetworkSecurityConfigProvider;
import com.hush.env.com.android.internal.content.BRReferrerIntent;
import com.hush.env.dalvik.system.BRVMRuntime;

import com.hush.HushCore;
import com.hush.app.configuration.AppLifecycleCallback;
import com.hush.app.dispatcher.AppServiceDispatcher;
import com.hush.core.CrashHandler;
import com.hush.core.IBActivityThread;
import com.hush.core.VCore;
import com.hush.core.VNative;
import com.hush.core.env.VirtualRuntime;
import com.hush.core.system.user.BUserHandle;
import com.hush.entity.AppConfig;
import com.hush.entity.am.ReceiverData;
import com.hush.entity.pm.InstalledModule;
import com.hush.fake.delegate.AppInstrumentation;
import com.hush.fake.delegate.ContentProviderDelegate;
import com.hush.fake.frameworks.BXposedManager;
import com.hush.fake.hook.HookManager;
import com.hush.fake.service.HCallbackStub;
import com.hush.utils.Reflector;
import com.hush.utils.Slog;
import com.hush.utils.compat.ActivityManagerCompat;
import com.hush.utils.compat.BuildCompat;
import com.hush.utils.compat.ContextCompat;
import com.hush.utils.compat.StrictModeCompat;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";
    private static final Object mConfigLock = new Object();
    private static volatile BActivityThread sBActivityThread;
    private AppConfig mAppConfig;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = HushCore.get().getHandler();

    public static class AppBindData {
        ApplicationInfo appInfo;
        Object info;
        String processName;
        List<ProviderInfo> providers;
    }

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) return getAppConfig().processName;
        if (currentActivityThread().mBoundApplication != null) return currentActivityThread().mBoundApplication.processName;
        return null;
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) return getAppConfig().packageName;
        if (currentActivityThread().mInitialApplication != null) return currentActivityThread().mInitialApplication.getPackageName();
        return null;
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(HushCore.getHostUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? HushCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (this.mAppConfig != null && !this.mAppConfig.packageName.equals(appConfig.packageName)) {
                throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
            }
            this.mAppConfig = appConfig;
            final IBinder iBinder = asBinder();
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            synchronized (BActivityThread.mConfigLock) {
                                try {
                                    iBinder.linkToDeath(this, 0);
                                } catch (RemoteException e) {
                                    // ignore
                                }
                                BActivityThread.this.mAppConfig = null;
                            }
                        }
                    }, 0);

            } catch (RemoteException e) {
                Log.e(TAG, "error", e);
            }
        }
    }

    public boolean isInit() {
        return this.mBoundApplication != null;
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!isInit()) bindApplication(serviceInfo.packageName, serviceInfo.processName);
        try {
            Class<?> clazz = BRLoadedApk.get(this.mBoundApplication.info).getClassLoader().loadClass(serviceInfo.name);
            Object instance = clazz.newInstance();
            Context context = createPackageContext(serviceInfo.applicationInfo);
            Service service;
            if (instance instanceof Service) {
                service = (Service) instance;
            } else {
                // 2.6.59：HMS runtimekit 的「虚拟服务」（HwAccMgrService /
                // HwidInnerService 都继承 KitService -> KitContext）不是
                // android.app.Service，以前直接返回 null，导致 HMS core 登录
                // 成功后 bind HwidInnerService 拿到 binder=false，客户端等
                // 5 秒超时后判定登录失败 —— 用户看到的就是「登录成功又让重登」。
                // 这里把它托管到一个真正的 Service 壳上；HwidInnerService
                // 的 onBind 本身只是返回构造函数里创建好的 Binder，完全可用。
                KitServiceShim shim = KitServiceShim.wrap(instance, clazz);
                if (shim == null) {
                    Diag.log("svc", "跳过虚拟服务 " + serviceInfo.name);
                    return null;
                }
                try {
                    // KitService 自带：attachBaseContext + 设置 hmsAppContext，
                    // 让 kit 服务能用 getFilesDir/getApplication 等常规 API。
                    java.lang.reflect.Method m = clazz.getMethod(
                            "setHmsContextAsBase", Context.class);
                    m.invoke(instance, context);
                } catch (Throwable ignored) {
                }
                Diag.log("svc", "kit 服务代理 " + serviceInfo.name);
                service = shim;
            }
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(context, HushCore.mainThread(), serviceInfo.name,token, this.mInitialApplication, BRActivityManagerNative.get().getDefault());
            ContextCompat.fix(context);
            service.onCreate();
            return service;
        } catch (Exception e) {
            Log.e(TAG, "error", e);
            if (serviceInfo.name != null && serviceInfo.name.startsWith("com.huawei.")) {
                Diag.log("svc", "HMS 服务创建失败，跳过 " + serviceInfo.name + " " + e);
                return null;
            }
            throw new RuntimeException("Unable to create service " + serviceInfo.name, e);
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!isInit()) bindApplication(serviceInfo.packageName, serviceInfo.processName);
        try {
            Class<?> clazz = BRLoadedApk.get(this.mBoundApplication.info).getClassLoader().loadClass(serviceInfo.name);
            if (!JobService.class.isAssignableFrom(clazz)) {
                Log.w(TAG, "Class " + serviceInfo.name + " is not a JobService! Skipping instantiation.");
                return null;
            }
            JobService service = (JobService) clazz.newInstance();
            Context context = createPackageContext(serviceInfo.applicationInfo);
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(context, HushCore.mainThread(), serviceInfo.name,getActivityThread(), this.mInitialApplication, BRActivityManagerNative.get().getDefault());
            ContextCompat.fix(context);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            Log.e(TAG, "error", e);
            throw new RuntimeException("Unable to create JobService " + serviceInfo.name, e);
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        // Always call handleBindApplication on the current thread directly.
        // The old pattern of posting to the main looper and blocking with ConditionVariable
        // caused priority inversion: background service dispatcher threads held locks while
        // waiting for the main thread, which was itself blocked acquiring the same lock.
        // Under concurrent GMS sub-process spawning this produced cascading 4-9s ANR-LOG entries.
        // handleBindApplication is synchronized so concurrent calls are serialized safely.
        handleBindApplication(packageName, processName);
    }


    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        try {
            Diag.init(HushCore.getContext());
        } catch (Throwable ignored) {
        }
        Diag.log("bind", ">> handleBindApplication pkg=" + packageName + " proc=" + processName);
        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }
        Binder.clearCallingIdentity();
        PackageInfo packageInfo = HushCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        Diag.log("bind", "packageInfo ok targetSdk=" + applicationInfo.targetSdkVersion
                + " srcDir=" + applicationInfo.sourceDir
                + " nativeLib=" + applicationInfo.nativeLibraryDir);
        try {
            File libDir = new File(applicationInfo.nativeLibraryDir);
            String[] libs = libDir.list();
            Diag.log("bind", "guest libs count=" + (libs == null ? -1 : libs.length));
            // 加固壳（网易 unisec）的两个入口库必须能按名字 dlopen，否则
            // 壳不会解密业务 dex。这里直接把结果记下来，省得下次再猜。
            boolean hasUnisec = new File(libDir, "libunisec.so").exists();
            boolean hasMainruns = new File(libDir, "libandroidmainruns.so").exists();
            Diag.log("bind", "guest shell libs unisec=" + hasUnisec + " androidmainruns=" + hasMainruns);
            // 把 so 名字全打出来。壳的判定靠这个：报「打不开」时，看一眼
            // 这里有没有 libjiagu / libDexHelper / libshell 之类就能定性，
            // 不用再让用户反复导出诊断包猜。
            if (libs != null && libs.length > 0) {
                StringBuilder sb = new StringBuilder();
                int limit = Math.min(libs.length, 60);
                for (int i = 0; i < limit; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(libs[i]);
                }
                if (libs.length > limit) sb.append(" …(共 ").append(libs.length).append(")");
                Diag.log("bind", "guest libs: " + sb);
            }
            ensureGuestLibs(applicationInfo.sourceDir, libDir);
        } catch (Throwable ignored) {
        }
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));
        Object boundApplication = BRActivityThread.get(HushCore.mainThread()).mBoundApplication();
        Context packageContext = createPackageContext(applicationInfo);
        Diag.log("bind", "createPackageContext ok -> " + packageContext);
        Object loadedApk = BRContextImpl.get(packageContext).mPackageInfo();
        BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
        // fix applicationInfo
        BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);
        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        VirtualRuntime.setupRuntime(processName, applicationInfo);
        BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        if (BuildCompat.isS()) {
            BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);
        }
        // hidden API 豁免必须在 HushHook 干活之前完成：它的 Init 要访问
        // ArtMethod 的内部字段、要 FromReflectedMethod 一堆 @hide 成员，
        // 不豁免就会被 ART 拦掉（这也是上一版 init=0 的原因之一）。
        try {
            boolean exempt = com.hush.reflection.akshit.BootstrapClass.exemptAll();
            Diag.log("bind", "hiddenApiExempt=" + exempt);
        } catch (Throwable t) {
            Diag.log("bind", "hiddenApiExempt failed " + t);
        }
        Diag.log("bind", "before VNative.init api=" + Build.VERSION.SDK_INT);
        VNative.init(Build.VERSION.SDK_INT);
        Diag.log("bind", "VNative.init ok");
        // 先判断这个 guest 是不是需要 ART 兼容的壳，**必须在 enableIO 之前**：
        // enableIO 里会根据这个开关决定要不要跑 HushHook 的 Init。普通 App
        // 一律不跑 —— 那是对 libart 做 inline hook，收益为零、风险全是它的。
        boolean shellGuest = false;
        String shellWhy = null;
        try {
            shellWhy = com.hush.core.hook.HookPolicy.detect(applicationInfo);
            shellGuest = shellWhy != null;
        } catch (Throwable t) {
            Diag.log("bind", "shell detect failed " + t);
        }
        // 启动守卫：上一次同一个包在这个空间里没走到 bound 就死了，说明
        // 当前这套 hook 组合对它不成立。加固壳在没有 ART 兼容的情况下
        // 必崩（壳在 attachBaseContext 里校验 JNI ID），所以第二次一律
        // 强制走 shell 兼容路径再试一次，而不是让用户永远点不开。
        int lastFails = 0;
        try {
            if (com.hush.core.HmsCore.isHmsPackage(packageName)) {
                // HMS / 账号 kit 启动失败通常是我们自己的容器问题（缺类、
                // uid 对不上），不是加固壳。按「连续失败」把它们当壳来跑
                // ART hook 只会越修越坏，直接跳过这套重试。
                Diag.log("guard", "HMS 系统包跳过壳重试 " + packageName);
            } else {
                lastFails = com.hush.core.hook.LaunchGuard.begin(packageName, getUserId());
                if (lastFails > 0 && !shellGuest) {
                    shellGuest = true;
                    shellWhy = "retry-after-crash x" + lastFails;
                }
            }
        } catch (Throwable t) {
            Diag.log("bind", "启动守卫失败 " + t);
        }
        Diag.log("bind", "shellHooks=" + shellGuest + (shellWhy == null ? "" : " why=" + shellWhy));
        // Xposed 模块也走 HushHook，总闸必须在这之前打开：Init 是在
        // enableIO 里由后台线程跑起来的，晚了就只能现场等，启动会被拖慢。
        boolean xpNeeded = false;
        try {
            xpNeeded = com.hush.core.xposed.XposedModuleLoader.needed();
        } catch (Throwable t) {
            Diag.log("xp", "needed 判定失败 " + t);
        }
        try {
            VNative.setHookGate(shellGuest || xpNeeded);
            if (xpNeeded) {
                Diag.log("xp", "gate on for xposed");
            }
        } catch (Throwable t) {
            Diag.log("bind", "setHookGate failed " + t);
        }
        assert packageContext != null;
        // 空间级环境开关必须在 enableRedirect 之前落到 SdkBridge 的静态字段。
        //
        // enableRedirect 内部会读 setHideRoot()/sHideXposed 决定要不要装
        // su / Xposed 的隐藏规则；而 beforeCreateApplication 回调要到
        // makeApplication 之前才执行（见下面 399 行），那时隐藏规则早就装完了。
        // 结果是：设置页里改「隐藏 Root / 隐藏 Xposed」永远只对宿主生效，
        // 空间里的开关形同虚设。这里提前读一次该空间的配置。
        try {
            com.hush.entity.SpaceSettings spaceCfg = HushCore.get().resolveSpaceSettings(getUserId());
            HushCore.setHideRoot(Boolean.TRUE.equals(spaceCfg.hideRoot));
            HushCore.setHideXposed(!Boolean.FALSE.equals(spaceCfg.hideXposed));
            HushCore.setEnableDaemonService(!Boolean.FALSE.equals(spaceCfg.daemon));
            Diag.log("bind", "空间配置预置 userId=" + getUserId()
                    + " hideRoot=" + spaceCfg.hideRoot
                    + " hideXposed=" + spaceCfg.hideXposed
                    + " daemon=" + spaceCfg.daemon);
        } catch (Throwable t) {
            Diag.log("bind", "空间配置预置失败 " + t);
        }
        VCore.get().enableRedirect(packageContext);
        Diag.log("bind", "enableRedirect ok");
        // 以下 hook 必须在 enableRedirect 之后：这些 hook 内部会判断/改写文件路径，
        // 得先让 IO 重定向生效；同时必须早于 makeApplication，壳的校验全在 StubApp 里。
        //
        // 签名必须先于 hook 取好：取签名本身要查一次 getPackageInfo，如果 hook
        // 已经挂上，这次查询会再进 hook，直接递归。
        if (shellGuest) {
            // 心跳与主线程栈采样是排查壳崩溃用的重诊断：采样线程要
            // 周期性 suspend 主线程抓栈。它对普通 App 只有伤害、没有收益，
            // 所以只在白名单壳上开，普通 App 一个线程都不起。
            startHeartbeat();
            installExitTracer();
            try {
                com.hush.core.hook.SignatureCompat.prime(packageName, BActivityThread.getUserId());
            } catch (Throwable t) {
                Diag.log("sign", "prime failed " + t);
            }
        }
        try {
            com.hush.core.hook.LsHooks.install(shellGuest);
        } catch (Throwable t) {
            Diag.log("bind", "LsHooks failed " + t);
        }
        // IO 重定向装好之后再铺外部目录：否则 mkdirs 会走未重定向的原始路径
        try {
            com.hush.core.env.BEnvironment.ensureExternalDirs(packageName);
            Diag.log("bind", "external dirs ready -> "
                    + com.hush.core.env.BEnvironment.getExternalDataDir(packageName));
        } catch (Throwable t) {
            Diag.log("bind", "external dirs FAILED " + t);
        }
        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;
        ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
        activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
        activityThreadAppBindData._set_appInfo(bindData.appInfo);
        activityThreadAppBindData._set_info(bindData.info);
        activityThreadAppBindData._set_processName(bindData.processName);
        activityThreadAppBindData._set_providers(bindData.providers);
        mBoundApplication = bindData;
        //ssl适配
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Application application;
        try {
            Diag.log("bind", "before onBeforeCreateApplication");
            onBeforeCreateApplication(packageName, processName, packageContext);
            Diag.log("bind", "before makeApplication");
            // Application 的构造/attach 必须发生在有 Looper 的线程上。正常启动时
            // handleBindApplication 本来就在主线程；但 provider 拉起进程那条路是从
            // IBActivityThread 的 binder 线程进来的，HMS 的 CoreApplication.attachBaseContext
            // 第一件事就是 new Handler()，在 binder 线程上直接抛
            // "Can't create handler inside thread ... that has not called Looper.prepare()"，
            // 于是 com.huawei.hwid.persistent 这种进程永远起不来。
            // CoreApplication 在 attachBaseContext 里就初始化了 NetworkUtil /
            // Manager / Tracker 这些单例。容器里它的 e/f 静态 Context 只有
            // makeApplication 之后才补，那时已经晚了一步 —— 先拿 packageContext
            // 提前填上，Application 出来后再换成 Application 本身。
            try {
                com.hush.core.HmsKitCtx.preInstall(packageContext, packageName);
            } catch (Throwable t) {
                Diag.log("bind", "kit ctx pre 失败 " + t);
            }
            final Object loadedApkF = loadedApk;
            application = callOnGuestMain("makeApplication",
                    () -> BRLoadedApk.getWithException(loadedApkF).makeApplication(false, null));
            Diag.log("bind", "makeApplication ok -> " + (application == null ? "null" : application.getClass().getName()));
            if (application == null) {
                throw new RuntimeException("makeApplication returned null for " + packageName);
            }
            // 2.6.69：HMS AIDL 两端探针。客户端在第三方 App 进程里看不到 HMS 的日志，
            // 只能自己记录 transact；服务端在 hwid 进程里记录 onTransact 是否真的被调用。
            try {
                com.hush.utils.HmsAidlProbe.install();
                com.hush.utils.HmsAidlProbe.installServer(application.getClassLoader());
                // 2.6.71：SDK 自带的 AIDL 代理类是 Java 方法，直接记录方法名/返回/异常。
                com.hush.utils.HmsAidlProbe.installProxy(application.getClassLoader());
            } catch (Throwable t) {
                Diag.log("aidl", "探针安装异常 " + t);
            }
            // 2.6.75：HWID/kit 进程里的服务端校验（调用方包名、OOBE 网络门）。
            // 这两道门在容器里必然失败，导致所有 asyncCall 直接回 907135000/907135007。
            if (packageName != null && (packageName.startsWith("com.huawei.hwid")
                    || packageName.startsWith("com.huawei.hms"))) {
                try {
                    com.hush.utils.HmsProbe.installHmsCore(application.getClassLoader());
                } catch (Throwable t) {
                    Diag.log("hms", "服务进程探针安装异常 " + t);
                }
            }
            setPhase("fix app context");
            Diag.log("bind", "fix app ctx begin");
            ContextCompat.fix(application);
            Diag.log("bind", "fix app ctx ok");
            // kit 包被降级成 android.app.Application 后，CoreApplication 的
            // 静态 context 一直是 null，账号页在构造时就会 NPE（2.6.34）。
            try {
                com.hush.core.HmsKitCtx.install(application, applicationInfo.packageName, processName);
            } catch (Throwable t) {
                Diag.log("bind", "kit ctx 失败 " + t);
            }
            // Application#attach 是 final 小方法，ART 会把它内联进
            // Instrumentation.newApplication，HushHook 改方法入口拦不到那次调用。
            // 只 hook 它来做初始化的模块（zll 就是）会一直停在「hook 已挂、从不触发」。
            // attachBaseContext 同理，壳重写过的能触发，没重写的同样会被内联。
            try {
                int n = com.hush.core.xposed.XposedEngine.replayMissed(
                        android.app.Application.class, "attach",
                        application, new Object[]{packageContext}, "makeApplication");
                n += com.hush.core.xposed.XposedEngine.replayMissed(
                        android.app.Application.class, "attachBaseContext",
                        application, new Object[]{packageContext}, "makeApplication");
                if (n > 0) {
                    Diag.log("bind", "补触发 Application 生命周期 hook x" + n);
                }
                Diag.log("bind", "overlay=" + android.provider.Settings.canDrawOverlays(application));
            } catch (Throwable t) {
                Diag.log("bind", "补触发 Application hook 失败 " + t);
            }
            try {
                Diag.log("bind", "app cl=" + describeLoader(application.getClassLoader()));
            } catch (Throwable ignored) {
            }
            setPhase("fix system context");
            ContextCompat.fix((Context) BRActivityThread.get(HushCore.mainThread()).getSystemContext());
            Diag.log("bind", "fix system ctx ok");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "com.tencent.mm:recovery".equals(processName)) {
                fixWeChatRecovery(application);
            }
            mInitialApplication = application;
            BRActivityThread.get(HushCore.mainThread())._set_mInitialApplication(mInitialApplication);
            List<ProviderInfo> providers;
            setPhase("installProviders");
            Diag.log("bind", "installProviders begin count=" + (bindData.providers == null ? 0 : bindData.providers.size()));
            installProviders(mInitialApplication, bindData.processName, bindData.providers, shellGuest);
            Diag.log("bind", "installProviders ok");
            try {
				fixAiLiaoPhoto(mInitialApplication);
			} catch (Throwable e) {
				e.printStackTrace();
			}
            setPhase("onBeforeApplicationOnCreate");
            Diag.log("bind", "before onBeforeApplicationOnCreate");
            onBeforeApplicationOnCreate(packageName, processName, application);
            Diag.log("bind", "before callApplicationOnCreate");
            setPhase("Application.onCreate");
            final Application appF = application;
            callOnGuestMain("Application.onCreate", () -> {
                AppInstrumentation.get().callApplicationOnCreate(appF);
                return null;
            });
            Diag.log("bind", "after callApplicationOnCreate");
            setPhase("bound");
            onAfterApplicationOnCreate(packageName, processName, application);
            HookManager.get().checkEnv(HCallbackStub.class);
            dumpGuestActivities(packageName);
            com.hush.core.hook.LaunchGuard.ok(packageName, getUserId());
            Diag.log("bind", "handleBindApplication DONE");
        } catch (Throwable e) {
            e.printStackTrace();
            Diag.log("bind", "handleBindApplication FAILED at " + sPhase + "\n" + Diag.stack(e));
            throw new RuntimeException("Unable to makeApplication", e);
        }
    }

    private static volatile String sPhase = "start";

    /**
     * 把一段 guest 初始化代码放到主线程执行，并同步等结果。
     *
     * <p>只在当前线程没有 Looper（即 binder 线程池拉起的绑定）时才切；主线程本来就
     * 在跑绑定的话直接执行，行为与以前完全一致。</p>
     *
     * <p>超时兜底：主线程如果还没进 Looper.loop（极少见），就在本线程补一个
     * Looper 再执行。任务带 CAS 保护，主线程稍后跑到同一任务会直接跳过，
     * 不会出现两个 Application 实例。</p>
     */
    private static <T> T callOnGuestMain(String tag, java.util.concurrent.Callable<T> task) throws Exception {
        Looper main = Looper.getMainLooper();
        if (main == null || Looper.myLooper() == main) {
            return task.call();
        }
        final Object[] out = new Object[1];
        final Throwable[] err = new Throwable[1];
        final java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        boolean posted = new Handler(main).post(() -> {
            if (!ran.compareAndSet(false, true)) return;
            try {
                out[0] = task.call();
            } catch (Throwable t) {
                err[0] = t;
            } finally {
                latch.countDown();
            }
        });
        if (!posted) {
            Diag.log("bind", tag + " 主线程 post 失败，改在当前线程执行");
            return task.call();
        }
        boolean done;
        try {
            done = latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            done = false;
        }
        if (!done) {
            Diag.log("bind", tag + " 主线程 1.2s 未执行，回退当前线程");
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            if (ran.compareAndSet(false, true)) {
                try {
                    out[0] = task.call();
                } catch (Throwable t) {
                    err[0] = t;
                }
            }
        }
        if (err[0] != null) {
            if (err[0] instanceof Exception) throw (Exception) err[0];
            throw new RuntimeException(err[0]);
        }
        @SuppressWarnings("unchecked")
        T ret = (T) out[0];
        return ret;
    }

    private static void setPhase(String p) {
        sPhase = p;
    }

    public static String phase() {
        return sPhase;
    }

    /** 心跳线程：进程若在 native 层静默退出，心跳会随之中断；若是卡死，心跳继续。 */
    private static void startHeartbeat() {
        if (sHeartbeatStarted) return;
        sHeartbeatStarted = true;
        Thread t = new Thread(() -> {
            int i = 0;
            while (i++ < 40) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                Diag.log("hb", "alive phase=" + sPhase);
            }
        }, "HushCore-Heartbeat");
        t.setDaemon(true);
        t.start();
        startMainStackSampler();
    }

    /**
     * 主线程栈采样。
     *
     * 加固壳的崩溃发生在 onCreate 的 native 调用里，native 栈只能看到
     * libunisec+0x54900 这种偏移，看不出是哪一步。这里在启动窗口期内周期性
     * 抓主线程的 Java 栈落盘，崩溃前最后一条样本就是现场。
     *
     * 采样节奏分两档：平时 120ms 一次；一旦进入 Application.onCreate 就切到 8ms
     * ——壳从进 onCreate 到崩只有 19ms（日志实测），低频采样根本抓不到。
     * 25 秒后自动停。
     */
    private static void startMainStackSampler() {
        if (sSamplerStarted) return;
        sSamplerStarted = true;
        Thread t = new Thread(() -> {
            long deadline = SystemClock.uptimeMillis() + 25_000L;
            String last = null;
            long lastLog = 0;
            while (SystemClock.uptimeMillis() < deadline) {
                boolean hot = "Application.onCreate".equals(sPhase);
                String cur = mainStackDigest();
                long now = SystemClock.uptimeMillis();
                // 栈变了就记；栈没变但处于 onCreate 窗口内，也每 60ms 补一条，
                // 避免"壳卡在同一个 native 调用里"时一条都留不下。
                if (cur != null && (!cur.equals(last) || (hot && now - lastLog > 60))) {
                    Diag.log("stack", "main@[" + sPhase + "]\n" + cur);
                    last = cur;
                    lastLog = now;
                }
                try {
                    // 200ms 一次。之前 8ms 是为了抓 19ms 的崩溃窗口，代价是
                // 整个启动期主线程几乎一直被 suspend，普通 App 直接卡死。
                // 现在只在白名单壳上开，并且降到 200ms。
                Thread.sleep(hot ? 200 : 400);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "HushCore-StackSampler");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean sSamplerStarted = false;

    /** 取主线程栈，压成多行文本；帧数限制在 24 帧，够看清壳走到哪。 */
    private static String mainStackDigest() {
        try {
            Thread main = Looper.getMainLooper().getThread();
            StackTraceElement[] st = main.getStackTrace();
            if (st == null || st.length == 0) return null;
            StringBuilder sb = new StringBuilder(1024);
            int n = Math.min(st.length, 24);
            for (int i = 0; i < n; i++) {
                sb.append("  ").append(st[i].toString()).append('\n');
            }
            if (st.length > n) sb.append("  ... ").append(st.length - n).append(" more\n");
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile boolean sHeartbeatStarted = false;

    /**
     * 退出追踪。若目标进程是被 exit()（而非 _exit()/信号）终止的，
     * JVM 会执行 shutdown hook，我们就能拿到全部线程的调用栈，
     * 直接看到是哪一行代码决定退出。
     */
    private static volatile boolean sExitTracerInstalled = false;

    private static synchronized void installExitTracer() {
        if (sExitTracerInstalled) return;
        sExitTracerInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    StringBuilder sb = new StringBuilder("SHUTDOWN hook fired, phase=" + sPhase);
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                            : Thread.getAllStackTraces().entrySet()) {
                        sb.append("\n-- ").append(e.getKey().getName())
                                .append(" state=").append(e.getKey().getState());
                        for (StackTraceElement el : e.getValue()) {
                            sb.append("\n    at ").append(el);
                        }
                    }
                    Diag.log("exit", sb.toString());
                } catch (Throwable ignored) {
                }
            }, "HushCore-ExitTracer"));
        } catch (Throwable ignored) {
        }
    }
    
    private void fixAiLiaoPhoto(Application application) throws Throwable {
		if (application.getPackageName().equals("com.mosheng")) {
			ClassLoader loader = AppInstrumentation.get().getDelegateAppClassLoader();
			Class fileProviderClass = loader.loadClass("androidx.core.content.FileProvider");
			Method parsePathStrategyMethod = fileProviderClass.getDeclaredMethod("getPathStrategy", Context.class, String.class);
			parsePathStrategyMethod.setAccessible(true);
			Object pathStrategy = parsePathStrategyMethod.invoke(null, application, "com.mosheng.provider");
			Field fieldAuthority = pathStrategy.getClass().getDeclaredField("mAuthority");
			fieldAuthority.setAccessible(true);
            String newAuthority = "files." + HushCore.getHostPkg();
			fieldAuthority.set(pathStrategy, newAuthority);
		}
	}
    
    private void fixWeChatRecovery(Application app) {
        try {
            Field field = app.getClassLoader().loadClass("com.tencent.recovery.Recovery").getField("context");
            field.setAccessible(true);
            if (field.get(null) != null) {
                return;
            }
            field.set(null, app.getBaseContext());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static Context createPackageContext(ApplicationInfo info) {
        try {
            Context context = HushCore.getContext().createPackageContext(info.packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            try {
                Object loadedApk = com.hush.env.android.app.BRContextImpl.get(context).mPackageInfo();
                if (loadedApk != null) {
                    com.hush.env.android.app.BRLoadedApk.get(loadedApk)._set_mSplitNames(null);
                    com.hush.env.android.app.BRLoadedApk.get(loadedApk)._set_mSplitAppDirs(null);
                    com.hush.env.android.app.BRLoadedApk.get(loadedApk)._set_mSplitResDirs(null);
                    com.hush.env.android.app.BRLoadedApk.get(loadedApk)._set_mSplitClassLoaderNames(null);
                    // 真机上 HMS Core 和账号 kit 是同一个 APK 的 base + split，
                    // 共用一个 ClassLoader。容器里它们是两个独立安装的包，
                    // 各只有一个 dex：kit 里的 HwIDProvider 找不到 base 的
                    // com.huawei.hms.support.log.KitLog，Provider 起不来；
                    // base 里引用 kit 类的地方同理。这里把对方的 base.apk
                    // 追加进当前 ClassLoader，恢复「同一个 dex 列表」的语义。
                    boolean fresh = shareHmsClassLoader(loadedApk, info.packageName);
                    if (fresh) addSharedDex(loadedApk, info.packageName);
                }
            } catch (Throwable t) {}
            return context;
        } catch (Exception e) {
            Log.e(TAG, "error", e);
            return null;
        }
    }

    /**
     * 让所有 HMS 系包共用**同一个** ClassLoader 实例。
     *
     * <p>真机上 HMS Core 是一个 APK（base + 若干 split），所有 split 共用
     * 一个 ClassLoader，静态字段只有一份。容器里它被拆成九个独立安装的包，
     * 每个包各自 new 一个 PathClassLoader —— 即使 dex 路径已经串成一条，
     * 静态字段仍然是九份。</p>
     *
     * <p>这直接打断了 runtimekit 的 activity 代理：发起方在自己的
     * {@code RuntimeActivityThread} / kams 里注册 session，跳板页在另一份
     * 里查，查不到就 {@code finishWithTryCatch()}。2.6.24 日志里的第二次
     * {@code HMSKitStandardC2NEStubActivity1} 就是这样在 onCreate 里
     * 「自己结束自己」的（finish-in-onCreate，60ms，无异常）。</p>
     *
     * <p>共用之后 {@code RuntimeActivityThread}、{@code KitLoaderManager}、
     * 各类 Manager 单例、ModuleLoader 的 kit 缓存都回到「一份」，
     * 与真机语义一致。</p>
     *
     * @return true 表示本次新建了共享 ClassLoader（调用方还需要把其它 HMS
     *         包的 dex 补进去）；false 表示复用了已有的，dex 已经齐了。
     */
    private static volatile ClassLoader sHmsSharedLoader;

    private static boolean shareHmsClassLoader(Object loadedApk, String pkg) {
        try {
            if (pkg == null) return false;
            boolean hms = com.hush.core.HmsCore.HMS_PKG.equals(pkg)
                    || pkg.startsWith("com.huawei.hms.");
            if (!hms) return false;
            ClassLoader cl = com.hush.env.android.app.BRLoadedApk.get(loadedApk).getClassLoader();
            if (cl == null) return false;
            ClassLoader shared = sHmsSharedLoader;
            if (shared == null) {
                sHmsSharedLoader = cl;
                Diag.log("bind", "HMS 共用 ClassLoader 建立 " + pkg + " " + describeLoader(cl));
                return true;
            }
            if (shared == cl) {
                Diag.log("bind", "HMS 共用 ClassLoader 复用 " + pkg);
                return false;
            }
            // 字段名在个别 ROM 上被改过，不写死：取 LoadedApk 里唯一那个
            // ClassLoader 类型、且当前值就是我们刚建好的 loader 的字段。
            java.lang.reflect.Field f = null;
            for (java.lang.reflect.Field cand
                    : Class.forName("android.app.LoadedApk").getDeclaredFields()) {
                if (ClassLoader.class.isAssignableFrom(cand.getType())) {
                    cand.setAccessible(true);
                    if (cand.get(loadedApk) == cl) {
                        f = cand;
                        break;
                    }
                    if (f == null) f = cand;
                }
            }
            if (f == null) {
                Diag.log("bind", "HMS 共用 ClassLoader 找不到字段 " + pkg);
                return false;
            }
            f.set(loadedApk, shared);
            Diag.log("bind", "HMS 共用 ClassLoader 替换 " + pkg + " " + describeLoader(shared));
            return false;
        } catch (Throwable t) {
            Diag.log("bind", "HMS 共用 ClassLoader 失败 " + pkg + " " + t);
            return false;
        }
    }

    /**
     * HMS Core 与账号 kit 互为对方的「split」：把对方已安装的 base.apk
     * 追加到当前包已建好的 ClassLoader 里。
     *
     * <p>用 {@code BaseDexClassLoader.addDexPath} 而不是重建 ClassLoader，
     * 这样 nativeLibraryDirectories / 共享库 / 父加载器全部保持系统建好的样子。
     */
    private static void addSharedDex(Object loadedApk, String pkg) {
        java.util.List<String> others = new java.util.ArrayList<>(10);
        try {
            boolean self = com.hush.core.HmsCore.HMS_PKG.equals(pkg)
                    || (pkg != null && pkg.startsWith("com.huawei.hms."));
            if (!self) return;
            // 真机上 HMS Core、账号 kit、七个基础框架 kit 都是同一个 APK 的
            // base + split，共用一个 ClassLoader。容器里它们是一个个独立安装
            // 的包，各自只有一个 dex，跨包引用全部会 NoClassDefFoundError。
            //
            // 最容易踩的一个：登录链路的跳板页
            // com.huawei.hms.runtimekit.stub.HMSKitStandardC2NEStubActivity1
            // 类在 Framework-runtime.apk，清单却声明在 hmscore 里 —— 类加载
            // 不到时 Activity 退化成空壳，onCreate 立刻 finish，用户看到的就是
            // 「账号页点开一秒退回桌面」，而且一条异常都没有。
            //
            // 所以这里不再只补「本体 ↔ 账号 kit」，而是把所有 HMS 系包的
            // base.apk 全部串成一个 dex 列表，跟真机语义对齐。
            for (String p : com.hush.core.HmsCore.allHmsPackages()) {
                if (!p.equals(pkg)) others.add(p);
            }
        } catch (Throwable ignored) {
        }
        for (String other : others) {
            addDexPath(loadedApk, pkg, other);
        }
    }

    /** 把 {@code other} 这个包的 base.apk 追加进当前进程的 ClassLoader。 */
    private static void addDexPath(Object loadedApk, String pkg, String other) {
        String path = null;
        try {
            PackageInfo pi = HushCore.getBPackageManager()
                    .getPackageInfo(other, 0, getUserId());
            if (pi != null && pi.applicationInfo != null) {
                path = pi.applicationInfo.sourceDir;
            }
        } catch (Throwable t) {
            Diag.log("bind", "共享 dex 取路径失败 " + other + " " + t);
        }
        if (path == null) {
            Diag.log("bind", "共享 dex 缺失 " + other);
            return;
        }
        try {
            // createPackageContext 发生在 bind 的 hiddenApiExempt 之前，
            // 这里补一次；addDexPath 是 @UnsupportedAppUsage 成员。
            com.hush.reflection.akshit.BootstrapClass.exemptAll();
            ClassLoader cl = com.hush.env.android.app.BRLoadedApk.get(loadedApk).getClassLoader();
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            java.lang.reflect.Method add = null;
            try {
                add = base.getDeclaredMethod("addDexPath", String.class);
            } catch (NoSuchMethodException e) {
                add = base.getDeclaredMethod("addDexPath", String.class, boolean.class);
            }
            add.setAccessible(true);
            if (add.getParameterCount() == 2) {
                add.invoke(cl, path, false);
            } else {
                add.invoke(cl, path);
            }
            Diag.log("bind", "共享 dex " + pkg + " += " + path);
        } catch (Throwable t) {
            Diag.log("bind", "共享 dex 失败 " + pkg + " " + t);
        }
    }
    
    public Object getPackageInfo() {
        return this.mBoundApplication.info;
    }
    
    /**
     * 把 ClassLoader 的 dex 组成打出来。
     * "Class not found using the boot class loader" 这类报错，光看异常分不清是
     * 「loader 给错了」还是「dex 里真没有这个类」，所以直接把 dexElements 列出来。
     */
    public static String describeLoader(ClassLoader cl) {
        if (cl == null) return "null";
        StringBuilder sb = new StringBuilder(cl.getClass().getSimpleName());
        try {
            java.lang.reflect.Field f = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
            f.setAccessible(true);
            Object pathList = f.get(cl);
            if (pathList == null) return sb.append(" pathList=null").toString();
            java.lang.reflect.Field de = pathList.getClass().getDeclaredField("dexElements");
            de.setAccessible(true);
            Object[] els = (Object[]) de.get(pathList);
            int n = els == null ? 0 : els.length;
            sb.append(" dex[").append(n).append("]");
            for (int i = 0; i < Math.min(3, n); i++) {
                try {
                    java.lang.reflect.Field df = els[i].getClass().getDeclaredField("dexFile");
                    df.setAccessible(true);
                    Object dexFile = df.get(els[i]);
                    if (dexFile != null) {
                        java.lang.reflect.Method m = dexFile.getClass().getDeclaredMethod("getName");
                        m.setAccessible(true);
                        sb.append(" ").append(m.invoke(dexFile));
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            sb.append(" (").append(t.getClass().getSimpleName()).append(")");
        }
        // native 搜索路径：加固壳的 loadLibrary 全靠它。找不到 libunisec.so
        // 就意味着壳解不出 dex，后面所有业务类都会 ClassNotFound。
        try {
            java.lang.reflect.Field f = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
            f.setAccessible(true);
            Object pathList = f.get(cl);
            if (pathList != null) {
                java.lang.reflect.Field nd = pathList.getClass().getDeclaredField("nativeLibraryDirectories");
                nd.setAccessible(true);
                Object dirs = nd.get(pathList);
                sb.append(" native=").append(dirs);
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /**
     * 安装 provider。
     *
     * 真实 Android 里 handleBindApplication 永远在主线程执行，provider 的
     * onCreate 天然有一个 Looper。容器的子进程不是这样：父进程通过
     * IBActivityThread.acquireContentProviderClient 让子进程「顺手」绑一次，
     * 这次调用是从 Binder 线程进来的，于是 provider 里的 new Handler() 直接抛
     *
     *   Can't create handler inside thread Thread[binder:2844_1] that has not
     *   called Looper.prepare()
     *
     * 高德 :locationservice 的 androidx.lifecycle.ProcessLifecycleOwnerInitializer
     * 就是这样挂掉的（2026-11-01 13:16:11.096 / 14:48:20.556 两次日志都有完整栈）。
     * provider 装不上，ProcessLifecycleOwner 就永远没初始化，App 一碰 lifecycle 就崩。
     *
     * 两个凑合方案都被日志否掉了：给 Binder 线程 Looper.prepare() 只是让构造不抛异常，
     * 线程不 loop，post 全丢；在主线程 post 后等 5 秒，主线程正卡在启动期的同步 IPC 上，
     * 等到超时也等不到，最后还是退回 Binder 线程。
     * 正确做法只有一种：投递给主线程，但不在 bind 里等它（见下面的实现）。
     */
    private void installProviders(final Context context, final String processName,
                                  final List<ProviderInfo> provider, final boolean shellGuest) {
        if (provider == null || provider.isEmpty()) {
            Diag.log("bind", "providers installed=0/0");
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            installProvidersHere(context, processName, provider);
            return;
        }

        // 走到这里 = 本线程不是主线程（子进程的 bind 是从 Binder 线程进来的）。
        // 2026-11-01 14:48:20.556 的日志证明，此刻主线程根本没法执行任何消息：
        //
        //   bind: providers moved to main from binder:31129_2 posted=true done=false   ← 等了 5 秒
        //   bind: main looper busy, install providers on binder:31129_2
        //   IllegalStateException: Method handleLifecycleEvent must be called on the main thread
        //
        // 原因：子进程的主线程在启动期正卡在 attach 的同步 IPC 里，那一步要等 bind 返回
        // 才解除 —— 也就是说，我们在 bind 里等主线程，主线程在等 bind 结束，互等到超时。
        // 而 Looper.prepare() 在原地装只是换了个报错：LifecycleRegistry 认的是「主线程」，
        // 一个临时 Looper 骗不过它，于是 provider 还是装不上，ProcessLifecycleOwner 永远不初始化。
        //
        // 唯一成立的做法是【只投递、不等待】：bind 立刻返回 -> 主线程解除阻塞 -> 开始 loop ->
        // 第一件事就是执行这个 Runnable，provider 最终仍然是在主线程创建的。
        final String tname = Thread.currentThread().getName();
        boolean posted = false;
        try {
            posted = new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Diag.log("bind", "providers install on main (deferred from " + tname + ")");
                    try {
                        installProvidersHere(context, processName, provider);
                    } catch (Throwable t) {
                        Diag.log("bind", "installProviders@main FAILED\n" + Diag.stack(t));
                    }
                }
            });
        } catch (Throwable t) {
            Diag.log("bind", "post providers to main FAILED " + t);
        }
        Diag.log("bind", "providers deferred to main from " + tname + " posted=" + posted);
        if (!posted) {
            installProvidersHere(context, processName, provider);
        }
    }

    private void installProvidersHere(Context context, String processName, List<ProviderInfo> provider) {
        long origId = Binder.clearCallingIdentity();
        int installed = 0;
        ClassLoader appCl = null;
        try {
            appCl = context.getClassLoader();
        } catch (Throwable ignored) {
        }
        Diag.log("bind", "prov cl=" + describeLoader(appCl));

        try {
            for (ProviderInfo providerInfo : provider) {
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        setPhase("provider " + providerInfo.name);
                        installProvider(HushCore.mainThread(), context, providerInfo, null);
                        installed++;
                    }
                } catch (Throwable t) {
                    // 类找不到时，用 guest loader 直接再试一次：
                    // 能加载 = 系统内部拿到的 loader 不对；加载不了 = dex 里确实没这个类。
                    String probe;
                    try {
                        Class<?> c = (appCl == null) ? null : appCl.loadClass(providerInfo.name);
                        probe = (c == null) ? "no-loader" : "found";
                    } catch (Throwable e2) {
                        probe = "miss:" + e2.getClass().getSimpleName();
                    }
                    Diag.log("bind", "provider FAILED " + providerInfo.name
                            + " probe=" + probe + "\n" + Diag.stack(t));
                }
            }
            Diag.log("bind", "providers installed=" + installed + "/" + provider.size());
        } finally {
            Binder.restoreCallingIdentity(origId);
            // Clear the cached Settings/Secure/Global ContentProvider references SYNCHRONOUSLY
            // so that any Settings.Secure.getString() call in Application.onCreate() (e.g.
            // Finsky's EventLoggerFactory reading "android_id") goes through our SystemProviderStub
            // rather than the old host-binder that was cached before our hooks were installed.
            try {
                ContentProviderDelegate.clearSettingProvider();
            } catch (Throwable t) {
                android.util.Log.e(TAG, "clearSettingProvider() failed", t);
            }
            // Run the rest of ContentProviderDelegate.init() (heavy synchronous IPC to settings
            // provider) on a background thread to avoid blocking the main thread during app bind.
            new Thread("HushCore-ProviderInit") {
                @Override
                public void run() {
                    try {
                        ContentProviderDelegate.init();
                    } catch (Throwable t) {
                        android.util.Log.e(TAG, "ContentProviderDelegate.init() failed", t);
                    }
                }
            }.start();
        }
    }


    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = Reflector.findMethodByFirstName(mainThread.getClass(), "installProvider");
        if (installProvider != null) {
            installProvider.setAccessible(true);
            installProvider.invoke(mainThread, context, holder, providerInfo, false, true, true);
        }
    }
    
    public void loadXposed(Context context) {
        // 真正的模块加载在 XposedModuleLoader 里：读启用状态、建 PathClassLoader、
        // 回调 handleLoadPackage，模块的 findAndHookMethod 最终落到 HushHook。
        try {
            com.hush.core.xposed.XposedModuleLoader.load(context);
        } catch (Throwable t) {
            Diag.log("xp", "loadXposed 失败\n" + Diag.stack(t));
        }
        if (SdkBridge.sHideXposed) { VNative.hideXposed(); }
    }

    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(HushCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) bindApplication(getAppPackageName(), getAppProcessName());
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void restartJobService(String selfId) {}

    private IBinder getLocalProviderBinder(String targetAuth) {
        try {
            Object activityThread = HushCore.mainThread();
            java.util.Map<?, ?> map = com.hush.env.android.app.BRActivityThread.get(activityThread).mProviderMap();
            for (Object value : map.values()) {
                String[] mNames = com.hush.env.android.app.BRActivityThreadProviderClientRecordP.get(value).mNames();
                if (mNames != null) {
                    for (String name : mNames) {
                        if (name.equals(targetAuth)) {
                            android.os.IInterface provider = com.hush.env.android.app.BRActivityThreadProviderClientRecordP.get(value).mProvider();
                            if (provider != null) return provider.asBinder();
                        }
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) {
        if (!isInit()) bindApplication(getAppConfig().packageName, getAppConfig().processName);
        for (String auth : providerInfo.authority.split(";")) {
            IBinder binder = getLocalProviderBinder(auth);
            if (binder != null) return binder;
        }
        try {
            Context context = null;
            if (providerInfo.packageName.equals(getAppPackageName()) && mInitialApplication != null) {
                context = mInitialApplication;
            } else {
                context = createPackageContext(providerInfo.applicationInfo);
                try {
                    Object loadedApk = BRContextImpl.get(context).mPackageInfo();
                    BRLoadedApk.getWithException(loadedApk).makeApplication(false, null);
                } catch (Throwable ignored) {}
            }
            installProvider(HushCore.mainThread(), context, providerInfo, null);
            for (String auth : providerInfo.authority.split(";")) {
                IBinder binder = getLocalProviderBinder(auth);
                if (binder != null) return binder;
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to install provider " + providerInfo.authority, e);
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(HushCore.mainThread()).mActivities();
            Object clientRecord = activities.get(token);
            if (clientRecord == null) return;
            Activity activity = getActivityByToken(token);
            while (activity.getParent() != null) activity = activity.getParent();
            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        mH.post(() -> {
            Intent newIntent = BuildCompat.isLollipop_MR1() ? BRReferrerIntent.get()._new(intent, HushCore.getHostPkg()) : intent;
            Object mainThread = HushCore.mainThread();
            if (BRActivityThread.get(mainThread)._check_performNewIntents(null, null) != null) {
                BRActivityThread.get(mainThread).performNewIntents(token, Collections.singletonList(newIntent));
            } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                BRActivityThreadNMR1.get(mainThread).performNewIntents(token, Collections.singletonList(newIntent), true);
            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            }
        });
    }

    @Override
    public void scheduleReceiver(final ReceiverData data) {
        if (!isInit()) bindApplication();
        mH.post(() -> {
            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader cl = baseContext.getClassLoader();
                data.intent.setExtrasClassLoader(cl);
                BroadcastReceiver receiver = (BroadcastReceiver) cl.loadClass(data.activityInfo.name).newInstance();
                BRBroadcastReceiver.get(receiver).setPendingResult(data.data.build());
                receiver.onReceive(baseContext, data.intent);
                BroadcastReceiver.PendingResult finish = BRBroadcastReceiver.get(receiver).getPendingResult();
                if (finish != null) finish.finish();
                HushCore.getBActivityManager().finishBroadcast(data.data);
            } catch (Throwable e) {
                Log.e(TAG, "error", e);
                Slog.e(TAG, "Error receiving broadcast " + data.intent);
            }
        });
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> map = BRActivityThread.get(HushCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(map.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback cb : HushCore.get().getAppLifecycleCallbacks()) {
            cb.beforeCreateApplication(packageName, processName, context, getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application app) {
        for (AppLifecycleCallback cb : HushCore.get().getAppLifecycleCallbacks()) {
            cb.beforeApplicationOnCreate(packageName, processName, app, getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application app) {
        for (AppLifecycleCallback cb : HushCore.get().getAppLifecycleCallbacks()) {
            cb.afterApplicationOnCreate(packageName, processName, app, getUserId());
        }
    }

    /**
     * 把 guest manifest 里声明的所有 Activity 打出来。
     *
     * 现在的僵局是：入口 {@code ProtocolLauncher} 的 View 树全空（三个 FrameLayout
     * 都是 0x0/GONE，没有任何 WebView/SurfaceView），它既不画东西也不跳转。
     * 要绕开它，必须先知道这个包里「除了它还有哪些 Activity」，以及哪个才是
     * 真正的游戏入口（Unity 的 UnityPlayerActivity）。
     *
     * 这些信息只能从 guest 的 PackageInfo 里读，靠猜类名没有意义。
     */
    private static void dumpGuestActivities(String pkg) {
        try {
            android.content.pm.PackageInfo pi = HushCore.getBPackageManager()
                    .getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES,
                            BActivityThread.getUserId());
            if (pi == null || pi.activities == null) {
                Diag.log("acts", "none");
                return;
            }
            Diag.log("acts", "count=" + pi.activities.length);
            int n = 0;
            for (android.content.pm.ActivityInfo ai : pi.activities) {
                if (ai == null) continue;
                if (++n > 40) {
                    Diag.log("acts", "... truncated");
                    break;
                }
                StringBuilder sb = new StringBuilder(160);
                sb.append(n).append(" name=").append(ai.name)
                        .append(" exp=").append(ai.exported ? 1 : 0)
                        .append(" theme=0x").append(Integer.toHexString(ai.theme))
                        .append(" launchMode=").append(ai.launchMode)
                        .append(" so=").append(ai.screenOrientation)
                        .append(" cc=0x").append(Integer.toHexString(ai.configChanges))
                        .append(" proc=").append(ai.processName);
                Diag.log("acts", sb.toString());
            }
        } catch (Throwable t) {
            Diag.log("acts", "failed " + t);
        }
    }

    /**
     * 安装时 {@code copyNativeLib} 是「遍历 APK 里的 .so 逐个拷贝」，只要有一步
     * 抛异常就整体返回 -1，而安装流程并不会因此回滚 —— 结果就是库目录里静静少了
     * 几个 .so。之后 {@code System.loadLibrary} 只会抛一句 UnsatisfiedLinkError，
     * 既不说缺哪个，也不说为什么。
     *
     * 光遇这个包 arm64 下是 61 个 so（libBootloader.so 单个 67MB，Unity 和游戏
     * 逻辑全在里面），少一个都可能让引擎起不来。这里在 bind 时对一遍清单：
     * 缺的、或者大小不对的，直接从 APK 重新解出来。
     */
    private static void ensureGuestLibs(String apkPath, File libDir) {
        java.util.zip.ZipFile zf = null;
        try {
            if (!libDir.exists()) libDir.mkdirs();
            zf = new java.util.zip.ZipFile(apkPath);

            int total = 0, miss = 0, fixed = 0;
            byte[] buf = new byte[64 * 1024];
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                java.util.zip.ZipEntry entry = e.nextElement();
                String n = entry.getName();
                if (!n.startsWith("lib/") || !n.endsWith(".so")) continue;
                // 只处理当前 ABI，别的架构解出来也没用，白占空间。
                if (!n.startsWith("lib/" + Build.SUPPORTED_ABIS[0] + "/")) continue;
                total++;

                String name = n.substring(n.lastIndexOf('/') + 1);
                File dst = new File(libDir, name);
                if (dst.exists() && dst.length() == entry.getSize()) continue;

                miss++;
                try {
                    java.io.InputStream in = zf.getInputStream(entry);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                    try {
                        int len;
                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                        out.flush();
                    } finally {
                        try { out.close(); } catch (Throwable ignored) {}
                        try { in.close(); } catch (Throwable ignored) {}
                    }
                    dst.setReadable(true, false);
                    dst.setExecutable(true, false);
                    fixed++;
                    Diag.log("bind", "LIB REPAIR " + name + " size=" + dst.length());
                } catch (Throwable t) {
                    Diag.log("bind", "LIB REPAIR FAILED " + name + " " + t);
                }
            }
            Diag.log("bind", "guest libs apk=" + total + " broken=" + miss + " repaired=" + fixed);
        } catch (Throwable t) {
            Diag.log("bind", "lib check failed " + t);
        } finally {
            try { if (zf != null) zf.close(); } catch (Throwable ignored) {}
        }
    }
}
