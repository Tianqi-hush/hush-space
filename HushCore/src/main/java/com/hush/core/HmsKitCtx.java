package com.hush.core;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.utils.Diag;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 给 HMS 的 kit 进程补「全局 Context」。
 *
 * <p>kit 包（{@code com.huawei.hms.account} 等）在真机上是 split，跑在
 * {@code com.huawei.hwid} 进程里，共用 {@code CoreApplication} 铺好的全局单例。
 * 容器里它们被当成独立包装，Application 又被降级成 {@code android.app.Application}
 * （见 {@code PackageManagerCompat#generateApplicationInfo}），
 * {@code CoreApplication.attachBaseContext} 从来没跑过，于是：</p>
 *
 * <pre>
 *   CoreApplication.e = null
 *     → gm.&lt;init&gt;() 里 this.a = CoreApplication.getCoreBaseContext() = null
 *     → new PackageManagerHelper(null) → ctx.getPackageManager() NPE
 *     → TransInfo.&lt;init&gt; 在 Activity 字段初始化时炸
 *     → Unable to instantiate activity StartUpGuideLoginActivity
 *     → 进程 FATAL，界面退回框架
 * </pre>
 *
 * <p>2.6.31 试过直接把 kit 的 Application 换成 CoreApplication，结果把
 * Tracker（{@code W.b.e}）和 multidex 一起拉进来，崩得更狠。这里换一条路：
 * Application 仍然是轻量的，只把 kit 真正需要的那个静态字段补上。</p>
 *
 * <p>{@code CoreApplication.k(Context)} 就是 {@code e = ctx} 的 setter
 * （反编译 hmscore.apk 确认：{@code static void k(Context c){ e = c; }}）。</p>
 */
public final class HmsKitCtx {

    private static final String CORE_APP = "com.huawei.hms.app.CoreApplication";

    /** 每个进程只做一次。 */
    private static boolean sDone;

    /**
     * makeApplication 之前调用：把 CoreApplication 的全局 Context 先铺好。
     *
     * <p>{@code Application.attachBaseContext} 里就会执行 core 的初始化，
     * 那时如果 {@code e/f} 还是 null，里面创建的 Manager / NetUtils / Tracker
     * 会把 null 或错误的 ConnectivityManager 缓存成静态字段，之后再补也救不回来。
     * 这里用 packageContext 先把坑填上，Application 创建完再由 {@link #install}
     * 换成真正的 Application。</p>
     */
    public static void preInstall(Context base, String pkg) {
        if (base == null || !isHms(pkg)) return;
        try {
            ClassLoader cl = base.getClassLoader();
            if (cl == null) return;
            // packageContext 的 ContextImpl 还没被 ContextCompat.fix 过，
            // opPackageName 仍是虚拟包名；CoreApplication 在 attachBaseContext
            // 里创建的 Manager 会把它缓存下来。先按宿主身份修一遍。
            com.hush.utils.compat.ContextCompat.fix(base);
            Class<?> core = Class.forName(CORE_APP, false, cl);
            writeCoreContext(core, base, pkg + "(pre)");
        } catch (Throwable t) {
            Diag.log("kitctx", pkg + " pre 跳过 " + t);
        }
    }

    public static void install(Context app, String pkg) {
        install(app, pkg, null);
    }

    public static void install(Context app, String pkg, String proc) {
        if (sDone || app == null || pkg == null) return;
        if (!isHms(pkg)) return;
        ClassLoader cl = app.getClassLoader();
        if (cl == null) return;

        Class<?> core;
        try {
            core = Class.forName(CORE_APP, true, cl);
        } catch (Throwable t) {
            Diag.log("kitctx", pkg + " 无 " + CORE_APP + " " + t);
            return;
        }

        // ① CoreApplication.e —— getCoreBaseContext() 直接返回它
        boolean ok = writeCoreContext(core, app, pkg);

        Object got = null;
        try {
            Method g = core.getDeclaredMethod("getCoreBaseContext");
            g.setAccessible(true);
            got = g.invoke(null);
        } catch (Throwable ignored) {
        }
        Diag.log("kitctx", pkg + " CoreApplication.e="
                + (got == null ? "null" : got.getClass().getName()) + " set=" + ok);

        // ② 单例可能已经被创建过（构造时拿到的是 null），补一次。
        //    2.6.42：不再只修 account kit —— hwid 进程里的 okhttp 工厂
        //    （NetworkClient -> gm.b().a()）同样会拿到 null context，那样
        //    SecureX509TrustManager 建不起来，HMS 就退回系统信任链，
        //    grs.dbankcloud.com（华为 CBG Root CA）直接握手失败。
        fixGm(cl, app);

        // ③ 网络：HMS 用 guest 的 Context 查 connectivity，容器里这份可能
        //    查不到网（页面上就是「无网络」）。换成宿主真实的 ConnectivityManager。
        fixConnectivity(app, pkg);

        // ④ 真机上 kit 是同一个 APK 的 split，所有 kit 共用一份 AssetManager，
        //    H5 协议页 / 离线资源都是按 assets 路径直接读的。容器里每个 kit 是
        //    独立安装的包，只串了 dex，assets 仍然是各看各的 —— 在 hwid 进程里
        //    打开 account kit 的协议页时，file:///android_asset/... 直接 404，
        //    页面只能显示自己的「无网络」兜底页。这里把其它 HMS 包的 APK 路径
        //    追加进当前进程的 AssetManager，恢复「一份 assets」的语义。
        //    只在会跑页面的进程做：core / persistent 这种纯后台进程加了也用不上，
        //    反而拖慢启动。
        if (wantsUi(proc, pkg)) {
            shareAssets(app, pkg);
        }

        // ④b 2.6.42：把 HMS 自带的 Huawei CBG Root CA 追加进这个进程的
        //     默认信任链。系统 CA store 里没有这张华为自签根证书（非华为
        //     机型都没有），所以凡是走 HttpsURLConnection 的地方（GRS
        //     站点解析、运营商 SDK 回传）都会报
        //     Trust anchor for certification path not found。
        HmsTls.install(app);

        // ⑤ WebView 的 profile/cache 目录在 guest 的 credential dataDir 下。
        //    VCore 只给 device-protected 目录建过 app_webview，这里补上并确认可写；
        //    目录不存在时 Chromium 会直接判「网络不可用」。
        prepareWebViewDir(app, pkg);

        sDone = true;

        // ⑥ 只保留 HTTP 取证。不再在 HMS 进程里抢先 new WebView：那个探针会
        //    提前触发 Chromium 初始化，和 HMS 自己的 WebView 抢数据目录 / 渲染
        //    进程，反而把要观察的页面弄坏。
        if (wantsNetDiag(proc)) {
            Diag.log("netprobe", "start where=" + proc + " pkg=" + pkg);
            com.hush.utils.NetProbe.probe(app, "guest:" + proc);
            askHmsNetUtils(app, pkg, cl);
            // HMS 自己会往 logcat 写它为什么判定没网（chromium 的 net::ERR_* 也在里面）
            try {
                com.hush.core.hook.GuestLog.start();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isHms(String pkg) {
        return pkg != null
                && (com.hush.core.HmsCore.HMS_PKG.equals(pkg) || pkg.startsWith("com.huawei.hms."));
    }

    /** 写 CoreApplication 的 e / f 两个静态 Context，返回 e 是否写成功。 */
    private static boolean writeCoreContext(Class<?> core, Context ctx, String tag) {
        boolean ok = false;
        try {
            Method k = core.getDeclaredMethod("k", Context.class);
            k.setAccessible(true);
            k.invoke(null, ctx);
            ok = true;
        } catch (Throwable t) {
            Diag.log("kitctx", tag + " k() 失败 " + t);
        }
        if (!ok) {
            // 混淆名变了就退回直接写字段
            try {
                Field e = core.getDeclaredField("e");
                e.setAccessible(true);
                e.set(null, ctx);
                ok = true;
            } catch (Throwable t) {
                Diag.log("kitctx", tag + " 字段 e 失败 " + t);
            }
        }
        // f —— attachBaseContext 里那个包装 context
        try {
            Field f = core.getDeclaredField("f");
            f.setAccessible(true);
            if (f.get(null) == null) f.set(null, ctx);
        } catch (Throwable ignored) {
        }
        return ok;
    }

    /**
     * 把其它 HMS 包的 APK 追加进当前进程的 AssetManager。
     *
     * <p>真机上 HMS Core 的所有 kit 都是同一个 base + split，资源表也只合并了
     * 一次，所以 kit 页面里的 {@code assets/} 对 hwid 进程天然可见。容器里
     * 每个 kit 是独立安装的包，hwid 的 AssetManager 只有自己那份 base.apk；
     * account kit 里的协议页 HTML、图片、配置全都读不到。</p>
     *
     * <p>只追加到**后面**：已有的资源 ID 仍然由 hwid 本体优先解析，不会因为
     * 后加入的 kit 有同名 ID 而改变现有页面。</p>
     */
    private static void shareAssets(Context app, String pkg) {
        try {
            android.content.res.AssetManager am = app.getResources().getAssets();
            for (String other : com.hush.core.HmsCore.allHmsPackages()) {
                if (other.equals(pkg)) continue;
                String path = sourceDirOf(other);
                if (path == null) continue;
                try {
                    Integer cookie = com.hush.env.android.content.res.BRAssetManager.get(am)
                            .addAssetPath(path);
                    Diag.log("kitctx", "共享 assets " + pkg + " += " + other
                            + " cookie=" + cookie + " " + path);
                } catch (Throwable t) {
                    Diag.log("kitctx", "共享 assets 失败 " + other + " " + t);
                }
            }
        } catch (Throwable t) {
            Diag.log("kitctx", pkg + " 共享 assets 跳过 " + t);
        }
    }

    private static String sourceDirOf(String pkg) {
        try {
            PackageInfo pi = HushCore.getBPackageManager()
                    .getPackageInfo(pkg, 0, BActivityThread.getUserId());
            if (pi == null || pi.applicationInfo == null) return null;
            String path = pi.applicationInfo.sourceDir;
            return path != null && new File(path).isFile() ? path : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** WebView 的数据目录必须真实存在且可写，否则 Chromium 会判网络不可用。 */
    private static void prepareWebViewDir(Context app, String pkg) {
        try {
            File dir = new File(app.getDataDir(), "app_webview");
            boolean ok = dir.isDirectory() || dir.mkdirs();
            Diag.log("kitctx", pkg + " webview dir=" + dir
                    + " ok=" + ok + " write=" + dir.canWrite());
        } catch (Throwable t) {
            Diag.log("kitctx", pkg + " webview dir 失败 " + t);
        }
    }

    /**
     * 只在「跑 kit 页面的容器进程」和「account kit 进程」里做联网取证，
     * 其余 guest 进程（persistent / core / 其它）一律跳过，否则一次启动会
     * 起几十个探测线程。
     */
    private static boolean wantsNetDiag(String proc) {
        if (proc == null) return false;
        return proc.contains(".container") || proc.startsWith("com.huawei.hms.account");
    }

    /** 这个进程会不会真的跑 HMS 的页面（需要 assets / WebView 目录）。 */
    private static boolean wantsUi(String proc, String pkg) {
        if (proc == null) return true;
        return proc.contains(".container")
                || proc.startsWith("com.huawei.hms.account")
                || com.hush.core.HmsCore.HMS_PKG.equals(proc)
                || proc.equals(pkg);
    }

    /**
     * 直接问 account kit 自己的判断函数，不再靠类名猜。
     *
     * <p>反编译 {@code AccountKit-base.apk} 得到的是这个类（所有 kit 共用）：</p>
     * <pre>
     *   Lcom/huawei/hwid/common/util/NetUtils;
     *     static int getNetworkState(Context)              0 = 无网
     *     static NetworkInfo getActiveNetWorkInfo(Context)
     *     static String getNetType(Context)                "NONE" = 无网
     * </pre>
     * <p>它内部就是 {@code ctx.getSystemService("connectivity")
     * .getActiveNetworkInfo()}，和 {@code ConnectivityManager} 给的答案应当一致。
     * 三个方法一起打，只要有一个说「无网」，就是它导致页面写「无网络」。</p>
     */
    private static void askHmsNetUtils(Context app, String pkg, ClassLoader cl) {
        try {
            Class<?> nu = Class.forName("com.huawei.hwid.common.util.NetUtils", false, cl);
            String state;
            try {
                Method m = nu.getDeclaredMethod("getNetworkState", Context.class);
                m.setAccessible(true);
                state = String.valueOf(m.invoke(null, app));
            } catch (Throwable t) {
                state = "ERR " + t.getClass().getSimpleName();
            }
            String info;
            try {
                Method m = nu.getDeclaredMethod("getActiveNetWorkInfo", Context.class);
                m.setAccessible(true);
                Object ni = m.invoke(null, app);
                info = ni == null ? "null" : (String) ni.getClass()
                        .getMethod("getTypeName").invoke(ni) + "/"
                        + ni.getClass().getMethod("isConnected").invoke(ni);
            } catch (Throwable t) {
                info = "ERR " + t.getClass().getSimpleName();
            }
            String type;
            try {
                Method m = nu.getDeclaredMethod("getNetType", Context.class);
                m.setAccessible(true);
                type = String.valueOf(m.invoke(null, app));
            } catch (Throwable t) {
                type = "ERR " + t.getClass().getSimpleName();
            }
            com.hush.utils.Diag.log("netprobe", pkg + " NetUtils.getNetworkState=" + state
                    + " getActiveNetWorkInfo=" + info + " getNetType=" + type
                    + "（0 / null / NONE 就是页面说的「无网络」）");
        } catch (Throwable t) {
            com.hush.utils.Diag.log("netprobe", pkg + " 无 NetUtils " + t);
        }
    }

    /**
     * 把 guest Application 里缓存的 {@link android.net.ConnectivityManager}
     * 换成宿主的那一份。
     *
     * <p>HMS 判断网络（反编译 hmscore {@code z0.m.a(Context)}）：</p>
     * <pre>
     *   ctx.checkPermission(ACCESS_NETWORK_STATE, pid, uid)
     *   → ctx.getSystemService("connectivity").getActiveNetwork()
     *   → getNetworkCapabilities(net).hasTransport(CELLULAR/WIFI/…) || hasCapability(VALIDATED)
     *   → 兜底 getActiveNetworkInfo().isConnected()
     * 任一步拿不到就返回 false，页面显示「无网络」。
     * </pre>
     *
     * <p>guest 的 ContextImpl 是拿宿主 ContextImpl 复制出来的，系统服务缓存里
     * 的 ConnectivityManager 绑的却是 guest 包名。这里让它直接复用宿主实例：
     * uid / 包名 / 权限都是宿主自己的，查询结果必然和系统设置里一致。</p>
     */
    private static void fixConnectivity(Context app, String pkg) {
        try {
            Context host = com.hush.HushCore.getContext();
            if (host == null) {
                com.hush.utils.Diag.log("netfix", pkg + " 宿主 context 为空");
                return;
            }
            Object hostCm = host.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (hostCm == null) {
                com.hush.utils.Diag.log("netfix", pkg + " 宿主 connectivity 为空");
                return;
            }
            // 先让 guest 自己建一份，缓存槽才会存在
            Object guestCm = app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Context base = app instanceof android.content.ContextWrapper
                    ? ((android.content.ContextWrapper) app).getBaseContext() : null;
            if (base == null) return;
            Field cacheField = null;
            for (Class<?> c = base.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    cacheField = c.getDeclaredField("mServiceCache");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (cacheField == null) {
                com.hush.utils.Diag.log("netfix", pkg + " 找不到 mServiceCache");
                return;
            }
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(base);
            if (!(cacheObj instanceof Object[])) {
                com.hush.utils.Diag.log("netfix", pkg + " mServiceCache 不是数组");
                return;
            }
            Object[] cache = (Object[]) cacheObj;
            int n = 0;
            for (int i = 0; i < cache.length; i++) {
                if (cache[i] instanceof android.net.ConnectivityManager) {
                    cache[i] = hostCm;
                    n++;
                }
            }
            com.hush.utils.Diag.log("netfix", pkg + " guestCm="
                    + (guestCm == null ? "null" : "ok") + " 替换=" + n
                    + " cacheLen=" + cache.length);
            // 换了缓存还不够：HMS 常把 context 存成字段，之后自己 new 出来查。
            // 直接把 guestCm 内部的 mContext 换成宿主 context，双保险。
            if (guestCm != null) {
                try {
                    Field mctx = guestCm.getClass().getDeclaredField("mContext");
                    mctx.setAccessible(true);
                    mctx.set(guestCm, host);
                    com.hush.utils.Diag.log("netfix", pkg + " guestCm.mContext -> host");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            com.hush.utils.Diag.log("netfix", pkg + " 失败 " + t);
        }
    }

    /**
     * {@code gm} 是 account kit 里的 Context 持有者：
     * <pre>
     *   class gm { private Context a;
     *              private gm(){ a = CoreApplication.getCoreBaseContext(); }
     *              public Context a(){ return a; } }
     * </pre>
     * 若它在 {@link #install} 之前就被创建，光改静态字段救不回来，得改实例。
     */
    private static void fixGm(ClassLoader cl, Context app) {
        int fixed = 0;
        try {
            Class<?> holder = Class.forName("gm$b", true, cl);
            for (Field f : holder.getDeclaredFields()) {
                if (!"gm".equals(f.getType().getName())) continue;
                f.setAccessible(true);
                Object inst = f.get(null);
                if (inst == null) continue;
                try {
                    Field a = inst.getClass().getDeclaredField("a");
                    a.setAccessible(true);
                    if (a.get(inst) == null) {
                        a.set(inst, app);
                        fixed++;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            com.hush.utils.Diag.log("kitctx", "gm 修复跳过 " + t);
        }
        if (fixed > 0) {
            com.hush.utils.Diag.log("kitctx", "gm 实例补 context x" + fixed);
        }
    }

    private HmsKitCtx() {
    }
}
