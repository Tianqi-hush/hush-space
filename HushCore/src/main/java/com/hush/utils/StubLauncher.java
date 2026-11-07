package com.hush.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * runtimekit 跳板页的「直连」通道。
 *
 * <p>HMS 的动态 kit 机制是这样跑的：宿主（com.huawei.hwid）在清单里预先声明一堆
 * 1×1 的透明跳板页（{@code com.huawei.hms.runtimekit.stub.*}，都继承
 * {@code ContainerActivity}）。要打开 kit 里的页面时，kams 先登记一条 session
 * （intent 的 action 就是 session id），再拉起跳板页；跳板页用
 * {@code loadKitInfoSuccess(intent)} 把 session 换回来，结果**写进
 * {@code mStubLoadInfo} 字段**，然后 {@code loadKitForActivity()} 里再做
 * {@code KitLoaderManager.loadKit()} + {@code inject()}，最后让 delegate 启动真正的页面。</p>
 *
 * <p>这套机制在真机上是自洽的（一个 APK、一个 ClassLoader、kams 常驻）。容器里
 * 拆成 9 个包之后，{@code KitLoaderManager} 的单例、delegate 工厂、kit 的 dex
 * 缓存都可能落在别的进程，跳板页就会「create 完几十毫秒自己 finish」。</p>
 *
 * <p>既然每个 kit 在容器里本身就是**独立安装的包**，它的 Activity 在清单里是实打实
 * 声明过的，根本不需要动态注入 ClassLoader。所以这里直接拿 session 里的
 * {@code kit_component_name}，用最普通的 startActivity 把目标页拉起来，跳板页
 * 立刻 finish。整条链路上不再依赖 kams 的 delegate / inject。</p>
 *
 * <p><b>2.6.27 的坑</b>：{@code loadKitInfoSuccess} 的签名是
 * {@code (Intent)Z}（返回 boolean），session 结果落在 {@code mStubLoadInfo}
 * 字段里，不是返回值。2.6.27 里按 {@code (Intent)Bundle} 反射，永远取不到，
 * 每次都白等 1.2 秒再放行——日志里 9 次 {@code verify 始终为 null} 全是这个原因。</p>
 *
 * <p><b>2.6.28 的坑（务必注意）</b>：修对反射后 {@link #tryHandle} 真的会返回 true，
 * 而它的调用方是 {@code AppInstrumentation.callActivityOnCreate}，返回 true 就
 * **不执行 super.callActivityOnCreate**。Activity 没走 super.onCreate 会被框架判死：</p>
 * <pre>
 *   android.util.SuperNotCalledException: Activity {…HwIDStartUpGuideLoginActivity}
 *   did not call through to super.onCreate()
 * </pre>
 * <p>更糟的是它连 HMS 本来能跑通的 stubexplicit 页也抢跑，finish 之后账号页收到
 * CANCELED 跟着退，整条链在第 2 步就断了。所以 2.6.29 起不再从 onCreate 调用
 * tryHandle，只保留 {@link #describe} 做只读诊断。</p>
 *
 * <p>将来若要恢复直连，必须满足两条：① 只对 {@code runtimekit.stub.*} 生效，
 * 跳过 {@code stubexplicit.*}；② 先 {@code super.callActivityOnCreate()} 再
 * startActivity + finish，绝不能吞掉 super。</p>
 */
public final class StubLauncher {

    private static final String KEY_KIT_COMPONENT = "kit_component_name";
    private static final String KEY_STUB_COMPONENT = "stub_component_name";
    private static final String KEY_KIT_PACKAGE = "kit_package_name";
    private static final String KEY_KIT_UID = "kit_uid";

    /**
     * 已经由这个跳板页自己 startActivity 过的记录。
     *
     * <p>跳板页成功时会「启动真实页 + finish 自己」，这种 finishing 是正常交接，
     * 不能再去兜底，否则会把目标页起两遍。只有「finishing 但一次 startActivity
     * 都没发过」才是真的放弃。WeakHashMap 不持有 Activity 引用，页面销毁后自动回收。</p>
     */
    private static final Map<Activity, Boolean> STARTED =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());

    /**
     * 本进程里真正 resume 过的页面（按 intent 的 component 记）。
     *
     * <p>跳板页的 Java 类名永远是 {@code HMSKitStandardC2NEStubActivity1}，
     * 真实身份在 intent 的 component 里（HMS 会把 cmp 换成 kit 里的真实类）。
     * 2.6.29 日志里跳板 2 的 {@code kit_component_name} 和跳板 1 已经 resume 的
     * 真实页是同一个类 {@code OneKeyLoginOrRegisterActivity}——这是「自己拉自己」，
     * 再 startActivity 一次只会无限套娃，正确做法是当作成功返回。</p>
     */
    private static final java.util.Set<String> RESUMED =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 兜底次数上限，防止异常情况下链式递归。 */
    private static int sRescueCount;

    private StubLauncher() {
    }

    /** 由 AppInstrumentation.callActivityOnResume 调用。 */
    public static void markResumed(Activity a) {
        try {
            Intent it = a == null ? null : a.getIntent();
            ComponentName cn = it == null ? null : it.getComponent();
            if (cn != null && cn.getClassName() != null) RESUMED.add(cn.getClassName());
        } catch (Throwable ignored) {
        }
    }

    /** 由 AppInstrumentation.traceStart 调用：记下这个页面确实发起过启动。 */
    public static void markStarted(Activity a) {
        if (a == null) return;
        try {
            STARTED.put(a, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * HMS 自己放弃时的兜底（2.6.32 重写）。
     *
     * <p><b>2.6.30 / 2.6.31 的根本错误</b>：我以为 kit 页面是普通 Activity，
     * 直接 startActivity 就能起来。反编译 account 的 dex 才看明白：</p>
     * <pre>
     *   OneKeyLoginOrRegisterActivity
     *     -> SmsBaseActivity -> x9 -> ak0
     *     -> com.huawei.hms.runtimekit.container.kitsdk.KitActivity
     *     -> com.huawei.hms.runtimekit.container.kitsdk.KitContext   &lt;- 不是 android.app.Activity
     * </pre>
     * <p>kit 页面是「虚拟 Activity」，只能由 ContainerActivity 的 delegate 代理，
     * 任何 startActivity 都启动不了它。2.6.30 报构造函数 NPE、2.6.31 报
     * {@code cannot be cast to android.app.Activity}，都是这个原因。</p>
     *
     * <p>正确的路只有一条：让 ContainerActivity 自己的
     * {@code loadKitForActivity()} 成功。它有三个失败点：
     * ① {@code loadKitInfoSuccess} 返回 false；
     * ② {@code KitLoaderManager.loadKit(bundle,false)} 返回 false；
     * ③ {@code inject(uid, delegate)} 返回 false。
     * 这里在 onCreate 之后重放 ②③，把返回值/异常写进日志，并按容器里的真实
     * 位置修正 session 里的 apk_path / native_path 再试一次。</p>
     */
    public static void rescue(Activity a) {
        if (a == null) return;
        try {
            if (!a.isFinishing()) return;
            if (!isContainerStub(a, a.getClass().getName())) return;
            rescueNow(a);
        } catch (Throwable t) {
            Diag.log("act", "stub 兜底异常 " + t);
        }
    }

    /**
     * 在 HMS 自己的 onCreate 之前，先把 kit 的 ClassLoader / Application 预热好。
     *
     * <p>{@code ContainerActivity.onCreate -> loadKitForActivity} 是一条直线：
     * verifySession → loadKit → inject，任何一步失败就 finish。我们在它前面先跑
     * 一遍 verifySession + 修正 bundle + loadKit，把 kit 的 dex 和 Application
     * 真正建起来。HMS 随后自己再调 loadKit 时，KitLoaderManager 里已经有缓存，
     * 直接成功，inject 才有机会跑通。</p>
     */
    public static void prewarm(Activity a) {
        if (a == null) return;
        try {
            if (!isContainerStub(a, a.getClass().getName())) return;
            Diag.log("act", "stub 预热开始 " + a.getClass().getName());
            // 2.6.76：kit 的 DexClassLoader 父加载器是宿主的 PathClassLoader，
            // 里面已经有一份 base.apk 自带的 runtimekit（6.16）。子 dex 里是
            // 应用市场下发的 runtimekit（6.20）。父优先会把 6.16 的 KitContext /
            // NonCrashKitContext 遮住，跨版本继承直接 VerifyError，账号 kit 的
            // 所有页面（x9、HuaweiIDForSettingsActivity）连锁验证失败。
            // 这里在 loadKit 之前给 kit CL 打开 runtimekit 包的子优先加载。
            HmsProbe.installKitChildFirst();
            Bundle info = readStubInfo(a);
            if (info == null) {
                Method v = findLoadKitInfoSuccess(a.getClass());
                if (v == null) {
                    Diag.log("act", "stub 预热放弃：找不到 loadKitInfoSuccess");
                    return;
                }
                boolean ok = invokeVerify(a, v, a.getIntent());
                Diag.log("act", "stub 预热 verify -> " + ok);
                if (!ok) return;
                info = readStubInfo(a);
            }
            if (info == null) {
                Diag.log("act", "stub 预热放弃：verify 过了但 mStubLoadInfo 仍为空");
                return;
            }
            Diag.log("act", "stub 预热 info " + dumpBundle(info));
            fixKitPaths(a, info);
            Object mgr = kitLoader(a);
            if (mgr == null) {
                Diag.log("act", "stub 预热放弃：拿不到 KitLoaderManager");
                return;
            }
            Boolean ok = null;
            try {
                ok = callLoadKit(mgr, info);
            } catch (Throwable t) {
                Diag.log("act", "stub 预热 loadKit 抛异常 " + t);
                fallbackAppClass(info);
                dropKitLoader(mgr, info);
                ok = false;
            }
            Diag.log("act", "stub 预热 loadKit -> " + ok);
            if (ok != null && !ok) {
                fallbackAppClass(info);
                // loadKit 内部把异常吞掉只返回 false，这里不知道是不是
                // VerifyError，索性一律丢掉缓存重建一次：CL 是干净的，
                // 代价只是几十毫秒的重新加载。
                dropKitLoader(mgr, info);
                android.os.SystemClock.sleep(60);
                try {
                    Boolean again = callLoadKit(mgr, info);
                    Diag.log("act", "stub 预热 loadKit 重试 -> " + again);
                } catch (Throwable t) {
                    Diag.log("act", "stub 预热 loadKit 重试抛异常 " + t);
                    dropKitLoader(mgr, info);
                }
            }
            // kit 的 ClassLoader 此时已经建好，可以按它重新反序列化 extras 了。
            repairExtras(a, mgr, info);
            // 2.6.42：同一个 CL 上挂三条探针，把「无法获取数据」到底卡在
            // 取号、校验还是 mccmnc 为空一次区分开（见 HmsProbe）。
            Object kitCl = kitClassLoader(mgr, info);
            ClassLoader guestCl = a.getClassLoader();
            Diag.log("act", "probe CL kit=" + (kitCl == null ? "-"
                            : System.identityHashCode(kitCl))
                    + " guest=" + (guestCl == null ? "-" : System.identityHashCode(guestCl)));
            if (kitCl instanceof ClassLoader) {
                HmsProbe.install((ClassLoader) kitCl);
                HmsProbe.installKitChildFirst();
                HmsProbe.probeRuntimeClasses((ClassLoader) kitCl);
                HmsDeviceId.install((ClassLoader) kitCl);
            }
            // guest CL 和 kit CL 各自定义了一份 HMS 类，两边都要挂。
            HmsProbe.install(guestCl);
            HmsDeviceId.install(guestCl);
            logTelephony(a);
        } catch (Throwable t) {
            Diag.log("act", "stub 预热异常 " + t);
        }
    }

    /**
     * 把一个已经脏掉的 kit ClassLoader 从 KitLoaderManager 的缓存里删掉。
     *
     * <p>KitLoaderManager.f795c 是 {@code Map<包名_版本, Pair<loader, app>>}，
     * key 由 {@code n(kitUid)} 算出来。删除后下一次 loadKit 会重建
     * DexClassLoader，KitApplication / KitContext 重新走一遍校验。</p>
     */
    private static void dropKitLoader(Object mgr, Bundle info) {
        try {
            String uid = strOf(info, "kit_uid");
            if (uid == null) return;
            Method n = mgr.getClass().getMethod("n", String.class);
            n.setAccessible(true);
            Object key = n.invoke(mgr, uid);
            Field f = mgr.getClass().getDeclaredField("f795c");
            f.setAccessible(true);
            Object map = f.get(mgr);
            if (map instanceof Map && key != null) {
                Object old = ((Map) map).remove(key);
                Diag.log("act", "stub 清掉坏掉的 kit CL key=" + key + " -> " + (old != null));
            }
        } catch (Throwable t) {
            Diag.log("act", "stub 清 kit CL 失败 " + t);
        }
    }

    /**
     * 用 kit 自己的 ClassLoader 重新反序列化 intent 的 extras。
     *
     * <p><b>2.6.41 定位到的「登录页起来几十毫秒自己 finish」</b>：
     * 账号 kit 的 {@code LoginLoadingActivity} 拿到运营商数据后，把它包成
     * {@code CarrierData}（account kit 自己的 Parcelable）塞进 extras，再启动
     * {@code OneKeyLoginOrRegisterActivity}。真机上这两个页面、这个 Parcelable
     * 都在同一份 ClassLoader 里，拿回来强转没问题。</p>
     *
     * <p>容器里 Intent 要经过我们自己的 ActivityStack 和真实 system_server
     * 来回序列化，回到 guest 进程时 ActivityThread 用的是 **guest 的
     * ClassLoader** 反序列化；而 runtimekit 给 kit 建的是另一份
     * {@code DexClassLoader}（父加载器是宿主/system CL）。同一个
     * {@code CarrierData} 于是被定义了两份，kit 页面里的
     * {@code (CarrierData) getParcelableExtra(...)} 直接抛
     * ClassCastException，被它自己的 try/catch 接住，l0 保持 null：</p>
     * <pre>
     *   OneKeyLoginOrRegisterActivity.C9()
     *     mCarrierData = intent.getParcelableExtra("carrierDataKey")  // null
     *     → "mCarrierData is null, finish"
     *     → finish() → 跳板页销毁 → 整条登录链退回框架
     * </pre>
     *
     * <p>我们已经在 {@link #prewarm} 里把 kit 的 ClassLoader 建好了，这里把
     * extras 先写成 Parcel、再按 kit 的 ClassLoader 读回来，让
     * {@code CarrierData} 变成 kit CL 里的那一份，强转就不会再失败。顺带丢掉
     * session 里可能残留的 {@code kit_secure_intent}（那份 intent 是 kams 在
     * 别的 ClassLoader 下序列化的，同样会把这些 Parcelable 搞成两份）。</p>
     */
    private static void repairExtras(Activity a, Object mgr, Bundle info) {
        try {
            stripSecureIntent(info);

            String uid = strOf(info, "kit_uid");
            if (uid == null) {
                Diag.log("act", "stub 重解 extras 跳过：kit_uid 为空");
                return;
            }
            Method g = mgr.getClass().getMethod("getKitClassLoader", String.class);
            g.setAccessible(true);
            Object cl = g.invoke(mgr, uid);
            if (!(cl instanceof ClassLoader)) {
                Diag.log("act", "stub 重解 extras 跳过：kit ClassLoader 为空");
                return;
            }
            ClassLoader kitCl = (ClassLoader) cl;

            Intent it = a.getIntent();
            if (it == null) return;
            Bundle src = it.getExtras();
            if (src == null) return;

            android.os.Parcel p = android.os.Parcel.obtain();
            Bundle out;
            try {
                p.writeBundle(src);
                p.setDataPosition(0);
                out = p.readBundle(kitCl);
            } finally {
                p.recycle();
            }
            if (out == null) return;
            out.setClassLoader(kitCl);
            // 先强制反序列化一次：只要 kit CL 里有任何一个类加载不了，
            // readBundle 出来的 Bundle 会在 keySet 时抛 BadParcelableException。
            // 抛了就保持原 extras 不动，避免把页面搞坏。
            java.util.Set<String> outKeys = out.keySet();
            int want = src.keySet().size();
            if (outKeys.size() < want) {
                Diag.log("act", "stub 重解 extras 丢键 " + outKeys.size() + "/" + want + "，保持原样");
                return;
            }
            it.replaceExtras(out);

            Object carrier = null;
            try {
                carrier = out.get("carrierDataKey");
            } catch (Throwable ignored) {
            }
            Diag.log("act", "stub 重解 extras kit=" + uid
                    + " keys=" + out.keySet()
                    + " carrier=" + (carrier == null ? "null" : carrier.getClass().getName())
                    + " carrierCl=" + (carrier == null ? "-"
                    : System.identityHashCode(carrier.getClass().getClassLoader()))
                    + " kitCl=" + System.identityHashCode(kitCl));
        } catch (Throwable t) {
            Diag.log("act", "stub 重解 extras 失败 " + t);
        }
    }

    private static Object kitClassLoader(Object mgr, Bundle info) {
        try {
            String uid = strOf(info, "kit_uid");
            if (uid == null || mgr == null) return null;
            Method g = mgr.getClass().getMethod("getKitClassLoader", String.class);
            g.setAccessible(true);
            return g.invoke(mgr, uid);
        } catch (Throwable t) {
            Diag.log("act", "取 kit ClassLoader 失败 " + t);
            return null;
        }
    }

    /**
     * 2.6.42：一键登录第一步就要 {@code TerminalInfo.getDeviceMccmnc()}，
     * 它依赖 TelephonyManager 的 SIM state / 运营商 PLMN。容器里这条链路
     * 可能被虚拟服务或权限挡住，返回 null 就直接弹「无法获取数据」。
     * 每次预热打一行，和 {@link HmsProbe} 的返回值对照就能确认。
     */
    private static void logTelephony(Activity a) {
        StringBuilder sb = new StringBuilder("telephony:");
        try {
            sb.append(" permReadPhone=")
                    .append(a.checkSelfPermission("android.permission.READ_PHONE_STATE"));
        } catch (Throwable t) {
            sb.append(" permReadPhone=?").append(t.getClass().getSimpleName());
        }
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) a.getSystemService("phone");
            if (tm == null) {
                Diag.log("hms", sb.append(" tm=null").toString());
                return;
            }
            sb.append(" simState=").append(tel(tm, 0));
            sb.append(" simOperator=").append(tel(tm, 1));
            sb.append(" subscriberId=").append(tel(tm, 2));
            sb.append(" networkOperator=").append(tel(tm, 3));
            sb.append(" networkType=").append(tel(tm, 4));
        } catch (Throwable t) {
            sb.append(" ERR=").append(t.getClass().getSimpleName());
        }
        Diag.log("hms", sb.toString());
    }

    private static String tel(android.telephony.TelephonyManager tm, int what) {
        try {
            switch (what) {
                case 0:
                    return String.valueOf(tm.getSimState());
                case 1:
                    return String.valueOf(tm.getSimOperator());
                case 2:
                    String id = tm.getSubscriberId();
                    return id == null ? "null"
                            : id.substring(0, Math.min(5, id.length())) + "…";
                case 3:
                    return String.valueOf(tm.getNetworkOperator());
                case 4:
                    return String.valueOf(tm.getNetworkType());
                default:
                    return "-";
            }
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName();
        }
    }

    /**
     * kams 写进 session 的 {@code kit_secure_intent} 是「真机同 CL」前提下
     * 的产物；容器里它的 Parcelable 类身份和 kit CL 对不上，而且会覆盖我们
     * 刚修好的 intent。直接丢掉，让 ContainerActivity 用跳板页自己那份 intent。
     */
    private static void stripSecureIntent(Bundle info) {
        try {
            if (info.containsKey("kit_secure_intent")) {
                info.remove("kit_secure_intent");
                Diag.log("act", "stub 丢弃 kit_secure_intent（容器里 CL 不一致）");
            }
        } catch (Throwable t) {
            Diag.log("act", "stub 丢弃 kit_secure_intent 失败 " + t);
        }
    }

    private static void rescueNow(Activity a) {
        Bundle info = readStubInfo(a);
        if (info == null) {
            Diag.log("act", "stub 兜底：mStubLoadInfo 为空");
            failToast(a, "session 为空");
            return;
        }
        Diag.log("act", "stub info " + dumpBundle(info));

        Object mgr = kitLoader(a);
        if (mgr == null) {
            Diag.log("act", "stub 兜底：拿不到 KitLoaderManager");
            failToast(a, "找不到 KitLoaderManager");
            return;
        }
        Boolean ok = callLoadKit(mgr, info);
        if (ok != null && !ok) {
            // kams 刚生成 session，kit 的 odex / native 可能还没落盘，给一次很短的窗口
            android.os.SystemClock.sleep(60);
            ok = callLoadKit(mgr, info);
        }
        if (ok != null && !ok && fixKitPaths(a, info)) {
            Diag.log("act", "stub 路径/类名按容器实际位置修正后重试");
            ok = callLoadKit(mgr, info);
        }
        if (ok == null || !ok) {
            Diag.log("act", "stub loadKit 最终失败，HMS 的 session 里缺东西");
            failToast(a, "loadKit 失败");
            return;
        }
        callInject(a, mgr, info);
    }

    /**
     * 失败原因直接弹到屏幕上。
     *
     * <p>日志通道在重装之后可能整条断掉，用户拿到的永远是旧文件，只能靠
     * 肉眼把「卡在哪一步」带回来。这一步只在这些失败分支触发，
     * 正常走通时不会打扰。</p>
     */
    private static void failToast(Activity a, String why) {
        try {
            final android.content.Context c = a.getApplicationContext();
            final String msg = "华为账号加载失败：" + why;
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(c, msg,
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static final String CLS_KIT_LOADER =
            "com.huawei.hms.runtimekit.kitloader.KitLoaderManager";

    /**
     * 拿到 HMS 自己用的那个 KitLoaderManager 单例。
     *
     * <p>2.6.35 的 Toast 已经证明这里返回空：{@code Class.forName} 用的是宿主的
     * ClassLoader，而 {@code KitLoaderManager} 在 Framework-runtime.apk 里，
     * 由 runtimekit 自己的 DexClassLoader 加载，宿主 CL 永远看不到这个类
     * ——于是「找不到 KitLoaderManager」，跟 kit 本身能不能加载无关。</p>
     *
     * <p>正确做法是用跳板页自己的 ClassLoader：ContainerActivity 就在同一个
     * Framework-runtime.apk 里，它的 CL 必然能加载 KitLoaderManager，拿到的
     * 静态字段 {@code f} 也是 HMS 自己用过的那个实例，loadKit 的缓存因此共享。</p>
     */
    private static Object kitLoader(Activity a) {
        ClassLoader own = null;
        try {
            own = a.getClass().getClassLoader();
        } catch (Throwable ignored) {
        }
        ClassLoader[] all = new ClassLoader[]{
                own,
                Thread.currentThread().getContextClassLoader(),
                StubLauncher.class.getClassLoader()
        };
        Throwable first = null;
        for (ClassLoader cl : all) {
            if (cl == null) continue;
            try {
                Class<?> c = Class.forName(CLS_KIT_LOADER, false, cl);
                Method g = c.getMethod("getInstance", android.content.Context.class);
                g.setAccessible(true);
                // 与 ContainerActivity 一致，传 Activity 本身而不是 Application：
                // KitLoaderManager 构造里只用 Context 取目录，两者都行，但传 this
                // 能和 HMS 完全对齐，避免个别分支按 instanceof Activity 判断。
                Object mgr = g.invoke(null, a);
                if (mgr != null) {
                    Diag.log("act", "stub KitLoaderManager ok via "
                            + (cl == own ? "activityCL" : cl.getClass().getSimpleName()));
                    return mgr;
                }
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                if (first == null) first = c;
            }
        }
        Diag.log("act", "stub KitLoaderManager 三个 ClassLoader 都失败 " + first);
        return null;
    }

    private static Boolean callLoadKit(Object mgr, Bundle info) {
        try {
            Method m = null;
            for (Method x : mgr.getClass().getMethods()) {
                if ("loadKit".equals(x.getName()) && x.getParameterTypes().length == 2
                        && x.getParameterTypes()[0] == Bundle.class) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                Diag.log("act", "stub 找不到 loadKit(Bundle,boolean)");
                return null;
            }
            Object r = m.invoke(mgr, info, Boolean.FALSE);
            Diag.log("act", "stub loadKit -> " + r);
            return r instanceof Boolean ? (Boolean) r : null;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            Diag.log("act", "stub loadKit 抛异常 " + c);
            return Boolean.FALSE;
        }
    }

    /**
     * 真机上 kit 是 split，session 里的 apk_path / native_path 指向 split 的
     * 路径；容器里 account 是独立安装的包，路径完全不同。loadKit 第一步就是
     * 读这两个字段，路径不存在会直接抛 LoadKitException。
     */
    private static boolean fixKitPaths(Activity a, Bundle info) {
        try {
            String pkg = strOf(info, "kit_package_name");
            if (pkg == null) return false;
            android.content.pm.ApplicationInfo ai =
                    a.getPackageManager().getApplicationInfo(pkg, 0);
            boolean changed = false;
            String apk = strOf(info, "apk_path");
            if (apk == null || !new java.io.File(apk).exists()) {
                info.putString("apk_path", ai.sourceDir);
                Diag.log("act", "stub 修 apk_path " + apk + " -> " + ai.sourceDir);
                changed = true;
            }
            String nat = strOf(info, "native_path");
            if (nat == null || !new java.io.File(nat).exists()) {
                info.putString("native_path", ai.nativeLibraryDir);
                Diag.log("act", "stub 修 native_path " + nat + " -> " + ai.nativeLibraryDir);
                changed = true;
            }
            // 2.6.79：kit_app_class 不再预判。session 里的 JosApplication 可能
            // 在 kit 的多 dex / split 里，用单条 apk 路径做 loadable 检查会误判
            // ClassNotFound，把真正的 Application 换成 android.app.Application，
            // 结果 GameUiService 这类 kit 内部服务没初始化。改为先让 loadKit
            // 用原类跑，真失败（抛异常/返回 false）时才降级并重试。
            return changed;
        } catch (Throwable t) {
            Diag.log("act", "stub 修路径失败 " + t);
            return false;
        }
    }

    private static void callInject(Activity a, Object mgr, Bundle info) {
        try {
            String uid = strOf(info, "kit_uid");
            Field f = null;
            for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("stubActivityDelegate");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f == null) {
                Diag.log("act", "stub 找不到 stubActivityDelegate");
                failToast(a, "delegate 字段找不到");
                return;
            }
            f.setAccessible(true);
            Object del = f.get(a);
            Method m = null;
            for (Method x : mgr.getClass().getMethods()) {
                if ("inject".equals(x.getName()) && x.getParameterTypes().length == 2) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                Diag.log("act", "stub 找不到 inject");
                failToast(a, "inject 方法找不到");
                return;
            }
            Object r = m.invoke(mgr, uid, del);
            Diag.log("act", "stub inject -> " + r);
            if (Boolean.FALSE.equals(r)) {
                failToast(a, "inject 返回 false");
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            Diag.log("act", "stub inject 抛异常 " + c);
            failToast(a, "inject 异常");
        }
    }

    private static String dumpBundle(Bundle b) {
        StringBuilder sb = new StringBuilder("keys=");
        try {
            for (String k : b.keySet()) {
                Object v;
                try {
                    v = b.get(k);
                } catch (Throwable t) {
                    v = "?";
                }
                String s = String.valueOf(v);
                if (s.length() > 90) s = s.substring(0, 90) + "...";
                sb.append(k).append('=').append(s).append("; ");
            }
        } catch (Throwable t) {
            sb.append("dump-err ").append(t);
        }
        return sb.toString();
    }

    /** 2.6.79：loadKit 真的失败之后才把 kit 的 Application 降级成空的。 */
    private static void fallbackAppClass(Bundle info) {
        String appCls = strOf(info, "kit_app_class");
        if (appCls == null || "android.app.Application".equals(appCls)) return;
        info.putString("kit_app_class", "android.app.Application");
        Diag.log("act", "stub 降级 kit_app_class " + appCls
                + " -> android.app.Application（loadKit 失败后）");
    }

    private static boolean loadable(String apk, String cls, ClassLoader parent) {
        try {
            ClassLoader cl = new dalvik.system.PathClassLoader(apk, parent);
            Class.forName(cls, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String loadFail(String apk, String cls, ClassLoader parent) {
        try {
            ClassLoader cl = new dalvik.system.PathClassLoader(apk, parent);
            Class.forName(cls, false, cl);
            return "ok";
        } catch (Throwable t) {
            Throwable root = t;
            int n = 0;
            while (root.getCause() != null && n++ < 3) root = root.getCause();
            return root.getClass().getSimpleName() + ": " + root.getMessage();
        }
    }

    private static String strOf(Bundle b, String k) {
        try {
            Object v = b.get(k);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return true 表示已经由这里直接拉起真实页面，调用方**不要**再走
     *         {@code super.callActivityOnCreate()}（否则跳板页会再 finish 一次，
     *         把刚拉起来的页面一起带走）。
     */
    public static boolean tryHandle(Activity a) {
        if (a == null) return false;
        String cn = a.getClass().getName();
        if (!isContainerStub(a, cn)) return false;
        try {
            Method verify = findLoadKitInfoSuccess(a.getClass());
            if (verify == null) {
                Diag.log("act", "stub 直连放弃：找不到 loadKitInfoSuccess " + cn);
                return false;
            }
            Intent src = a.getIntent();
            if (src == null) return false;

            // loadKitInfoSuccess(Intent) 返回 boolean，session 结果写进 mStubLoadInfo。
            boolean ok = invokeVerify(a, verify, src);
            Bundle info = ok ? readStubInfo(a) : null;
            if (!ok) {
                // kams 还没就绪时 session 查不到。留很短一段兜底即可——
                // 2.6.27 日志证明等 1.2 秒也不会有变化，纯属让页面黑屏。
                long t0 = SystemClock.uptimeMillis();
                for (int i = 0; i < 4 && !ok; i++) {
                    SystemClock.sleep(60);
                    ok = invokeVerify(a, verify, src);
                }
                info = ok ? readStubInfo(a) : null;
                Diag.log("act", "stub 轮询 verify " + (SystemClock.uptimeMillis() - t0)
                        + "ms -> " + ok);
            }
            if (!ok || info == null) {
                Diag.log("act", "stub verify=false（mStubLoadInfo=" + (info == null) + "），放行原流程 " + cn);
                return false;
            }

            ComponentName kit = parcelable(info, KEY_KIT_COMPONENT);
            ComponentName stub = parcelable(info, KEY_STUB_COMPONENT);
            String pkg = str(info, KEY_KIT_PACKAGE);
            String uid = str(info, KEY_KIT_UID);
            Diag.log("act", "stub verify ok uid=" + uid + " pkg=" + pkg + " kit=" + kit + " stub=" + stub);

            if (kit == null || kit.getClassName() == null) {
                Diag.log("act", "stub 直连跳过：session 里没有 kit_component_name");
                return false;
            }
            // 极少数 session 的 kit_component_name 指回跳板页本身，直连会死循环。
            if (kit.getClassName().contains("runtimekit.stub")
                    || kit.getClassName().equals(cn)) {
                Diag.log("act", "stub 直连跳过：目标是跳板页自己 " + kit);
                return false;
            }

            Intent direct = new Intent(src);
            direct.setComponent(kit);
            // 跳板页是被 startActivityForResult 拉起来的，直连后要把结果交还给
            // 最初的调用方（登录结果、授权结果都靠这条链回去），否则调用方永远
            // 等不到 onActivityResult。
            direct.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

            boolean launched = false;
            try {
                a.startActivity(direct);
                launched = true;
            } catch (Throwable t) {
                Diag.log("act", "stub 直连目标不可用 " + kit + " " + t);
                String kcls = kit.getClassName();
                if (pkg != null && !"null".equals(pkg) && !pkg.equals(kit.getPackageName())) {
                    try {
                        direct.setComponent(new ComponentName(pkg, kcls));
                        a.startActivity(direct);
                        launched = true;
                        Diag.log("act", "stub 直连改包重试 " + pkg + "/" + kcls);
                    } catch (Throwable t2) {
                        Diag.log("act", "stub 直连改包仍失败 " + t2);
                    }
                }
            }
            if (!launched) return false;
            Diag.log("act", "stub 直连启动 " + direct.getComponent());
            a.finish();
            return true;
        } catch (Throwable t) {
            Diag.log("act", "stub 直连失败 " + cn + "\n" + Diag.stack(t));
            return false;
        }
    }

    /**
     * 只读诊断：跳板页 onCreate 结束后，把它手上的 session 状态摊进日志。
     *
     * <p>2.6.27/2.6.28 卡在「页面起来几十毫秒自己 finish」时，日志里只有
     * finishing=true，看不出是 session 没查到、还是 kit 加载失败。这里把
     * action / stub_session_id / kit_uid / kit_component_name 一起打出来，
     * 一眼就能分清。</p>
     *
     * @return null 表示不是跳板页，不用打
     */
    public static String describe(Activity a) {
        if (a == null) return null;
        try {
            if (!isContainerStub(a, a.getClass().getName())) return null;
            Intent it = a.getIntent();
            StringBuilder sb = new StringBuilder("stub{act=");
            sb.append(it == null ? "null" : StubSession.brief(it.getAction()));
            sb.append(" sid=");
            sb.append(it == null ? "null" : StubSession.brief(it.getStringExtra("stub_session_id")));
            Bundle info = readStubInfo(a);
            if (info == null) {
                sb.append(" info=null");
            } else {
                sb.append(" uid=").append(str(info, KEY_KIT_UID));
                sb.append(" kit=").append(str(info, KEY_KIT_COMPONENT));
            }
            // 2.6.41：看一眼 kit 页面实际拿到的那份 intent 里运营商数据还在不在
            // （登录页 null 就会当场 finish，这是「点开又退回」的直接原因）。
            Object carrier = null;
            try {
                Bundle ex = it == null ? null : it.getExtras();
                carrier = ex == null ? null : ex.get("carrierDataKey");
            } catch (Throwable ignored) {
            }
            sb.append(" carrier=").append(carrier == null ? "null" : carrier.getClass().getName());
            return sb.append('}').toString();
        } catch (Throwable t) {
            return "stub{describe-err " + t.getClass().getSimpleName() + "}";
        }
    }

    private static boolean isContainerStub(Activity a, String cn) {
        if (cn.startsWith("com.huawei.hms.runtimekit.stub.")) return true;
        for (Class<?> c = a.getClass(); c != null && c != Activity.class; c = c.getSuperclass()) {
            if ("com.huawei.hms.runtimekit.container.ContainerActivity".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Method findLoadKitInfoSuccess(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("loadKitInfoSuccess", Intent.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** @return loadKitInfoSuccess 的真实返回值（boolean），异常/签名不符一律 false。 */
    private static boolean invokeVerify(Activity a, Method m, Intent src) {
        try {
            Object r = m.invoke(a, src);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            Diag.log("act", "stub verify 异常 " + t);
            return false;
        }
    }

    /** session 解析结果在 ContainerActivity.mStubLoadInfo 字段里。 */
    /**
     * 2.6.65：给 HmsProbe 用——当前跳板页要拉起的 kit 页面类名
     * （{@code kit_component_name}），拿不到返回 null。
     */
    public static String kitComponentName(Activity a) {
        try {
            Bundle info = readStubInfo(a);
            if (info == null) return null;
            Object v = info.get(KEY_KIT_COMPONENT);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bundle readStubInfo(Activity a) {
        for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("mStubLoadInfo");
                f.setAccessible(true);
                Object v = f.get(a);
                return v instanceof Bundle ? (Bundle) v : null;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                Diag.log("act", "stub 读 mStubLoadInfo 失败 " + t);
                return null;
            }
        }
        return null;
    }

    private static ComponentName parcelable(Bundle b, String key) {
        try {
            Object v = b.get(key);
            return v instanceof ComponentName ? (ComponentName) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Bundle b, String key) {
        try {
            Object v = b.get(key);
            return v == null ? "null" : String.valueOf(v);
        } catch (Throwable t) {
            return "?";
        }
    }
}
