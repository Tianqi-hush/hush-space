package com.hush.utils;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 华为账号请求失败定位探针（2.6.43）。
 *
 * <p>2.6.42 的结论修正：点「立即登录/注册」弹出的那句
 * 「无法获取数据，请稍后再试。」<b>不是</b> {@code OneKey.t2()} 的
 * {@code hwid_one_key_request_error}（那句是
 * 「请求失败，请使用其他方式登录/注册。」），而是
 * {@code x9.showErrorDialog(R.string.CS_ERR_for_unable_get_data)} ——
 * 账号 kit 里所有 BaseActivity 共用的通用错误出口：</p>
 * <pre>
 *   x9.showRequestFailedDialog(bundle)
 *     -> x9.dealRequestSucOrFail(bundle, ErrorStatus)
 *          bundle[{requestError=[ErrorCode]:…}]
 *          -> 不在特判表里 -> showErrorDialog(CS_ERR_for_unable_get_data)
 * </pre>
 *
 * <p>2.6.42 只挂了 OneKey 的 H4 / w7 / t2，这三个都是「一键取号那条链」的
 * 回调，通用请求失败根本不走它们，所以日志里一个都没有触发。2.6.43 改挂：</p>
 * <pre>
 *   ① x9.showErrorDialog(int,int)            最终弹框，含资源 id 和文案
 *   ② x9.showRequestFailedDialog(Bundle)     失败入口，bundle 里有 ErrorStatus
 *   ③ x9.dealRequestSucOrFail(Bundle,ES)     错误码 + 原因
 *   ④ HwIDRestClientCall.execute()           URL、HTTP 码、errCode、exception
 * </pre>
 *
 * <p>同一份探针要装在两个 ClassLoader 上：kit 自己的 CL 和 guest 的 CL。
 * 2.6.41 已经证明同一个类会被两边各定义一份，只挂一边可能完全不命中。</p>
 */
public final class HmsProbe {

    private static final String CLS_ONE_KEY =
            "com.huawei.hwid20.login.loginbysms.OneKeyLoginOrRegisterActivity";
    private static final String CLS_TERMINAL =
            "com.huawei.hwid.common.util.TerminalInfo";
    private static final String CLS_X9 = "x9";
    private static final String CLS_ERROR_STATUS =
            "com.huawei.hwid.core.helper.handler.ErrorStatus";
    private static final String CLS_REST_CALL =
            "com.huawei.hwid.common.network.HwIDRestClientCall";
    private static final String CLS_ACCT_MGR =
            "com.huawei.hwid.common.account.APKAccountManager";
    private static final String CLS_MEM_CACHE =
            "com.huawei.hwid.common.memcache.HwIDMemCache";

    private static final Set<ClassLoader> DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());

    private HmsProbe() {
    }

    /** cl 可以是 kit 的 ClassLoader，也可以是 guest 的 ClassLoader。 */
    public static void install(ClassLoader cl) {
        if (cl == null || DONE.contains(cl)) return;
        DONE.add(cl);
        Diag.log("hms", "探针装到 CL " + System.identityHashCode(cl));
        hookMccmnc(cl);
        hookOneKey(cl);
        hookX9(cl, cl);
        hookFailedHandlers(cl);
        hookDialogs(cl);
        hookDeviceId(cl);
        hookHttp(cl);
        hookAccountSave(cl);
        hookAccountCrypto(cl);
        hookAccountCenterFallback(cl);
        hookAlertMessage();
        hookActivityExit();
        hookGamePrivacy(cl);
        hookUnlockGate(cl);
        hookSignInFlow(cl);
        hookJosSignIn(cl);
        hookWebAuth(cl);
        hookResultRelay(cl);
    }

    /** 2.6.80：游戏隐私协议页（HMS game kit 的 p20 PrivacyProtocolDelegate）。 */
    private static boolean sGamePrivacyHooked;

    /**
     * 2.6.80 的定位与兜底。
     *
     * <p>2.6.79 日志已经证明 JosApplication 正常加载、隐私页也能真正显示出来
     * （{@code HMSKitStandardC2TransparentActivity1 finishing=false}）。但用户点
     * 「同意」之后 {@code act: stubFinish} 一次都没有出现，游戏进程里的
     * BridgeActivity 也一直没有 resume —— 说明 p20 没能走到
     * {@code J(kitActivity, 0)} → {@code kitActivity.finish()} 这一步。</p>
     *
     * <p>p20 的正常链路是：</p>
     * <pre>
     *   点击同意 F()
     *     -> C() 起后台统计任务
     *     -> 非统一游戏直接 J(kitActivity, 0)
     *     -> 统一游戏 w() 发 client.hms.gs.unify.init 网络请求
     *        -> 成功/失败回调里再 J()
     *   J() 里 setResult(-1, {json_header={"status_code":0,"error_code":i},json_body={}})
     *     然后 z() -> kitActivity.finish() -> stubFinish
     * </pre>
     *
     * <p>如果 w() 的网络请求在容器里挂住、或 C() 抛异常，J() 永远不会执行：
     * 对话框被系统关掉、透明跳板页却留在原地，游戏进程一直等 init 回调，
     * 表现就是「点了同意后一直转圈」。</p>
     *
     * <p>这里做两件事：① 把 F/G/C/w/J 都挂上探针，日志里能直接看出停在哪一步；
     * ② 点「同意」2.2s 后如果页面还没结束，就按 p20.J 的格式补一份成功结果
     * （status_code=0 / error_code=0）再 finish，把游戏从死等里放出来。</p>
     */
    private static void hookGamePrivacy(final ClassLoader cl) {
        if (sGamePrivacyHooked) return;
        final Class<?> p20;
        try {
            p20 = Class.forName("com.huawei.hms.game.p20", false, cl);
        } catch (Throwable ignored) {
            return;
        }
        sGamePrivacyHooked = true;
        try {
            for (final Method m : p20.getDeclaredMethods()) {
                String n = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if ("a".equals(n) && pt.length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "隐私协议 delegate 挂到页面上");
                        }
                    });
                } else if ("F".equals(n) && pt.length == 5) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Diag.log("game", "隐私协议页 用户点了同意");
                            schedulePrivacyFinish(param.args[2]);
                        }
                    });
                } else if ("G".equals(n) && pt.length == 4) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "隐私协议页 用户点了不同意");
                        }
                    });
                } else if ("C".equals(n) && pt.length == 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "隐私协议页 开始上报统计任务");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null) {
                                Diag.log("game", "隐私协议页 统计任务抛异常 "
                                        + causeChain(param.getThrowable()));
                            }
                        }
                    });
                } else if ("w".equals(n) && pt.length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "隐私协议页 开始统一初始化网络请求");
                            schedulePrivacyFinish(param.args[0]);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null) {
                                Diag.log("game", "统一初始化抛异常 "
                                        + causeChain(param.getThrowable()));
                            }
                        }
                    });
                } else if ("J".equals(n) && pt.length == 2
                        && pt[1] == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "隐私协议页 交付结果 error_code=" + param.args[1]);
                        }
                    });
                } else if ("e".equals(n) && pt.length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "隐私协议 delegate 页面销毁");
                        }
                    });
                }
            }
            Diag.log("game", "隐私协议页探针已挂");
        } catch (Throwable t) {
            Diag.log("game", "隐私协议页探针挂载失败 " + t);
        }
    }

    private static void schedulePrivacyFinish(final Object kitActivity) {
        if (kitActivity == null) return;
        try {
            final android.os.Handler h =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object finishing = invokeNoArg(kitActivity, "isFinishing");
                        if (Boolean.TRUE.equals(finishing)) {
                            Diag.log("game", "隐私协议页已自行结束，兜底不介入");
                            return;
                        }
                        Diag.log("game", "隐私协议页 2.2s 未结束，按成功结果收尾");
                        android.content.Intent it = new android.content.Intent();
                        it.putExtra("json_header",
                                "{\"status_code\":0,\"error_code\":0}");
                        it.putExtra("json_body", "{}");
                        invoke(kitActivity, "setResult", -1, it);
                        invokeNoArg(kitActivity, "finish");
                    } catch (Throwable t) {
                        Diag.log("game", "隐私协议页兜底失败 " + causeChain(t));
                    }
                }
            }, 2200);
        } catch (Throwable t) {
            Diag.log("game", "隐私协议页兜底没排上 " + t);
        }
    }

    private static boolean sCenterFallbackHooked;
    private static final Map<Object, Boolean> COMPAT_ACT =
            Collections.synchronizedMap(new java.util.WeakHashMap<Object, Boolean>());
    /** 已经换成 Hush 占位页的 Activity。只有点“关闭”时才会移除，之后才放行 finish。 */
    private static final Map<Object, Boolean> FALLBACK_SHOWN =
            Collections.synchronizedMap(new java.util.WeakHashMap<Object, Boolean>());
    private static final int REQ_LOGIN = 0x48570001;
    /** 最近一次「用户自己从 Hush 空间打开 HMS」的时刻（uptimeMillis）。 */
    private static volatile long sUserFlowAt;

    /**
     * 2.6.64：HMS 的账号中心是动态模块 HwID_Features 里的
     * {@code com.huawei.hwid20.AccountCenter.CenterFeatureActivity}，安装包
     * （AccountKit-base.apk）里只有这个类名字符串，没有类实体。真机上这个模块
     * 由应用市场按需下发，容器里永远装不上。
     *
     * <p>后果：登录成功 → HMS 跳账号中心 → 取不到类 →
     * {@code ImplicitActivityDelegateImpl.L()} 直接 {@code stubFinish()} →
     * 所有页面销毁退回空间；之后再点 HMS 还是这条路，表现就是“打不开”。</p>
     *
     * <p>这里在 kit activity 创建失败（f18e == null）时把容器 Activity 留下来，
     * 拦掉它的 stubFinish，换成 Hush 的占位页（显示已登录账号），
     * 至少 HMS 能打开、账号状态可见。</p>
     */
    private static void hookAccountCenterFallback(ClassLoader cl) {
        if (sCenterFallbackHooked) return;
        try {
            // ① 先拦 stubFinish，保证下面 L() 里的 finish 不会执行
            try {
                Class<?> ca = Class.forName("com.huawei.hms.runtimekit.container.ContainerActivity", false, cl);
                for (final Method m : ca.getDeclaredMethods()) {
                    if ("stubFinish".equals(m.getName()) && m.getParameterTypes().length == 0) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (FALLBACK_SHOWN.containsKey(param.thisObject)) {
                                    Diag.log("acct", "账号中心模块缺失：拦下 stubFinish，保留占位页");
                                    param.setResult(null);
                                    return;
                                }
                                logStubFinish(param.thisObject);
                                skipGamePrivacy(param.thisObject);
                            }
                        });
                        break;
                    }
                }
            } catch (Throwable t) {
                Diag.log("acct", "stubFinish 拦截没挂上 " + t);
            }
            // ② 判定点不放这里：E.h / E.b 是 runtimekit 自己的 DexClassLoader 里的
            // 默认包类，宿主 CL 和 kit CL 都看不到（2.6.64 日志里就是
            // ClassNotFoundException: E.h）。改为在 Activity.finish 上按跳板页
            // 的 kit_component_name 判定，见 maybeCenterFallback()。
            // ③ 账号中心包装页会在这台容器里一直卡在 loading（2.6.65 日志
            //    "09:56:04 resume → 09:56:26 用户手动返回"），1.5s 后直接换占位页。
            XposedBridge.hookMethod(
                    android.app.Activity.class.getDeclaredMethod("onCreate", Bundle.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof android.app.Activity)) return;
                                final android.app.Activity a = (android.app.Activity) param.thisObject;
                                // 用户从 Hush 空间点 HMS 图标进来时，启动方是 com.hush.space；
                                // 第三方 App 通过 SDK 拉起的页面启动方是它自己。
                                noteUserFlow(a);
                                if (!a.getClass().getName().endsWith("HuaweiIDForSettingsActivity")) return;
                                if (!isAliasLauncher(a)) return;
                                Diag.log("acct", "账号中心包装页 onCreate，1.5s 后检查是否卡住");
                                a.getWindow().getDecorView().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            if (a.isFinishing() || FALLBACK_SHOWN.containsKey(a)) return;
                                            Diag.log("acct", "账号中心包装页 1.5s 无内容，换占位页");
                                            showCompat(a);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }, 1500);
                            } catch (Throwable t) {
                                Diag.log("acct", "包装页兜底失败 " + t);
                            }
                        }
                    });
            // ④ 占位页里点“登录/重新登录”拉起的登录页返回后，重画占位页。
            XposedBridge.hookMethod(
                    android.app.Activity.class.getDeclaredMethod("onActivityResult",
                            int.class, int.class, android.content.Intent.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if ((Integer) param.args[0] != REQ_LOGIN) return;
                                if (!(param.thisObject instanceof android.app.Activity)) return;
                                final android.app.Activity a = (android.app.Activity) param.thisObject;
                                Diag.log("acct", "占位页收到登录结果 resultCode=" + param.args[1]);
                                FALLBACK_SHOWN.put(a, Boolean.TRUE);
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            a.setContentView(buildCompatView(a));
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                });
                            } catch (Throwable t) {
                                Diag.log("acct", "登录结果回显失败 " + t);
                            }
                        }
                    });
            sCenterFallbackHooked = true;
            Diag.log("acct", "已挂账号中心兜底页");
        } catch (Throwable t) {
            Diag.log("acct", "账号中心兜底挂载失败 " + t);
        }
    }

    private static int sStubFinishLogs;

    /** 2.6.77：把「为什么要 stubFinish」的调用栈打出来（每个进程最多 6 条）。 */
    private static void logStubFinish(Object activity) {
        if (sStubFinishLogs >= 6) return;
        sStubFinishLogs++;
        try {
            StringBuilder sb = new StringBuilder("stubFinish 调用栈 act=");
            sb.append(activity.getClass().getName());
            StackTraceElement[] st = new Throwable().getStackTrace();
            int n = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("de.robv.android.xposed")
                        || c.startsWith("com.hush.utils.HmsProbe")) {
                    continue;
                }
                sb.append(" <- ").append(c).append("#").append(e.getMethodName());
                if (++n >= 8) break;
            }
            Diag.log("act", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 2.6.78：光遇登录走的是 HMS 游戏 kit 的隐私协议页
     * （{@code intent.extra.hms.game.DELEGATE_NAME=jos.privacyProtocolDelegate}）。
     * 2.6.77 直接 setResult(RESULT_OK) 把页面自己的返回数据抹掉了，
     * 反而让跳转链断掉（游戏一直转圈）。这里只观察，不改返回值。
     */
    private static void skipGamePrivacy(Object o) {
        if (!(o instanceof android.app.Activity)) return;
        android.app.Activity a = (android.app.Activity) o;
        try {
            android.content.Intent it = a.getIntent();
            if (it == null) return;
            String d = it.getStringExtra("intent.extra.hms.game.DELEGATE_NAME");
            if (!"jos.privacyProtocolDelegate".equals(d)) return;
            Object code = readField(a, "mResultCode");
            Object data = readField(a, "mResultData");
            Diag.log("act", "隐私协议页结束 resultCode=" + code
                    + " data=" + (data == null ? "null"
                    : ((android.content.Intent) data).toUri(android.content.Intent.URI_INTENT_SCHEME))
                    + " finish=" + a.isFinishing());
        } catch (Throwable t) {
            Diag.log("act", "隐私协议页观察失败 " + t);
        }
    }

    private static Field kitActivityField(Class<?> k) {
        while (k != null) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().getName().endsWith("KitActivity")) return f;
            }
            k = k.getSuperclass();
        }
        return null;
    }

    /** 从 kit 活动代理里取出它所属的容器 Activity（接口方法 getContainerActivity）。 */
    private static Object containerOf(Object delegate) {
        Class<?> k = delegate.getClass();
        while (k != null) {
            for (Field f : k.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(delegate);
                    if (v == null) continue;
                    Object a = v.getClass().getMethod("getContainerActivity").invoke(v);
                    if (a instanceof android.app.Activity) return a;
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    private static android.view.View buildCompatView(android.app.Activity host) {
        String phone = null;
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(host);
            android.accounts.Account[] as = am.getAccountsByType("com.huawei.hwid");
            if (as != null && as.length > 0) {
                String name = am.getUserData(as[0], "accountName");
                phone = (name == null || name.isEmpty()) ? as[0].name : name;
            }
        } catch (Throwable ignored) {
        }
        int dp = (int) host.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout ll = new android.widget.LinearLayout(host);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.setBackgroundColor(android.graphics.Color.WHITE);
        ll.setPadding(24 * dp, 96 * dp, 24 * dp, 24 * dp);

        android.widget.TextView title = new android.widget.TextView(host);
        title.setText(phone == null ? "华为账号中心暂时无法打开" : "华为账号已登录");
        title.setTextSize(20);
        title.setTextColor(android.graphics.Color.BLACK);
        ll.addView(title);

        if (phone != null) {
            android.widget.TextView num = new android.widget.TextView(host);
            num.setText(maskPhone(phone));
            num.setTextSize(16);
            num.setTextColor(android.graphics.Color.DKGRAY);
            num.setPadding(0, 8 * dp, 0, 0);
            ll.addView(num);
        }

        android.widget.TextView tip = new android.widget.TextView(host);
        tip.setText("账号已保存在 Hush 中，游戏和应用可以直接使用。\n"
                + "HMS 账号中心页面属于动态模块（HwID_Features），未内置到容器，"
                + "所以用本页代替。");
        tip.setTextSize(13);
        tip.setTextColor(android.graphics.Color.GRAY);
        tip.setPadding(0, 16 * dp, 0, 0);
        ll.addView(tip);

        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 24 * dp;

        android.widget.Button login = new android.widget.Button(host);
        login.setText(phone == null ? "登录华为账号" : "重新登录");
        login.setLayoutParams(lp);
        login.setOnClickListener(v -> {
            try {
                android.content.Intent it = new android.content.Intent();
                it.setComponent(new android.content.ComponentName("com.huawei.hwid",
                        "com.huawei.hms.runtimekit.stubexplicit.HwIDStartUpGuideLoginActivity"));
                String self = host.getPackageName();
                it.putExtra("businessPackageName", self);
                it.putExtra("KEY_PACKAGE_NAME", self);
                it.putExtra("callingpackage", self);
                it.putExtra("startActivityWay", 3); // StartActivityWay.FromApp
                host.startActivityForResult(it, REQ_LOGIN);
                Diag.log("acct", "占位页：拉起华为登录页");
            } catch (Throwable t) {
                Diag.log("acct", "占位页：拉起登录页失败 " + t);
            }
        });
        ll.addView(login);

        android.widget.Button close = new android.widget.Button(host);
        close.setText("关闭");
        close.setLayoutParams(lp);
        close.setOnClickListener(v -> dismissCompat(host));
        ll.addView(close);
        return ll;
    }

    private static String maskPhone(String s) {
        if (s == null || s.length() < 7) return s == null ? "" : s;
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }

    private static boolean sAcctHooked;

    /**
     * 2.6.61：HMS 登录成功后 {@code APKAccountManager.saveAccount()} 只把账号写进
     * AccountManager，<b>不会</b>刷新 {@code HwIDMemCache}；真机上靠
     * inner provider 的 notify → ContentObserver 异步刷新，容器里这条 notify 链
     * 是断的（UUID 也从 inner provider 读不到）。结果：
     * <pre>
     *   08:20:18.957 addAccountExplicitly ok=true / setAuthToken ok
     *   → 登录其实成功了
     *   → 但 HwIDMemCache 仍是空，HMS 认为“未登录”，页面停在登录页
     *   → 用户再点一次登录：isAccountAlreadyLogin=true
     *   → processAlreadyLogin → removeAccount + finish
     *   → 08:20:39.554 所有页面销毁，退回空间
     * </pre>
     * 这里在 saveAccount 成功后同步调用 HwIDMemCache.initHwAccount()，
     * 让登录状态立刻生效，登录成功后可直接进账号中心（GOTO_ACCOUNTCENTER_LOGINSUCCESS）。
     */
    private static void hookAccountSave(ClassLoader cl) {
        if (sAcctHooked) return;
        try {
            Class<?> c = Class.forName(CLS_ACCT_MGR, false, cl);
            for (final Method m : c.getDeclaredMethods()) {
                if (!"saveAccount".equals(m.getName()) && !"saveAccounts".equals(m.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object r = param.getResult();
                            boolean ok = !(r instanceof Boolean) || (Boolean) r;
                            Diag.log("acct", m.getName() + " 完成 ok=" + ok
                                    + (ok ? "，刷新 HwIDMemCache" : "，不刷新"));
                            if (ok) {
                                refreshHwIDMemCache(param.thisObject.getClass().getClassLoader());
                            }
                        } catch (Throwable t) {
                            Diag.log("acct", m.getName() + " 后刷新失败 " + t);
                        }
                    }
                });
                sAcctHooked = true;
                Diag.log("acct", "已挂 " + c.getName() + "." + m.getName());
            }
            // 登录页点「登录」时先走这里：返回 true 就会 removeAccount + finish。
            for (final Method m : c.getDeclaredMethods()) {
                if (!"isAccountAlreadyLogin".equals(m.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object[] a = param.args;
                        String name = (a != null && a.length > 1) ? String.valueOf(a[1]) : "?";
                        boolean r = Boolean.TRUE.equals(param.getResult());
                        // 2.6.63：返回 true 会走 processAlreadyLogin → removeAccount + finish。
                        // 如果库里这条账号是「空壳」（userId 等字段都没写进去），它其实
                        // 不是已登录状态，强制按未登录处理，避免点一次登录就被踢回空间。
                        String why = "";
                        if (r) {
                            try {
                                if (isEmptyHmsAccount((android.content.Context) a[0], name)) {
                                    param.setResult(false);
                                    r = false;
                                    why = " 空壳账号强制 false";
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        Diag.log("acct", "isAccountAlreadyLogin(" + name + ")=" + r + why + caller());
                    }
                });
                Diag.log("acct", "已挂 " + c.getName() + ".isAccountAlreadyLogin");
            }
        } catch (Throwable t) {
            Diag.log("acct", "挂账号保存探针失败 " + t);
        }
    }

    private static boolean sCryptHooked;

    /**
     * 2.6.62：HMS 写账号数据前会做双层加密（{@code eb0.p/q} → {@code iq} 的
     * AES-GCM work key）。work key 依赖 keystore / inner provider，在容器里初始化
     * 失败时 {@code iq.c()} 对任何输入都返回 ""，日志表现就是：
     * <pre>
     *   setUserData ... key=userId len=0      ← 连字面量 "0"(STValidStatus) 都是 0
     *   setAuthToken ... len=0
     * </pre>
     * 账号写进去了，但每个字段都是空串，HwIDMemCache 永远构建不出 HwAccount，
     * 于是登录状态永远不成立，点一次登录就循环一次。
     *
     * <p>这里把 {@code eb0} 的四个对外加解密入口旁路成恒等函数：
     * p/q（写）直接落明文，h/i（读）直接返回明文。数据只存在 Hush 自己的
     * AccountManager 里，不需要这层加密。</p>
     */
    private static void hookAccountCrypto(ClassLoader cl) {
        if (sCryptHooked) return;
        Class<?> c = null;
        for (String name : new String[]{"eb0", "defpackage.eb0"}) {
            try {
                c = Class.forName(name, false, cl);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (c == null) {
            Diag.log("acct", "账号加解密旁路失败：找不到 HwIDEncrypter 类");
            return;
        }
        try {
            int hooked = 0;
            for (final Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (!"p".equals(n) && !"q".equals(n) && !"h".equals(n) && !"i".equals(n)) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 2 || pt[0] != android.content.Context.class
                        || m.getReturnType() != String.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object v = param.args[1];
                        String s = (String) v;
                        // h/i 是读写共用入口：带 {{NNN}} 前缀的是历史密文，直接当空值，
                        // 避免把密文当明文用；其余情况原样返回。
                        if (s != null && s.startsWith("{{")) {
                            param.setResult("");
                        } else {
                            param.setResult(s);
                        }
                    }
                });
                hooked++;
            }
            sCryptHooked = true;
            Diag.log("acct", "账号加解密旁路 " + c.getName() + " 挂了 " + hooked + " 个入口");
        } catch (Throwable t) {
            Diag.log("acct", "挂账号加解密旁路失败 " + t);
        }
    }

    /**
     * 2.6.63：判断 AccountManager 里的这条华为账号是不是「空壳」。
     * userId 是 HMS 写账号时最先写、且后面所有逻辑都要用的字段；它是空的，
     * 说明整条账号数据都没落库，不能当成「已登录」。
     */
    private static boolean isEmptyHmsAccount(android.content.Context ctx, String name) {
        if (ctx == null || name == null || name.isEmpty() || name.indexOf('*') >= 0) {
            return false;
        }
        android.accounts.Account acct = new android.accounts.Account(name, "com.huawei.hwid");
        String userId = android.accounts.AccountManager.get(ctx).getUserData(acct, "userId");
        boolean empty = userId == null || userId.isEmpty();
        if (empty) Diag.log("acct", "账号 userId 为空，判定为空壳");
        return empty;
    }

    private static void refreshHwIDMemCache(ClassLoader cl) {
        try {
            Class<?> mc = Class.forName(CLS_MEM_CACHE, false, cl);
            Method getInstance = mc.getMethod("getInstance");
            Object inst = getInstance.invoke(null);
            Method init = mc.getMethod("initHwAccount");
            init.invoke(inst);
            Object acc = mc.getMethod("getHwAccount").invoke(inst);
            Diag.log("acct", "HwIDMemCache 已刷新，账号=" + (acc != null));
        } catch (Throwable t) {
            Diag.log("acct", "刷新 HwIDMemCache 失败 " + t);
        }
    }

    private static boolean sExitHooked;

    /**
     * 2.6.48：2.6.47 的日志里 OneKey → 短信页 → LoginLoading 之后，
     * 整条链在 20:19:53.3 自己 finish 掉退回框架，中间一个 HTTP 请求都没有。
     * 不能再猜了，直接挂 Activity.finish / onBackPressed：谁关的、从哪一行关的。
     */
    private static void hookActivityExit() {
        if (sExitHooked) return;
        sExitHooked = true;
        try {
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod("finish"),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (maybeCenterFallback(param.thisObject)) {
                                param.setResult(null);
                                return;
                            }
                            logExit("finish", param.thisObject);
                        }
                    });
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod("onBackPressed"),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof android.app.Activity
                                    && FALLBACK_SHOWN.containsKey(param.thisObject)) {
                                dismissCompat((android.app.Activity) param.thisObject);
                                param.setResult(null);
                                return;
                            }
                            logExit("onBackPressed", param.thisObject);
                        }
                    });
            Diag.log("fin", "探针已挂 Activity.finish / onBackPressed");
        } catch (Throwable t) {
            Diag.log("fin", "探针 Activity.finish 失败 " + t);
        }
    }

    private static void logExit(String what, Object a) {
        try {
            if (!(a instanceof android.app.Activity)) return;
            android.app.Activity act = (android.app.Activity) a;
            String cn = act.getClass().getName();
            if (!cn.startsWith("com.huawei.") && !cn.startsWith("x9")) return;
            String who = "?";
            try {
                android.content.ComponentName c = act.getIntent().getComponent();
                if (c != null) who = c.getClassName();
            } catch (Throwable ignored) {
            }
            Diag.log("fin", what + " " + cn + " who=" + who + caller());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 2.6.65：账号中心模块缺失时，拦掉跳板页的 finish，换成 Hush 占位页。
     *
     * <p>2.6.64 挂在 {@code E.h.L()} 上，但 E.h/E.b 在 runtimekit 自己的
     * DexClassLoader 里，宿主 CL 拿不到，最终 CNFE（日志已证实）。这里改为只看
     * 跳板页自己携带的 {@code kit_component_name}：账号中心那一页是
     * {@code com.huawei.hwid20.AccountCenter.CenterFeatureActivity}，它在
     * AccountKit-base.apk 里只有字符串、没有类实体，必然创建失败并立刻 finish。
     * 拦下以后窗口留在屏幕上，内容换成 Hush 的账号占位页。</p>
     */
    private static boolean maybeCenterFallback(Object obj) {
        if (!(obj instanceof android.app.Activity)) return false;
        final android.app.Activity a = (android.app.Activity) obj;
        try {
            if (FALLBACK_SHOWN.containsKey(a)) {
                // 占位页在，别让 HMS 自己把它关掉；只有点“关闭”才会清掉这个标记。
                return true;
            }
            if (!isAccountCenterPage(a)) return false;
            showCompat(a);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 2.6.67：只有「用户自己从 Hush 空间点 HMS 图标进来」的流程才换占位页。
     *
     * <p>第三方 App 调用 HMS SDK 登录时，HMS 会用它自己的 Activity 通过
     * {@code startActivityForResult} 等账号中心返回结果。2.6.66 无条件拦截 finish，
     * 调用方永远收不到结果，游戏和应用的 SDK 调用就卡死了（用户反馈
     * “其它软件无法正常调用 hmscore 的 SDK”）。这里用启动方包名区分：
     * 从空间打开时 intent 里 {@code callingpackage=com.hush.space}。</p>
     */
    private static boolean isAccountCenterPage(android.app.Activity a) {
        boolean sdkFlow = !withinUserFlow();
        String kit = StubLauncher.kitComponentName(a);
        if (kit != null && kit.contains("AccountCenter.CenterFeatureActivity")) {
            return !sdkFlow;
        }
        return isAliasLauncher(a);
    }

    private static void noteUserFlow(android.app.Activity a) {
        try {
            android.content.Intent it = a.getIntent();
            if (it == null) return;
            String caller = it.getStringExtra("callingpackage");
            if ("com.hush.space".equals(caller)) {
                if (withinUserFlow()) return;
                sUserFlowAt = android.os.SystemClock.uptimeMillis();
                Diag.log("acct", "用户从空间打开 HMS（callingpackage=com.hush.space），"
                        + "账号中心页面由 Hush 接管");
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean withinUserFlow() {
        long at = sUserFlowAt;
        return at > 0 && android.os.SystemClock.uptimeMillis() - at < 120000;
    }

    /** 用户点 HMS 图标时打开的 {@code CenterActivityAlias}（intent 已被清空）。 */
    private static boolean isAliasLauncher(android.app.Activity a) {
        try {
            android.content.Intent it = a.getIntent();
            if (it == null) return false;
            android.content.ComponentName c = it.getComponent();
            return c != null && c.getClassName().endsWith("CenterActivityAlias");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void showCompat(final android.app.Activity a) {
        FALLBACK_SHOWN.put(a, Boolean.TRUE);
        String kit = StubLauncher.kitComponentName(a);
        Diag.log("acct", "账号中心模块缺失：换成 Hush 占位页 cls=" + a.getClass().getName()
                + " kit=" + kit);
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    a.setContentView(buildCompatView(a));
                    Diag.log("acct", "账号中心占位页已渲染");
                } catch (Throwable t) {
                    Diag.log("acct", "账号中心占位页渲染失败 " + t);
                }
            }
        });
    }

    /** 用户点“关闭”：先解除拦截，再真正 finish。 */
    private static void dismissCompat(android.app.Activity a) {
        FALLBACK_SHOWN.remove(a);
        Diag.log("acct", "占位页关闭 " + a.getClass().getName());
        try {
            a.finish();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 2.6.44 的日志把弹框抓到了：
     * <pre>
     *   dlg: setMessage: 无法获取数据，请稍后再试。
     *     at ll1.q(UIUtil.java:22)
     *     at SmsBaseActivity.j2(SmsBaseActivity.java:50)
     *     at rm0.F(LoginBySMSEngine.java:212)
     *     at rm0$d.onError(LoginBySMSEngine.java:59)
     *   hms: http … getSMSCodeV3 http=500
     *        body={"errorCode":70001201,"errorDesc":"Parameter[deviceID]is invalid"}
     * </pre>
     * 也就是：不是网络、不是证书、不是取号，是**设备标识**被服务端判成非法。
     * HMS 的 deviceID 来自 DeviceInfoUtil.getRegisterDeviceInfo：
     *   有 SN  -> deviceID = SN（deviceType 8）
     *   没 SN  -> deviceID = UUID（deviceType 6）
     *   非华为机 -> deviceID = unitedId（通常是 IMEI，取不到就退 UUID）
     * 这几种取值都要 READ_PHONE_STATE，容器里被拒就变成空串。
     * 这里把真实取到的值打出来，方便和权限修复的效果对照。
     */
    private static void hookDeviceId(ClassLoader cl) {
        hookStringResult(cl, "com.huawei.hwid.common.util.TerminalInfo", "getSerialNo");
        hookStringResult(cl, "com.huawei.hwid.common.util.TerminalInfo", "getUUid");
        hookStringResult(cl, "com.huawei.hwid.common.util.TerminalInfo", "getUnitedId");
        hookStringResult(cl, "com.huawei.hwid.common.util.TerminalInfo", "getDeviceId");
        try {
            Class<?> t = Class.forName("com.huawei.hwid.common.util.TerminalInfo", false, cl);
            for (Method m : t.getDeclaredMethods()) {
                if (!m.getName().equals("isUDIDSupport")) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Diag.log("dev", "TerminalInfo.isUDIDSupport -> " + param.getResult());
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            Class<?> u = Class.forName("com.huawei.hwid.common.util.DeviceInfoUtil", false, cl);
            for (Method m : u.getDeclaredMethods()) {
                if (!m.getName().equals("getRegisterDeviceInfo")) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object d = param.getResult();
                            if (d == null) {
                                Diag.log("dev", "DeviceInfo=null");
                                return;
                            }
                            String imsi = String.valueOf(call(d, "k"));
                            Diag.log("dev", "DeviceInfo id=" + call(d, "e")
                                    + " id2=" + call(d, "f")
                                    + " id3=" + call(d, "g")
                                    + " idType=" + call(d, "j")
                                    + " imsi=" + (imsi.length() > 3
                                    ? imsi.substring(0, 3) + "***len" + imsi.length() : imsi));
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            Diag.log("dev", "探针 DeviceInfoUtil 已挂");
        } catch (Throwable t2) {
            Diag.log("dev", "探针 DeviceInfoUtil 失败 " + t2);
        }
    }

    private static void hookStringResult(ClassLoader cl, String owner, final String name) {
        try {
            Class<?> c = Class.forName(owner, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            String s = r == null ? "null" : r.toString();
                            if (s.length() > 48) s = s.substring(0, 48) + "…";
                            Diag.log("dev", "TerminalInfo." + name + " -> " + s);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean sAlertHooked;

    /**
     * 最后一道兜底：不管哪个类、哪个 ClassLoader 建的弹框，
     * 只要 setMessage 的文本命中关键字就记下来并打调用栈。
     */
    private static void hookAlertMessage() {
        if (sAlertHooked) return;
        sAlertHooked = true;
        try {
            Method m = android.app.AlertDialog.Builder.class
                    .getMethod("setMessage", CharSequence.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object o = param.args[0];
                    String s = o == null ? "null" : o.toString();
                    if (s.contains("无法获取") || s.contains("请求失败")
                            || s.contains("网络环境") || s.contains("系统时间")) {
                        Diag.log("dlg", "setMessage: " + s + caller());
                    }
                }
            });
            Diag.log("dlg", "探针已挂 AlertDialog.Builder.setMessage");
        } catch (Throwable t) {
            Diag.log("dlg", "探针 setMessage 失败 " + t);
        }
    }

    /**
     * 2.6.43 的日志证明了另一件事：弹框既不走 OneKey 的 H4/w7/t2，
     * 也不走 x9.showErrorDialog —— 那两个点全都挂上了、一次都没触发。
     * x9 里真正建「无法获取数据」弹框的其实是这几条：
     * <pre>
     *   x9.handleRequestFailed(KitActivity, Bundle)
     *     -> handleRequestFailedMore(...) / handleRequestFailedOther(...)
     *          -> UIUtil.createCommonDialog(act, R.string.CS_ERR_for_unable_get_data, 0, z)
     * </pre>
     * 所以这里按名字把 handleRequestFailed* 和 UIUtil.create*Dialog 全部挂上，
     * 不再依赖猜方法签名。
     */
    private static void hookFailedHandlers(ClassLoader cl) {
        try {
            Class<?> x9 = Class.forName(CLS_X9, false, cl);
            int n = hookByName(x9, "handleRequestFailed", "请求失败处理");
            n += hookByName(x9, "handleRequestFailedMore", "请求失败处理More");
            n += hookByName(x9, "handleRequestFailedOther", "请求失败处理Other");
            Diag.log("hms", "探针 handleRequestFailed* 挂了 " + n + " 个");
        } catch (Throwable t) {
            Diag.log("hms", "探针 handleRequestFailed* 失败 " + t);
        }
    }

    /** UIUtil 的弹框工厂：任何「无法获取数据」文案都要从这里过。 */
    private static void hookDialogs(ClassLoader cl) {
        try {
            Class<?> uu = Class.forName("com.huawei.hwid.common.util.UIUtil", false, cl);
            int n = 0;
            for (Method m : uu.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("createCommonDialog") && !name.startsWith("createAlertDialog")
                        && !name.startsWith("createCommonErrorDialog")) {
                    continue;
                }
                final String mn = name;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("dlg", "UIUtil." + mn
                                    + "(" + args(param.args) + ")" + caller());
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Diag.log("dlg", "探针 UIUtil 弹框工厂挂了 " + n + " 个");
        } catch (Throwable t) {
            Diag.log("dlg", "探针 UIUtil 失败 " + t);
        }
    }

    /** 按方法名挂所有重载：参数里有 int 就当错误码/资源 id 打出来。 */
    private static int hookByName(Class<?> owner, final String name, final String note) {
        int n = 0;
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Diag.log("dlg", note + "(" + args(param.args) + ")" + caller());
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 参数摘要：Context 跳过，字符串原样，int 尝试按 Context 解析文案。 */
    private static String args(Object[] a) {
        if (a == null) return "";
        Context ctx = null;
        for (Object o : a) {
            if (o instanceof Context) {
                ctx = (Context) o;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            Object o = a[i];
            if (o == null) {
                sb.append("null");
            } else if (o instanceof Context) {
                sb.append("<ctx>");
            } else if (o instanceof String) {
                String s = (String) o;
                sb.append('"').append(s.length() > 60 ? s.substring(0, 60) + "…" : s).append('"');
            } else if (o instanceof Integer && ctx != null) {
                try {
                    sb.append(ctx.getString((Integer) o));
                } catch (Throwable t) {
                    sb.append(o);
                }
            } else {
                sb.append(String.valueOf(o));
            }
        }
        return sb.toString();
    }

    /**
     * ① 直接看 {@code TerminalInfo.getDeviceMccmnc()} 的返回值。
     * 2.6.42 日志里是 46011，这条本身是好的，保留只为对照。
     */
    private static void hookMccmnc(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_TERMINAL, false, cl);
            Method m = c.getDeclaredMethod("getDeviceMccmnc", Context.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Diag.log("hms", "TerminalInfo.getDeviceMccmnc -> " + param.getResult());
                }
            });
        } catch (Throwable t) {
            Diag.log("hms", "探针 TerminalInfo 失败 " + t);
        }
    }

    /** 运营商取号那三条回调，保留但不再是重点。 */
    private static void hookOneKey(ClassLoader cl) {
        Class<?> act;
        try {
            act = Class.forName(CLS_ONE_KEY, false, cl);
        } catch (Throwable t) {
            return;
        }
        hookNoArg(act, "H4", "一键登录：运营商取号失败（H4）");
        hookBundle(act, "w7", "一键登录：运营商数据校验失败（w7）");
        hookNoArg(act, "t2", "一键登录：t2 请求失败框");
    }

    /** ②③：账号 kit 所有页面的通用错误出口。 */
    private static void hookX9(ClassLoader x9Cl, ClassLoader statusCl) {
        Class<?> x9;
        try {
            x9 = Class.forName(CLS_X9, false, x9Cl);
        } catch (Throwable t) {
            Diag.log("hms", "探针跳过：找不到 x9 " + t);
            return;
        }
        hookDialog(x9, int.class, int.class);
        hookDialog(x9, String.class, int.class);

        try {
            final Method m = x9.getDeclaredMethod("showRequestFailedDialog", Bundle.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Diag.log("hms", "showRequestFailedDialog " + dump(param.args[0]) + caller());
                }
            });
            Diag.log("hms", "探针已挂 showRequestFailedDialog");
        } catch (Throwable t) {
            Diag.log("hms", "探针 showRequestFailedDialog 失败 " + t);
        }

        try {
            Class<?> es = Class.forName(CLS_ERROR_STATUS, false, statusCl);
            final Method m = x9.getDeclaredMethod("dealRequestSucOrFail", Bundle.class, es);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Diag.log("hms", "dealRequestSucOrFail code=" + errCode(param.args[1])
                            + " reason=" + errReason(param.args[1])
                            + " " + dump(param.args[0]) + caller());
                }
            });
            Diag.log("hms", "探针已挂 dealRequestSucOrFail");
        } catch (Throwable t) {
            Diag.log("hms", "探针 dealRequestSucOrFail 失败 " + t);
        }
    }

    private static void hookDialog(Class<?> x9, Class<?> arg1, Class<?> arg2) {
        try {
            final Method m = x9.getDeclaredMethod("showErrorDialog", arg1, arg2);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String text = "";
                    Object a0 = param.args[0];
                    if (a0 instanceof Integer && param.thisObject instanceof Context) {
                        try {
                            text = ((Context) param.thisObject).getString((Integer) a0);
                        } catch (Throwable ignored) {
                        }
                    } else if (a0 instanceof String) {
                        text = (String) a0;
                    }
                    Diag.log("hms", "showErrorDialog(" + a0 + ") 文案=" + text + caller());
                }
            });
            Diag.log("hms", "探针已挂 showErrorDialog(" + arg1.getSimpleName()
                    + "," + arg2.getSimpleName() + ")");
        } catch (Throwable t) {
            Diag.log("hms", "探针 showErrorDialog 失败 " + t);
        }
    }

    /** ④ 真正发请求的那一层：URL + 返回码 + 异常。 */
    private static void hookHttp(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_REST_CALL, false, cl);
            final Method m = c.getDeclaredMethod("execute");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object url = reqUrl(param.thisObject);
                    if (param.getThrowable() != null) {
                        Diag.log("hms", "http ERR url=" + url + " " + param.getThrowable());
                        return;
                    }
                    Object r = param.getResult();
                    Object http = call(r, "getHttpStatusCode");
                    String body = "";
                    if (http instanceof Integer && (Integer) http != 200) {
                        Object c = call(r, "getContentString");
                        String s = String.valueOf(c);
                        body = " body=" + (s.length() > 240 ? s.substring(0, 240) + "…" : s);
                    }
                    Diag.log("hms", "http url=" + url
                            + " http=" + http
                            + " code=" + call(r, "getCode")
                            + " err=" + call(r, "getErrCode")
                            + " subErr=" + call(r, "getSubErrCode")
                            + " ex=" + call(r, "getException")
                            + " msg=" + call(r, "getErrorMsg") + body);
                }
            });
            Diag.log("hms", "探针已挂 HwIDRestClientCall.execute");
        } catch (Throwable t) {
            Diag.log("hms", "探针 HwIDRestClientCall 失败 " + t);
        }
    }

    private static Object reqUrl(Object restCall) {
        try {
            Field f = restCall.getClass().getDeclaredField("mRequest");
            f.setAccessible(true);
            Object req = f.get(restCall);
            return call(req, "getReqUrl");
        } catch (Throwable t) {
            return "?" ;
        }
    }

    private static Object errCode(Object es) {
        return call(es, "c");
    }

    private static Object errReason(Object es) {
        return call(es, "d");
    }

    /** 无参反射调用，失败返回 "?"，绝不抛。 */
    private static Object call(Object o, String name) {
        if (o == null) return "-";
        try {
            Method m = o.getClass().getMethod(name);
            m.setAccessible(true);
            Object v = m.invoke(o);
            if (v == null) return "null";
            return v;
        } catch (Throwable t) {
            return "?" ;
        }
    }

    private static void hookNoArg(Class<?> owner, String name, final String note) {
        try {
            Method m = owner.getDeclaredMethod(name);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Diag.log("hms", note + caller());
                }
            });
        } catch (Throwable t) {
            Diag.log("hms", "探针 " + name + " 失败 " + t);
        }
    }

    private static void hookBundle(Class<?> owner, String name, final String note) {
        try {
            Method m = owner.getDeclaredMethod(name, Bundle.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Diag.log("hms", note + caller() + " " + dump(param.args == null
                            || param.args.length == 0 ? null : param.args[0]));
                }
            });
        } catch (Throwable t) {
            Diag.log("hms", "探针 " + name + " 失败 " + t);
        }
    }

    /** 调用栈只留 guest 侧 8 帧，够看清是哪个 usecase 上来的。 */
    private static String caller() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < st.length && n < 8; i++) {
                String cn = st[i].getClassName();
                if (cn.startsWith("de.robv.") || cn.startsWith("com.hush.")
                        || cn.startsWith("java.lang.Thread")) continue;
                sb.append("\n    at ").append(st[i]);
                n++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= 2.6.76：runtimekit 跨版本 VerifyError =================
    private static boolean sKitChildFirst;
    private static final Map<ClassLoader, Boolean> KIT_LOADERS =
            Collections.synchronizedMap(new java.util.WeakHashMap<ClassLoader, Boolean>());

    /**
     * kit 的 DexClassLoader 父加载器是宿主的 PathClassLoader（10 个 dex），
     * 里面有一份 base.apk 自带的 runtimekit（6.16）；kit 自己的 dex 里是应用市场
     * 下发的 runtimekit（6.20，/files/kits/com.huawei.hms.runtimekit/62000300/）。
     *
     * <p>父优先加载会把 6.16 的 {@code KitContext} / {@code NonCrashKitContext}
     * 先拿到，6.20 的子类一继承就 {@code VerifyError: Rejecting class ...}，
     * 然后账号 kit 的 {@code x9}、{@code ak0}、{@code HuaweiIDForSettingsActivity}
     * 全部连锁失败 —— 2.6.75 日志里那一大串就是这个。</p>
     *
     * <p>这里只对「dex 路径含 /kits/」的 ClassLoader 生效，对
     * {@code com.huawei.hms.runtimekit.*} 强制走自己的 dex（子优先）。</p>
     */
    public static void installKitChildFirst() {
        if (sKitChildFirst) return;
        sKitChildFirst = true;
        try {
            final Method m = ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String n = (String) param.args[0];
                        if (n == null || !n.startsWith("com.huawei.hms.runtimekit.")) return;
                        ClassLoader cl = (ClassLoader) param.thisObject;
                        if (cl == null || !isKitLoader(cl)) return;
                        Class<?> c = findInOwnDex(cl, n);
                        if (c != null) {
                            Diag.log("kit", "子优先 " + n + " <- " + sourceOf(c));
                            param.setResult(c);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Diag.log("kit", "kit CL runtimekit 子优先已挂");
        } catch (Throwable t) {
            Diag.log("kit", "子优先挂载失败 " + t);
        }
    }

    private static boolean isKitLoader(ClassLoader cl) {
        Boolean c = KIT_LOADERS.get(cl);
        if (c != null) return c;
        boolean kit = false;
        try {
            Object pathList = pathListOf(cl);
            Object[] els = (Object[]) readField(pathList, "dexElements");
            if (els != null) {
                for (Object e : els) {
                    String p = (String) readField(e, "path");
                    if (p != null && p.contains("/kits/")) {
                        kit = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        KIT_LOADERS.put(cl, kit);
        return kit;
    }

    private static Object pathListOf(ClassLoader cl) throws Throwable {
        return readField(cl, "pathList");
    }

    private static Object readField(Object target, String name) throws Throwable {
        if (target == null) return null;
        Class<?> k = target.getClass();
        while (k != null) {
            for (Field f : k.getDeclaredFields()) {
                if (name.equals(f.getName())) {
                    f.setAccessible(true);
                    return f.get(target);
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    /** 只查自己 dex 里的定义（BaseDexClassLoader.pathList 的 findClass），不走父加载器。 */
    private static Class<?> findInOwnDex(ClassLoader cl, String name) {
        try {
            Object pathList = pathListOf(cl);
            if (pathList == null) return null;
            Method find = null;
            for (Method m : pathList.getClass().getDeclaredMethods()) {
                if (!"findClass".equals(m.getName())
                        || m.getParameterTypes().length < 1
                        || m.getParameterTypes()[0] != String.class) {
                    continue;
                }
                find = m;
                break;
            }
            if (find == null) return null;
            find.setAccessible(true);
            Object r;
            if (find.getParameterTypes().length >= 2) {
                r = find.invoke(pathList, name, java.util.Collections.emptyList());
            } else {
                r = find.invoke(pathList, name);
            }
            return r instanceof Class ? (Class<?>) r : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这个类是从哪个 apk 装进来的，一眼看出是不是 6.16/6.20 串了。 */
    public static String sourceOf(Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return "boot";
            String p = cs.getLocation().getPath();
            int i = p.indexOf("/kits/");
            return i > 0 ? p.substring(i) : p;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 把 runtimekit 关键类的来源和真实校验错误打出来（含完整 cause 链）。 */
    public static void probeRuntimeClasses(ClassLoader cl) {
        String[] names = {
                "com.huawei.hms.runtimekit.container.kitsdk.KitContext",
                "com.huawei.hms.runtimekit.container.kitsdk.NonCrashKitContext",
                "com.huawei.hms.runtimekit.container.kitsdk.KitActivity",
                "com.huawei.hms.runtimekit.container.kitsdk.KitApplication",
        };
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                Diag.log("kit", "类来源 " + n + " <- " + sourceOf(c));
            } catch (Throwable t) {
                StringBuilder sb = new StringBuilder("类加载失败 " + n + " -> " + t);
                Throwable cur = t.getCause();
                int depth = 0;
                while (cur != null && depth < 6) {
                    sb.append(" | cause=").append(cur);
                    cur = cur.getCause();
                    depth++;
                }
                Diag.log("kit", sb.toString());
            }
        }
    }

    // ================= 2.6.75：HMS Core 服务进程内的调用方校验 / OOBE 放行 =================
    private static boolean sHmsCoreBypass;

    /**
     * 只装在 hwid / kit 进程里的探针（普通 App 进程不要装，避免动到 Activity）。
     *
     * <p>反编译 hwid base 6.16.4（com.huawei.hms.core.common.message.a#y）看到的请求入口：</p>
     * <pre>
     *   asyncCall(DataBuffer, IAIDLCallback):
     *     Q1.l.f(header.packageName)  —— 用 Binder.getCallingUid() 反查包名
     *     com.huawei.hms.fwkcom.utils.v.b() —— OOBE 协议是否已同意
     *     任一失败 -> 直接 failure(907135000 / 907135007)，不进入业务
     * </pre>
     *
     * <p>容器里所有 guest 共用宿主 UID，getCallingUid() 是 com.hush.space 的 UID，
     * getPackagesForUid 里只有 com.hush.space，所以 SDK 传来的
     * com.netease.sky.huawei 永远对不上；非华为设备上 Settings.Global 里也没有
     * oobe_statement_agreed。这两道门一拦，第三方 App 调 HMS SDK 就是"发出去没回应"。</p>
     */
    public static void installHmsCore(ClassLoader cl) {
        if (cl == null || sHmsCoreBypass) return;
        sHmsCoreBypass = true;
        Diag.log("hms", "HMS 服务进程探针 CL=" + System.identityHashCode(cl));
        hookCallerPackage(cl);
        hookNetworkControl(cl);
        hookAidlFailure(cl);
        hookForegroundBus(cl);
        hookUnlockGate(cl);
        hookGameApi(cl);
        hookAlertMessage();
        hookServerSignIn(cl);
        hookJosSignIn(cl);
        hookWebAuth(cl);
        hookResultRelay(cl);
        hookBridgeRelay(cl);
    }

    /**
     * 2.6.92：跨进程把结果送到游戏进程的 BridgeActivity。
     *
     * <p>2.6.89 日志（23:45:10）里，中转页 UiJumpActivity 收到结果后正常销毁了，
     * 但游戏进程 p0 的 {@code com.huawei.hms.activity.BridgeActivity}
     * 从 23:44:15 pause 之后一直没有 resume、也没有 onActivityResult，
     * 直到 23:45:54 被销毁 —— 容器不会把 kit 进程页面的结果回传给游戏进程，
     * 所以登录页无论成功还是失败，游戏端都收不到。</p>
     *
     * <p>这里不再依赖容器的 Activity 结果回传：p8 把结果写进宿主真实的 files 目录，
     * p0 的 BridgeActivity 自己轮询取走，{@code setResult + finish}，
     * 游戏 SDK 就能在同进程里拿到结果。</p>
     */
    private static final String HOST_FILES = "/data/data/com.hush.space/files";
    private static final Set<ClassLoader> BRIDGE_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    private static boolean sBridgeDelivered;

    private static void hookBridgeRelay(final ClassLoader cl) {
        if (cl == null || !BRIDGE_DONE.add(cl)) return;
        final Class<?> bc;
        try {
            bc = Class.forName("com.huawei.hms.activity.BridgeActivity", false, cl);
        } catch (Throwable ignored) {
            return;
        }
        try {
            for (final Method m : bc.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if ("onCreate".equals(m.getName()) && pt.length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final Object act = param.thisObject;
                            Diag.log("game", "游戏跳板页开始等待登录结果");
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(new Runnable() {
                                        int tick = 0;

                                        @Override
                                        public void run() {
                                            tick++;
                                            if (deliverToBridge(act) || tick > 180) return;
                                            new android.os.Handler(android.os.Looper
                                                    .getMainLooper()).postDelayed(this, 1000);
                                        }
                                    }, 500);
                        }
                    });
                } else if ("onActivityResult".equals(m.getName()) && pt.length == 3) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "游戏跳板页收到结果 result=" + param.args[1]
                                    + " 原流程继续");
                        }
                    });
                }
            }
            Diag.log("game", "游戏跳板页结果探针已挂");
        } catch (Throwable t) {
            Diag.log("game", "游戏跳板页结果探针失败 " + t);
        }
    }

    private static java.io.File bridgeFile() {
        java.io.File d = new java.io.File(HOST_FILES);
        if (!d.isDirectory()) d = new java.io.File("/data/user/0/com.hush.space/files");
        if (!d.isDirectory()) return null;
        return new java.io.File(d, "hms_login_result.bin");
    }

    /** p8 侧：把结果落盘，供 p0 取走。 */
    private static void writeCrossProcessResult(int code, android.content.Intent data) {
        try {
            java.io.File f = bridgeFile();
            if (f == null) {
                Diag.log("game", "跨进程结果没有可写目录");
                return;
            }
            Bundle wrap = new Bundle();
            wrap.putInt("code", code);
            wrap.putParcelable("data", data);
            android.os.Parcel p = android.os.Parcel.obtain();
            p.writeBundle(wrap);
            byte[] bytes = p.marshall();
            p.recycle();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(bytes);
            out.close();
            Diag.log("game", "跨进程结果已落盘 code=" + code + " bytes=" + bytes.length);
        } catch (Throwable t) {
            Diag.log("game", "跨进程结果落盘失败 " + causeChain(t));
        }
    }

    /** p0 侧：取走落盘结果并直接收尾 BridgeActivity。 */
    private static boolean deliverToBridge(Object o) {
        if (sBridgeDelivered || !(o instanceof android.app.Activity)) return false;
        android.app.Activity a = (android.app.Activity) o;
        if (a.isFinishing()) return false;
        java.io.File f = bridgeFile();
        if (f == null || !f.exists()) return false;
        if (System.currentTimeMillis() - f.lastModified() > 30000L) {
            f.delete();
            return false;
        }
        try {
            byte[] bytes = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = 0;
            while (n < bytes.length) {
                int r = in.read(bytes, n, bytes.length - n);
                if (r <= 0) break;
                n += r;
            }
            in.close();
            android.os.Parcel p = android.os.Parcel.obtain();
            p.unmarshall(bytes, 0, n);
            p.setDataPosition(0);
            Bundle wrap = p.readBundle(a.getClass().getClassLoader());
            p.recycle();
            if (wrap == null) return false;
            int code = wrap.getInt("code", -1);
            android.content.Intent data =
                    (android.content.Intent) wrap.getParcelable("data");
            if (data == null) return false;
            sBridgeDelivered = true;
            f.delete();
            a.setResult(code, data);
            a.finish();
            Diag.log("game", "跨进程结果已送达游戏跳板页 code=" + code);
            return true;
        } catch (Throwable t) {
            Diag.log("game", "跨进程结果读取失败 " + causeChain(t));
            return false;
        }
    }

    /**
     * 2.6.85：容器里的「手机还锁着」误判。
     *
     * <p>2.6.84 日志（22:21:57 之后）里游戏每 5 秒调一次
     * {@code account.silentSignIn}，一直拿不到结果，界面就永远转圈。
     * 服务端（account kit）{@code BaseSignInRequest.onRequest} 第一行是：</p>
     * <pre>
     *   if (ui.l(gm.b().a())) {  // ui.l = UserManager.isUserUnlocked()
     *       retCode = 2005; "user still not unlock screen"
     *       return;              // 根本不走登录
     *   }
     * </pre>
     * <p>同时 {@code HwIDMemCache.initMemCache()} 第一行也用
     * {@code PropertyUtils.isPhoneStillInLockMode()} 做同样的判断，返回 true 就直接
     * return，什么都不初始化。两者都把结果静态缓存住，一旦第一次判成「锁屏」，
     * 整个进程后续永远认为锁屏。</p>
     *
     * <p>容器里 guest 共用宿主 UID，hwid 进程拿到的 Context/UserManager 是容器包出来的，
     * {@code isUserUnlocked()} 会返回 false。用户明明正在前台玩游戏，这里却判成锁屏，
     * 于是 {@code ui.l}、{@code PropertyUtils}、客户端 SDK 的
     * {@code account.sdk.util.e.a()}、hwid 的 {@code t0.z0.a()} 全部走「锁屏」分支。
     * 四个入口统一改成「已解锁」，并只在原值为 true 时打日志。</p>
     */
    private static final Set<ClassLoader> UNLOCK_GATE_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    private static int sUnlockGateLogs;

    private static void hookUnlockGate(final ClassLoader cl) {
        if (cl == null || !UNLOCK_GATE_DONE.add(cl)) return;
        hookUnlockMethod(cl, "com.huawei.hwid.common.util.PropertyUtils",
                "isPhoneStillInLockMode", android.content.Context.class, "PropertyUtils.isPhoneStillInLockMode");
        hookUnlockMethod(cl, "ui", "l",
                android.content.Context.class, "ui.l");
        hookUnlockMethod(cl, "com.huawei.hms.account.sdk.util.e",
                "a", null, "account.sdk.util.e.a");
        hookUnlockMethod(cl, "t0.z0", "a", null, "UserManagerPro.a");
    }

    /**
     * 把「是否锁屏」的判断强制改成 false（=已解锁）。
     *
     * @param paramType 有参数时要求第一个参数的类型；null 表示无参
     */
    private static void hookUnlockMethod(final ClassLoader cl, final String clsName,
                                         final String methodName, final Class<?> paramType,
                                         final String label) {
        try {
            final Class<?> cls = Class.forName(clsName, false, cl);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                int need = paramType == null ? 0 : 1;
                if (pt.length != need) continue;
                if (paramType != null && pt[0] != paramType) continue;
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        boolean origin = Boolean.TRUE.equals(r);
                        if (origin && sUnlockGateLogs < 10) {
                            sUnlockGateLogs++;
                            Diag.log("hms", "锁屏门 " + label + " 原值=true，按已解锁放行");
                        }
                        param.setResult(Boolean.FALSE);
                    }
                });
                Diag.log("hms", "锁屏门已挂 " + label);
            }
        } catch (Throwable ignored) {
            // 该 CL 里没有这个类，跳过
        }
    }

    /** 2.6.81：HMS 的 ForegroundBus 路由（游戏登录弹不出来就卡在这里）。 */
    private static boolean sForegroundBusHooked;
    private static final Map<Object, Boolean> SIGNIN_STARTED =
            Collections.synchronizedMap(new java.util.WeakHashMap<Object, Boolean>());

    /**
     * 2.6.80 日志里的关键事实：20:53 之后游戏每 7 秒调一次
     * {@code com.huawei.hms.jos.signIn}，每次都是
     * <pre>
     *   HwIdSignInHubActivity（游戏进程）→ BridgeActivity → UiJumpActivity（hwid 进程）
     *   → ForegroundBus$c.run → finish（200~400ms 后整条栈退掉）
     * </pre>
     * 也就是 ForegroundBus 的路由解析 {@code C()} 失败，直接
     * {@code B(907135001, "action configure is invalid")} 收场，游戏等不到登录结果，
     * 于是一直转圈并反复重试。
     *
     * <p>失败点在 {@code C()}：它拿 {@code RequestHeaderForJson.getOriginApiName()}
     * （日志里是 {@code com.huawei.hms.jos.signIn}）去
     * {@code queryIntentActivities}，而 game kit 清单里声明的却是
     * {@code com.huawei.hms.hwidjos.signIn}；容器里 kit 又是独立安装的包，
     * hwid 自己的 PM 查不到它，KMS 也拿不到 ResolveInfo。</p>
     *
     * <p>但 runtimekit 本来就有一条「kit 组件 → 跳板页 + session」的通道：
     * {@code RuntimeActivityThread.getActivityManager().getActivityComponent(...)}
     * （{@code KitContext.toActivityContainerIntent()} 用的就是它）。
     * 账号中心页在 20:53:56 能正常弹出来，走的正是这条通道。这里在
     * {@code ForegroundBus.w()} 之前直接调用它，把游戏 kit 的
     * {@code JosSignInActivity} 换成跳板页，并用 ForegroundBus 约定的
     * requestCode 431057 启动；用户登录完，结果会回到
     * {@code UiJumpActivity.onActivityResult → ForegroundBus.d → V()}，
     * SDK 就能拿到登录结果。</p>
     */
    private static void hookForegroundBus(ClassLoader cl) {
        if (sForegroundBusHooked) return;
        final Class<?> bus;
        try {
            bus = Class.forName("com.huawei.hms.core.activity.b", false, cl);
        } catch (Throwable ignored) {
            return;
        }
        sForegroundBusHooked = true;
        try {
            for (final Method m : bus.getDeclaredMethods()) {
                String n = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if ("w".equals(n) && pt.length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String api = apiOf(param.thisObject);
                                Diag.log("game", "ForegroundBus 开始路由 api=" + api);
                                if (!isJosSignIn(api)) return;
                                if (signInFallback(param.thisObject, api)) {
                                    Diag.log("game", "直连游戏 kit 登录页成功，跳过 ForegroundBus 路由");
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Diag.log("game", "游戏登录页直连异常 " + t);
                            }
                        }
                    });
                } else if ("C".equals(n) && pt.length == 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Diag.log("game", "ForegroundBus 路由解析 -> " + param.getResult()
                                    + " api=" + apiOf(param.thisObject));
                        }
                    });
                } else if ("B".equals(n) && pt.length == 2
                        && pt[0] == int.class && pt[1] == String.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String msg = String.valueOf(param.args[1]);
                                Diag.log("game", "ForegroundBus 放弃 code=" + param.args[0]
                                        + " msg=" + msg + " api=" + apiOf(param.thisObject));
                                // 2.6.82：apiOf 拿不到 header 时，这里用错误串里的 api 名兜底。
                                if (!msg.contains("jos.signIn")) return;
                                if (signInFallback(param.thisObject, msg)) {
                                    Diag.log("game", "已拦下失败返回，改为直连游戏登录页");
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Diag.log("game", "B 兜底异常 " + t);
                            }
                        }
                    });
                }
            }
            Diag.log("game", "ForegroundBus 探针已挂");
        } catch (Throwable t) {
            Diag.log("game", "ForegroundBus 探针挂载失败 " + t);
        }
        scheduleKamsSelfTest(cl);
    }

    /**
     * 2.6.83：启动 3 秒后自检 kams 能否把游戏 kit 的登录页换成跳板页。
     * 只调用 {@code getActivityComponent}，不启动页面，不会打扰用户。
     */
    private static void scheduleKamsSelfTest(final ClassLoader cl) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Class<?> rat = Class.forName(
                                        "com.huawei.hms.kit.app.RuntimeActivityThread", false, cl);
                                Object inst = rat.getMethod("getInstance").invoke(null);
                                Object am = invokeNoArg(inst, "getActivityManager");
                                Diag.log("game", "kams 自检 getActivityManager="
                                        + (am == null ? "null" : "ok"));
                                if (am == null) return;
                                Object r = invoke(am, "getActivityComponent",
                                        new android.content.ComponentName("com.huawei.hms.game",
                                                "com.huawei.hms.game.jos.api.account.JosSignInActivity"),
                                        0, "com.huawei.hms.jos.signIn");
                                if (r instanceof android.content.Intent) {
                                    android.content.Intent it = (android.content.Intent) r;
                                    Diag.log("game", "kams 自检 跳板=" + it.getComponent()
                                            + " action=" + it.getAction()
                                            + " extras=" + it.getExtras());
                                } else {
                                    Diag.log("game", "kams 自检 返回=" + r);
                                }
                            } catch (Throwable t) {
                                Diag.log("game", "kams 自检失败 " + causeChain(t));
                            }
                        }
                    }, 3000);
        } catch (Throwable t) {
            Diag.log("game", "kams 自检没排上 " + t);
        }
    }

    private static String apiOf(Object bus) {
        if (bus == null) return null;
        Object header = null;
        try {
            ClassLoader cl = bus.getClass().getClassLoader();
            Class<?> hc = Class.forName(
                    "com.huawei.hms.core.common.message.RequestHeaderForJson", true, cl);
            for (Field f : bus.getClass().getDeclaredFields()) {
                if (!hc.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    header = f.get(bus);
                } catch (Throwable ignored) {
                }
                break;
            }
        } catch (Throwable t) {
            Diag.log("game", "api 字段定位失败 " + t);
            return null;
        }
        if (header == null) return null;
        try {
            Object api = invokeNoArg(header, "getOriginApiName");
            return api == null ? null : api.toString();
        } catch (Throwable t) {
            Diag.log("game", "api 读取失败 " + causeChain(t));
            return null;
        }
    }

    private static boolean isJosSignIn(String api) {
        if (api == null) return false;
        return api.contains("jos.signIn");
    }

    /** 拉起游戏 kit 登录页（同一个跳板页只拉一次）。 */
    private static boolean signInFallback(Object bus, String api) {
        Object act = activityOf(bus);
        if (act == null) {
            Diag.log("game", "跳板页还没创建，直连放弃");
            return false;
        }
        if (SIGNIN_STARTED.containsKey(act)) {
            Diag.log("game", "游戏登录页已拉起过，不重复");
            return false;
        }
        if (!startKitSignIn(act)) return false;
        SIGNIN_STARTED.put(act, Boolean.TRUE);
        return true;
    }

    /** 按字段类型找 ForegroundBus 手里的跳板页（字段名是混淆的，不能写死）。 */
    private static Object activityOf(Object bus) {
        if (bus == null) return null;
        for (Class<?> k = bus.getClass(); k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!android.app.Activity.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(bus);
                    if (v instanceof android.app.Activity) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 走 runtimekit 的 kit 组件通道：kams 登记 session，返回一跳板页 Intent。
     */
    private static boolean startKitSignIn(Object activity) {
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            Class<?> rat = Class.forName("com.huawei.hms.kit.app.RuntimeActivityThread", false, cl);
            Object inst = rat.getMethod("getInstance").invoke(null);
            Object am = invokeNoArg(inst, "getActivityManager");
            if (am == null) {
                Diag.log("game", "kams 还没初始化，直连放弃");
                return false;
            }
            android.content.ComponentName target = new android.content.ComponentName(
                    "com.huawei.hms.game",
                    "com.huawei.hms.game.jos.api.account.JosSignInActivity");
            Object r = invoke(am, "getActivityComponent", target, 0,
                    "com.huawei.hms.jos.signIn");
            if (!(r instanceof android.content.Intent)) {
                Diag.log("game", "kams 没有返回跳板页 " + r);
                return false;
            }
            android.content.Intent it = (android.content.Intent) r;
            copyForegroundExtras((android.app.Activity) activity, it);
            Diag.log("game", "kams 跳板 " + it.getComponent() + " action="
                    + it.getAction() + " extras=" + it.getExtras());
            android.app.Activity.class.getMethod("startActivityForResult",
                    android.content.Intent.class, int.class)
                    .invoke(activity, it, 431057);
            return true;
        } catch (Throwable t) {
            Diag.log("game", "kams 通道失败 " + causeChain(t));
            return false;
        }
    }

    /**
     * 2.6.84：把 UiJumpActivity 上原始 SDK 请求的 header/body 带进跳板页 Intent。
     *
     * <p>2.6.83 的日志证明 kams 通道和工作：跳板页起来了、game kit 的
     * {@code JosSignInActivity} 也被加载了，但 onCreate 里
     * {@code o(o90(getIntent().getExtras()))} 直接失败，因为我们的 Intent 只有
     * {@code stub_session_id}。它需要 {@code HMS_FOREGROUND_REQ_HEADER} 和
     * {@code HMS_FOREGROUND_REQ_BODY} 才能从 header 解析 appId、从 body 解析
     * scope，缺了就走 {@code H(INTERNAL_ERROR)} 立刻 finish。</p>
     */
    private static void copyForegroundExtras(android.app.Activity from,
                                             android.content.Intent to) {
        try {
            android.content.Intent src = from.getIntent();
            if (src == null) return;
            for (String k : new String[]{"HMS_FOREGROUND_REQ_HEADER",
                    "HMS_FOREGROUND_REQ_BODY"}) {
                String v = src.getStringExtra(k);
                if (v != null) to.putExtra(k, v);
            }
            to.putExtra("intent.extra.isfullscreen",
                    src.getBooleanExtra("intent.extra.isfullscreen", false));
            Diag.log("game", "已带上 SDK 请求 header="
                    + (src.getStringExtra("HMS_FOREGROUND_REQ_HEADER") != null)
                    + " body=" + (src.getStringExtra("HMS_FOREGROUND_REQ_BODY") != null));
        } catch (Throwable t) {
            Diag.log("game", "复制 SDK 请求内容失败 " + t);
        }
    }

    // ================= 2.6.86：登录流程全链路日志 =================
    private static final Set<ClassLoader> CLIENT_FLOW_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    private static final Set<ClassLoader> SERVER_SIGNIN_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    private static final Set<ClassLoader> JOS_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    private static final Set<ClassLoader> WEB_AUTH_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    private static boolean sIntentLogHooked;

    /**
     * 2.6.86：客户端（游戏进程）这一侧到底在点什么页面。
     *
     * <p>2.6.85 的日志只覆盖到游戏启动后 10 秒，HWID 进程还没起来，
     * 看不到登录结果。用户描述「竖屏出现 HMS Core 的圈圈又回到游戏」，
     * 既可能是容器内 hwid 进程里的账号页，也可能是被 Hush 路由到了容器外
     * 真正的 HMS Core。这里把任何发往华为/HMS 组件的 Activity 启动都记下来
     * （只记包名/组件/action，不记业务参数），再挂上游戏自带的
     * {@code HwIdSignInHubActivity} 生命周期和结果码，下一次日志就能分辨。</p>
     */
    private static void hookSignInFlow(final ClassLoader cl) {
        if (cl == null || !CLIENT_FLOW_DONE.add(cl)) return;
        if (!sIntentLogHooked) {
            sIntentLogHooked = true;
            try {
                XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                        "startActivityForResult", android.content.Intent.class, int.class),
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                logHuaweiIntent("startActivityForResult",
                                        (android.content.Intent) param.args[0]);
                            }
                        });
                XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                        "startActivity", android.content.Intent.class),
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                logHuaweiIntent("startActivity",
                                        (android.content.Intent) param.args[0]);
                            }
                        });
                Diag.log("game", "华为页面启动日志已挂");
            } catch (Throwable t) {
                Diag.log("game", "华为页面启动日志挂载失败 " + t);
            }
        }
        hookHubActivity(cl);
    }

    private static void logHuaweiIntent(String from, android.content.Intent it) {
        if (it == null) return;
        try {
            android.content.ComponentName cmp = it.getComponent();
            String pkg = it.getPackage();
            String action = it.getAction();
            String cn = cmp == null ? null : cmp.getClassName();
            String cpkg = cmp == null ? null : cmp.getPackageName();
            boolean hit = false;
            if (cpkg != null && cpkg.startsWith("com.huawei.")) hit = true;
            if (cn != null && (cn.startsWith("com.huawei.") || cn.contains("hwid"))) hit = true;
            if (pkg != null && pkg.startsWith("com.huawei.")) hit = true;
            if (action != null && (action.contains("hwid") || action.contains("huawei")
                    || action.contains("hms"))) hit = true;
            if (!hit) return;
            StringBuilder sb = new StringBuilder("华为页启动 ").append(from)
                    .append(" cmp=").append(cmp)
                    .append(" pkg=").append(pkg)
                    .append(" action=").append(action);
            try {
                android.os.Bundle ex = it.getExtras();
                if (ex != null) sb.append(" extras=").append(ex.keySet());
            } catch (Throwable ignored) {
            }
            Diag.log("game", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 游戏包里自带的登录跳板页，结果码最终回到它这里。 */
    private static void hookHubActivity(ClassLoader cl) {
        final Class<?> hub;
        try {
            hub = Class.forName(
                    "com.huawei.hms.hwid.internal.ui.activity.HwIdSignInHubActivity", false, cl);
        } catch (Throwable ignored) {
            return;
        }
        try {
            for (final Method m : hub.getDeclaredMethods()) {
                String n = m.getName();
                int c = m.getParameterTypes().length;
                if (("onCreate".equals(n) || "onResume".equals(n) || "onDestroy".equals(n)
                        || "finish".equals(n)) && (c == 0 || c == 1)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "登录跳板页 " + m.getName() + " " + describeAct(param.thisObject));
                        }
                    });
                } else if ("onActivityResult".equals(n) && c == 3) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "登录跳板页 结果 request=" + param.args[0]
                                    + " result=" + param.args[1]
                                    + " data=" + describeIntent((android.content.Intent) param.args[2]));
                        }
                    });
                }
            }
            Diag.log("game", "登录跳板页探针已挂");
        } catch (Throwable t) {
            Diag.log("game", "登录跳板页探针失败 " + t);
        }
    }

    private static String describeAct(Object act) {
        try {
            android.app.Activity a = (android.app.Activity) act;
            return "finishing=" + a.isFinishing()
                    + " extras=" + (a.getIntent() == null ? "null" : a.getIntent().getExtras());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describeIntent(android.content.Intent it) {
        if (it == null) return "null";
        try {
            StringBuilder sb = new StringBuilder("{");
            android.os.Bundle ex = it.getExtras();
            if (ex != null) sb.append(ex.keySet());
            sb.append(" header=").append(it.getStringExtra("json_header"));
            return sb.append('}').toString();
        } catch (Throwable t) {
            return "{?}";
        }
    }

    /**
     * 2.6.86：服务端 silentSignIn 到底回了什么。
     *
     * <p>{@code BaseSignInRequest.onRequest} 是账号 kit 的登录入口，
     * {@code CompatibleAIDLRequestUtil.responseAidlCall} 是所有登录响应的唯一出口。
     * 把入口和出口都记下来，日志里就能看到 retCode（2001=需要拉起登录页，
     * 2005=被判锁屏，0=静默成功，其余=失败），不用再猜客户端为什么一直重试。</p>
     */
    private static void hookServerSignIn(final ClassLoader cl) {
        if (cl == null || !SERVER_SIGNIN_DONE.add(cl)) return;
        try {
            final Class<?> b = Class.forName("com.huawei.hms.hwid.api.BaseSignInRequest", false, cl);
            for (final Method m : b.getDeclaredMethods()) {
                if (!"onRequest".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object req = param.args[0];
                            Diag.log("hms", "登录入口 onRequest req="
                                    + (req == null ? "null" : req.getClass().getSimpleName())
                                    + " 参数=" + (req instanceof Bundle
                                    ? ((Bundle) req).keySet() : ""));
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() != null) {
                            Diag.log("hms", "登录入口抛异常 " + causeChain(param.getThrowable()));
                        }
                    }
                });
            }
            Diag.log("hms", "登录入口探针已挂");
        } catch (Throwable t) {
            Diag.log("hms", "登录入口探针跳过 " + t);
        }
        try {
            final Class<?> u = Class.forName(
                    "com.huawei.hms.hwid.api.CompatibleAIDLRequestUtil", false, cl);
            for (final Method m : u.getDeclaredMethods()) {
                if (!"responseAidlCall".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object entity = param.args.length > 1 ? param.args[1] : null;
                            Object status = param.args.length > 2 ? param.args[2] : null;
                            Diag.log("hms", "登录响应 retCode=" + call(entity, "getRetCode")
                                    + " status=" + String.valueOf(status)
                                    + (entity == null ? "" : " entity=" + entity.getClass().getSimpleName()));
                        } catch (Throwable t) {
                            Diag.log("hms", "登录响应读取失败 " + t);
                        }
                    }
                });
            }
            Diag.log("hms", "登录响应探针已挂");
        } catch (Throwable t) {
            Diag.log("hms", "登录响应探针跳过 " + t);
        }
    }

    /** 2.6.86：game kit 登录页自己为什么秒退。 */
    private static void hookJosSignIn(final ClassLoader cl) {
        if (cl == null || !JOS_DONE.add(cl)) return;
        final Class<?> jos;
        try {
            jos = Class.forName(
                    "com.huawei.hms.game.jos.api.account.JosSignInActivity", false, cl);
        } catch (Throwable ignored) {
            return;
        }
        try {
            for (final Method m : jos.getDeclaredMethods()) {
                String n = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if ("o".equals(n) && pt.length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "jos 登录页解析请求 参数="
                                    + (param.args[0] instanceof Bundle
                                    ? ((Bundle) param.args[0]).keySet() : param.args[0]));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null) {
                                Diag.log("game", "jos 登录页解析失败 " + causeChain(param.getThrowable()));
                            } else {
                                Diag.log("game", "jos 登录页解析结果=" + param.getResult());
                            }
                        }
                    });
                } else if ("H".equals(n) && pt.length == 1 && pt[0] == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "jos 登录页失败收尾 code=" + param.args[0]);
                        }
                    });
                } else if ("r".equals(n) && pt.length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "jos 登录页收尾 finish");
                        }
                    });
                } else if ("onCreate".equals(n) && pt.length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sJosPage = param.thisObject;
                            sJosGoodIntent = null;
                            Diag.log("game", "jos 登录页已创建 " + describeAct(param.thisObject));
                        }
                    });
                } else if ("onActivityResult".equals(n) && pt.length == 3) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.content.Intent data =
                                    (android.content.Intent) param.args[2];
                            if (param.args[1] != null && (Integer) param.args[1] == -1
                                    && data != null
                                    && data.hasExtra("HUAWEIID_SIGNIN_RESULT")) {
                                sJosGoodIntent = data;
                                scheduleJosForce(param.thisObject, data);
                            }
                            Diag.log("game", "jos 登录页子页结果 request=" + param.args[0]
                                    + " result=" + param.args[1]
                                    + " data=" + describeIntent(data));
                        }
                    });
                }
            }
            Diag.log("game", "jos 登录页探针已挂");
        } catch (Throwable t) {
            Diag.log("game", "jos 登录页探针失败 " + t);
        }
        hookJosGate(cl);
        hookJosExecutors(cl);
        hookJosBridgeFallback(cl);
    }

    /**
     * 2.6.90：桥接失败时用已经拿到手的账号结果直接完成登录。
     *
     * <p>2.6.89 日志把最后一步钉死了：23:44:24.592 华为账号页已经返回 RESULT_OK，
     * 结果里带 {@code HUAWEIID_SIGNIN_RESULT}（真实账号数据）；jos 页也收到了。
     * 但 jos 页接下来要经过 {@code c3 AuthorizedLoginUiTask} 的 AccountKit 内层调用，
     * 这次调用在 p8 里根本没有发出去（服务端 45 秒里一条 account 请求都没收到），
     * 45 秒后任务以 {@code -1 INTERNAL_ERROR} 收尾，页面 {@code H(-1)} 关闭，
     * 游戏端永远等不到成功结果 —— 这就是最后一圈。</p>
     *
     * <p>既然账号登录本身已经成功，这里就不再等那条坏掉的跨 kit 调用：
     * 一旦 {@code c3.o(i, responseEntity)} 收到 null 响应，就用 jos 页自己的
     * 成功出口 {@code w(intent)}（内部会 {@code n()} 加 {@code HMS_FOREGROUND_RES_HEADER}、
     * {@code setResult(-1)}、finish）把这份真实结果交回去。</p>
     */
    private static volatile Object sJosPage;
    private static volatile android.content.Intent sJosGoodIntent;
    private static final Map<Object, Boolean> JOS_FORCED =
            Collections.synchronizedMap(new java.util.WeakHashMap<Object, Boolean>());

    private static void hookJosBridgeFallback(final ClassLoader cl) {
        final Class<?> c3;
        try {
            c3 = Class.forName("com.huawei.hms.game.c3", false, cl);
        } catch (Throwable ignored) {
            return;
        }
        try {
            for (final Method m : c3.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (!"o".equals(m.getName()) || pt.length != 2 || pt[0] != int.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object page = sJosPage;
                        android.content.Intent good = sJosGoodIntent;
                        if (param.args[1] != null || page == null || good == null) return;
                        if (JOS_FORCED.put(page, Boolean.TRUE) != null) return;
                        param.setResult(null);
                        Diag.log("game", "账号桥接失败(" + param.args[0]
                                + ")，用已有账号结果直接完成登录");
                        forceJosSuccess(page, good);
                    }
                });
            }
            Diag.log("game", "账号桥接兜底已挂");
        } catch (Throwable t) {
            Diag.log("game", "账号桥接兜底失败 " + t);
        }
    }

    private static void forceJosSuccess(final Object page, final android.content.Intent data) {
        scheduleJosForce(page, data);
    }

    /**
     * 2.6.91：账号结果到手后 2.5 秒就收尾，不再等那条 45 秒才超时的跨 kit 调用。
     * 如果页面自己已经正常成功结束，isFinishing 检查会让这里自动不介入。
     */
    private static void scheduleJosForce(final Object page,
                                         final android.content.Intent data) {
        if (page == null || data == null) return;
        if (JOS_FORCED.put(page, Boolean.TRUE) != null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Object finishing = invokeNoArg(page, "isFinishing");
                    if (Boolean.TRUE.equals(finishing)) {
                        Diag.log("game", "jos 页已结束，兜底不介入");
                        return;
                    }
                    invoke(page, "w", data);
                    Diag.log("game", "jos 页已用账号结果成功收尾");
                } catch (Throwable t) {
                    Diag.log("game", "jos 兜底成功失败 " + causeChain(t));
                }
            }
        }, 2500);
    }

    /** 2.6.87：onCreate 里的两道门，决定是「拉起登录」还是直接 H(2005)。 */
    private static void hookJosGate(final ClassLoader cl) {
        try {
            final Class<?> lz = Class.forName("com.huawei.hms.game.lz", false, cl);
            for (final Method m : lz.getDeclaredMethods()) {
                if ("f".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Diag.log("game", "jos 门 lz.f=" + param.getResult());
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            final Class<?> ur = Class.forName("com.huawei.hms.game.ur", false, cl);
            for (final Method m : ur.getDeclaredMethods()) {
                if ("b".equals(m.getName()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "jos 门 ur.b appId=" + param.args[0]);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Diag.log("game", "jos 门 ur.b 结果=" + param.getResult());
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 2.6.87：登录执行器失败时的错误码 —— 这才是 jos 页 28 秒后关掉的直接原因。 */
    private static void hookJosExecutors(final ClassLoader cl) {
        String base = "com.huawei.hms.game.jos.api.account.JosSignInActivity";
        for (final String suffix : new String[]{"$a", "$b", "$c", "$d"}) {
            try {
                final Class<?> inner = Class.forName(base + suffix, false, cl);
                for (final Method m : inner.getDeclaredMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 1 || pt[0] == android.content.Intent.class) continue;
                    if (!"b".equals(m.getName()) && !"a".equals(m.getName())) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("game", "jos 执行器 " + inner.getSimpleName()
                                    + "#" + m.getName() + " code=" + param.args[0]);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
        Diag.log("game", "jos 执行器探针已挂");
    }

    /**
     * 2.6.87：账号 kit 的静默登录到底成没成功。
     *
     * <p>2.6.86 日志里 WebAuth 页 200ms 就 finish，堆栈是
     * {@code handleSilentDataSuccess -> finishWebAuthActivity}，也就是「拿到了静默数据」；
     * 但 jos 页随后还是失败收尾。把 WebAuth 的成功/失败出口都记下来，
     * 才能区分是静默令牌没拿到，还是拿到了却没交回 game kit。</p>
     */
    private static void hookWebAuth(final ClassLoader cl) {
        if (cl == null || !WEB_AUTH_DONE.add(cl)) return;
        final Class<?> wa;
        try {
            wa = Class.forName("com.huawei.hms.hwid.api.impl.WebAuthorizationActivity", false, cl);
        } catch (Throwable ignored) {
            return;
        }
        try {
            for (final Method m : wa.getDeclaredMethods()) {
                String n = m.getName();
                if ("handleSilentDataSuccess".equals(n) || "onSuccessAndFinish".equals(n)
                        || "onSilentDataSucc".equals(n)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object b = param.args.length > 0 ? param.args[0] : null;
                            Diag.log("hms", "账号页 " + n + " "
                                    + (b instanceof Bundle ? "keys=" + ((Bundle) b).keySet() : ""));
                        }
                    });
                } else if ("onSilentServerFail".equals(n) || "onClosePage".equals(n)
                        || "onSilentSTInvalid".equals(n) || "onSilentAuthSerFailed".equals(n)
                        || "onSilentAccountRemoved".equals(n) || "onSilentCheckPwdIntent".equals(n)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            StringBuilder sb = new StringBuilder("账号页 ").append(n);
                            for (Object a : param.args) {
                                sb.append(' ').append(a instanceof Bundle
                                        ? "keys=" + ((Bundle) a).keySet() : String.valueOf(a));
                            }
                            Diag.log("hms", sb.toString());
                        }
                    });
                } else if ("authorizationFailedReport".equals(n) && m.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("hms", "账号页 失败上报 code=" + param.args[0]
                                    + " msg=" + param.args[1]);
                        }
                    });
                }
            }
            Diag.log("hms", "账号页探针已挂");
        } catch (Throwable t) {
            Diag.log("hms", "账号页探针失败 " + t);
        }
    }

    /**
     * 2.6.88：直接把 kit 页的结果交回等待中的 UiJumpActivity。
     *
     * <p>2.6.87 日志证据：23:22:24.437 jos 页收到账号页的 RESULT_OK，
     * 24.4–25.0 秒之间把结果 setResult(-1, …) 并 finish；但 {@code UiJumpActivity}
     * 之后一次都没有 resume、也没有 onActivityResult，直到 23:23:07 被系统一起销毁。
     * 也就是容器虚拟 AMS 没有把「stub 页 finish」的回传路由回去 —— 游戏端
     * 的 Hub 永远等不到结果，于是每 25–30 秒重开一次登录页，看起来就是一直转圈。</p>
     *
     * <p>这里不再依赖那条坏掉的路由：记住正在等结果的 UiJumpActivity，
     * 记住 kit 页 set 的结果，等 kit 页 finish 后 300ms，直接给 UiJumpActivity
     * setResult + finish。结果 Intent 里带 {@code HMS_FOREGROUND_RES_HEADER}
     * （jos 的 n() 会写），所以不会误伤隐私页等其它 stub。</p>
     */
    private static final Map<Object, Object[]> STUB_RESULT =
            Collections.synchronizedMap(new java.util.WeakHashMap<Object, Object[]>());
    private static final Set<Object> ACT_GOT_RESULT = Collections.synchronizedSet(
            Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>()));
    private static volatile android.app.Activity sWaitingJump;
    private static final Set<ClassLoader> RELAY_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());

    private static void hookResultRelay(final ClassLoader cl) {
        if (cl == null || !RELAY_DONE.add(cl)) return;
        try {
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                    "onPause"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof android.app.Activity)) return;
                    android.app.Activity a = (android.app.Activity) param.thisObject;
                    if (a.getClass().getName().endsWith("UiJumpActivity")) {
                        sWaitingJump = a;
                    }
                }
            });
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                    "onActivityResult", int.class, int.class, android.content.Intent.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof android.app.Activity) {
                                ACT_GOT_RESULT.add(param.thisObject);
                                Diag.log("game", "中转页收到结果 req=" + param.args[0]
                                        + " code=" + param.args[1]
                                        + " data=" + describeIntent((android.content.Intent) param.args[2]));
                            }
                        }
                    });
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                    "onDestroy"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == sWaitingJump) sWaitingJump = null;
                }
            });
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                    "setResult", int.class, android.content.Intent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof android.app.Activity)) return;
                    android.app.Activity a = (android.app.Activity) param.thisObject;
                    if (!isKitStub(a)) return;
                    STUB_RESULT.put(a, new Object[]{param.args[0], param.args[1]});
                }
            });
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                    "setResult", int.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof android.app.Activity)) return;
                    android.app.Activity a = (android.app.Activity) param.thisObject;
                    if (!isKitStub(a)) return;
                    STUB_RESULT.put(a, new Object[]{param.args[0], null});
                }
            });
            XposedBridge.hookMethod(android.app.Activity.class.getDeclaredMethod(
                    "finish"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    relayToJump(param.thisObject);
                }
            });
            Diag.log("game", "结果转交探针已挂");
        } catch (Throwable t) {
            Diag.log("game", "结果转交探针失败 " + t);
        }
    }

    private static boolean isKitStub(android.app.Activity a) {
        return a.getClass().getName().startsWith("com.huawei.hms.runtimekit.stub.");
    }

    private static void relayToJump(Object stub) {
        try {
            if (!(stub instanceof android.app.Activity)) return;
            final android.app.Activity a = (android.app.Activity) stub;
            if (!isKitStub(a)) return;
            final Object[] r = STUB_RESULT.remove(a);
            if (r == null) return;
            final int code = (Integer) r[0];
            final android.content.Intent data = (android.content.Intent) r[1];
            if (data == null || !data.hasExtra("HMS_FOREGROUND_RES_HEADER")) return;
            final android.app.Activity wait = sWaitingJump;
            if (wait == null || wait.isFinishing() || ACT_GOT_RESULT.contains(wait)) return;
            Diag.log("game", "kit 页返回，准备交回中转页 code=" + code
                    + " header=" + data.getStringExtra("HMS_FOREGROUND_RES_HEADER"));
            writeCrossProcessResult(code, data);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (wait.isFinishing() || ACT_GOT_RESULT.contains(wait)) {
                            Diag.log("game", "中转页已自己收到结果，转交取消");
                            return;
                        }
                        wait.setResult(code, data);
                        wait.finish();
                        Diag.log("game", "结果已直接交回中转页 code=" + code);
                    } catch (Throwable t) {
                        Diag.log("game", "结果转交失败 " + causeChain(t));
                    }
                }
            }, 300);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 2.6.89：game kit 服务端每个接口的返回码。
     *
     * <p>2.6.88 一打开游戏就弹「请检查网络环境是否异常；请检查手机系统时间是否做过更改」，
     * 该文案是游戏拿到非 0 返回码后自己弹的。所有 game 接口的响应都要经过
     * {@code com.huawei.hms.game.f4#p(String, int)}，这里把 api 名和错误码全部记下来。</p>
     *
     * <p>另外 {@code jos.init} 是这个进程的启动门：它在 {@code xo} 里失败时分两条
     * {@code 7002}/{@code 7005}，游戏就会停在这一步一直重试。它的成功响应本来就是
     * 空 JSON + {@code error_code=0}（{@code xo.S(false)} → {@code p("{}", 0)}），
     * 所以对 {@code jos.init} 的非 0 返回直接按成功放行，不改变游戏看到的数据形状。</p>
     */
    private static final Set<ClassLoader> GAME_API_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());

    private static void hookGameApi(final ClassLoader cl) {
        if (cl == null || !GAME_API_DONE.add(cl)) return;
        try {
            final Class<?> f4 = Class.forName("com.huawei.hms.game.f4", false, cl);
            int n = 0;
            for (final Method m : f4.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (!"p".equals(m.getName()) || pt.length != 2
                        || pt[0] != String.class || pt[1] != int.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object apiO = invoke(param.thisObject, "g");
                            String api = String.valueOf(apiO);
                            int code = (Integer) param.args[1];
                            Diag.log("game", "服务端返回 api=" + api + " code=" + code);
                            if (code != 0 && "jos.init".equals(api)) {
                                param.args[0] = "{}";
                                param.args[1] = 0;
                                Diag.log("game", "jos.init 原返回 " + code + "，按成功放行");
                            }
                        } catch (Throwable t) {
                            Diag.log("game", "返回码读取失败 " + t);
                        }
                    }
                });
                n++;
            }
            Diag.log("game", "服务端返回码探针挂了 " + n + " 个");
        } catch (Throwable t) {
            Diag.log("game", "服务端返回码探针跳过 " + t);
        }
    }

    private static void hookCallerPackage(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("Q1.l", false, cl);
            final Method f = c.getDeclaredMethod("f", String.class);
            XposedBridge.hookMethod(f, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Diag.log("hms", "调用方包名校验 " + param.args[0] + "：容器共用 UID，放行");
                    param.setResult(Boolean.TRUE);
                }
            });
            Diag.log("hms", "调用方包名校验已放行");
        } catch (Throwable t) {
            Diag.log("hms", "包名校验 hook 跳过 " + t);
        }
    }

    private static void hookNetworkControl(ClassLoader cl) {
        try {
            Class<?> v = Class.forName("com.huawei.hms.fwkcom.utils.v", false, cl);
            XposedBridge.hookMethod(v.getDeclaredMethod("b"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Diag.log("hms", "OOBE 网络门原值=" + param.getResult() + "，放行");
                    param.setResult(Boolean.TRUE);
                }
            });
            Diag.log("hms", "OOBE 网络门已放行");
        } catch (Throwable t) {
            Diag.log("hms", "OOBE 网络门 hook 跳过 " + t);
        }
    }

    private static void hookAidlFailure(ClassLoader cl) {
        // 服务端把错误码原样打出来，省得再猜。
        try {
            Class<?> b = Class.forName("com.huawei.hms.core.common.message.b", false, cl);
            for (final Method m : b.getDeclaredMethods()) {
                if ("failure".equals(m.getName()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Diag.log("hms", "AIDL 服务端失败 code=" + param.args[0]);
                        }
                    });
                    break;
                }
            }
            Diag.log("hms", "AIDL 失败码日志已挂");
        } catch (Throwable t) {
            Diag.log("hms", "AIDL 失败码日志跳过 " + t);
        }
        // 记录服务端收到的 API 名。2.6.79：不再猜方法名（y/J），
        // 把所有「首参是 DataBuffer」的方法全挂上，方法名由日志给出。
        hookAllRequestEntries(cl);
    }

    private static void hookAllRequestEntries(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.huawei.hms.core.common.message.a", false, cl);
            Class<?> db = Class.forName("com.huawei.hms.core.aidl.DataBuffer", false, cl);
            int n = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length < 1
                        || !m.getParameterTypes()[0].equals(db)) {
                    continue;
                }
                n++;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object buf = param.args[0];
                            Diag.log("hms", "服务端收到请求 " + m.getName() + " "
                                    + describeBuffer(buf));
                        } catch (Throwable t) {
                            Diag.log("hms", "请求内容读取失败 " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() != null) {
                            Diag.log("hms", "服务端 " + m.getName() + " 抛异常 "
                                    + causeChain(param.getThrowable()));
                        }
                    }
                });
            }
            Diag.log("hms", "服务端请求入口已挂 " + n + " 个");
        } catch (Throwable t) {
            Diag.log("hms", "服务端入口 hook 跳过 " + t);
        }
    }

    /**
     * 2.6.80：按字段类型读 DataBuffer，不再依赖混淆后的字段名。
     * 之前写死 f2729a/f2730b，真机 hwid base 的混淆名对不上，只能打出 type=? api=?。
     */
    private static String describeBuffer(Object buf) {
        if (buf == null) return "buf=null";
        StringBuilder sb = new StringBuilder();
        Class<?> k = buf.getClass();
        int n = 0;
        while (k != null && n < 12) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(buf);
                    if (v instanceof Bundle) {
                        Bundle b = (Bundle) v;
                        sb.append(f.getName()).append("=keys").append(b.keySet());
                        for (String key : b.keySet()) {
                            if (key == null || !key.toLowerCase().contains("header")) continue;
                            try {
                                Object hv = b.get(key);
                                if (hv instanceof String) {
                                    sb.append(" ").append(key).append('=')
                                            .append(trim((String) hv));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        sb.append(' ');
                        n++;
                    } else if (v instanceof String || v instanceof Number
                            || v instanceof Boolean) {
                        sb.append(f.getName()).append('=').append(v).append(' ');
                        n++;
                    }
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        return sb.length() == 0 ? "buf=?" : sb.toString();
    }

    private static String trim(String s) {
        if (s == null) return "null";
        return s.length() <= 400 ? s : s.substring(0, 400) + "...";
    }

    private static Field fieldOf(Class<?> k, String name) {
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                k = k.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (t != null && n++ < 4) {
            if (n > 1) sb.append(" <- ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            t = t.getCause();
        }
        return sb.toString();
    }

    private static Object invokeNoArg(Object target, String name) throws Throwable {
        return invoke(target, name);
    }

    private static Object invoke(Object target, String name, Object... args) throws Throwable {
        Class<?> k = target.getClass();
        while (k != null) {
            for (Method m : k.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                if (m.getParameterTypes().length != args.length) continue;
                m.setAccessible(true);
                return m.invoke(target, args);
            }
            k = k.getSuperclass();
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    /** Bundle 里看错误码和描述，Parcelable 一律只打类名（ErrorStatus 例外）。 */
    private static String dump(Object o) {
        if (!(o instanceof Bundle)) return "<none>";
        Bundle b = (Bundle) o;
        StringBuilder sb = new StringBuilder("bundle{");
        try {
            Set<String> keys = b.keySet();
            int n = 0;
            for (String k : keys) {
                if (n++ >= 24) {
                    sb.append(" …");
                    break;
                }
                sb.append(k).append('=');
                try {
                    Object v = b.get(k);
                    if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                        String s = String.valueOf(v);
                        sb.append(s.length() > 80 ? s.substring(0, 80) + "…" : s);
                    } else if (v == null) {
                        sb.append("null");
                    } else if (v.getClass().getSimpleName().equals("ErrorStatus")) {
                        sb.append("<ErrorStatus code=").append(call(v, "c"))
                                .append(" reason=").append(call(v, "d")).append('>');
                    } else {
                        sb.append('<').append(v.getClass().getSimpleName()).append('>');
                    }
                } catch (Throwable t) {
                    sb.append("ERR");
                }
                sb.append(',');
            }
        } catch (Throwable t) {
            sb.append("keySet ERR ").append(t);
        }
        return sb.append('}').toString();
    }
}
