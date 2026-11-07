package com.hush.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.hush.env.android.app.BRActivityThread;
import com.hush.env.android.app.BRContextImpl;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.core.GmsCore;
import com.hush.core.env.AppSystemEnv;
import com.hush.core.hook.PmTrace;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.fake.service.base.PkgMethodProxy;
import com.hush.fake.service.base.ValueMethodProxy;
import com.hush.utils.MethodParameterUtils;
import com.hush.utils.Reflector;
import com.hush.utils.compat.BuildCompat;
import com.hush.utils.compat.ParceledListSliceCompat;

/**
 * IPackageManagerProxy — Production Final
 *
 * CRITICAL DESIGN DECISIONS for Facebook OAuth:
 *
 * 1. queryIntentActivities for http/https is NOT blocked.
 * REASON: Facebook SDK's CustomTabMainActivity.onCreate() calls
 * queryIntentActivities(ACTION_VIEW, https) to check if ANY browser
 * is available before trying to launch the CCT. If we return empty,
 * the SDK calls finish() immediately (21ms after creation) without
 * ever calling startActivity(https_url). That https_url call is what
 * ActivityManagerCommonProxy.StartActivity intercepts to redirect to
 * ProxyWebActivity. Blocking it kills the entire flow.
 *
 * 2. resolveService for CCT IS blocked.
 * This prevents Chrome from binding as a CCT service provider.
 * The SDK falls back to a regular View intent for the URL,
 * which we then intercept in StartActivity.hook().
 *
 * 3. queryIntentServices is NOT blocked.
 * SDK's isCustomTabsSupported() calls this. Blocking → RESULT_CANCELED.
 */
public class IPackageManagerProxy extends BinderInvocationStub {

    public static final String TAG = "PackageManagerStub";

    public static boolean isFacebookInstalledInCore() {
        return HushCore.get().isInstalled("com.facebook.katana", BActivityThread.getUserId()) ||
               HushCore.get().isInstalled("com.facebook.lite", BActivityThread.getUserId());
    }

    public static boolean isWhatsAppInstalledInCore() {
        return HushCore.get().isInstalled("com.whatsapp", BActivityThread.getUserId());
    }


    private static final String CCT_ACTION_SUPPORT = "android.support.customtabs.action.CustomTabsService";
    private static final String CCT_ACTION_ANDROIDX = "androidx.browser.customtabs.CustomTabsService";

    private static boolean isCustomTabsServiceIntent(Intent intent) {
        if (intent == null)
            return false;
        String a = intent.getAction();
        return CCT_ACTION_SUPPORT.equals(a) || CCT_ACTION_ANDROIDX.equals(a);
    }

    private static Object wrapResolveList(List<ResolveInfo> list) {
        if (BuildCompat.isN())
            return ParceledListSliceCompat.create(list);
        return list;
    }

    private static List<ResolveInfo> filterResolveInfo(List<ResolveInfo> list) {
        if (list == null)
            return null;
        List<ResolveInfo> filtered = new ArrayList<>();
        for (ResolveInfo ri : list) {
            if (ri != null && ri.activityInfo != null) {
                String pkg = ri.activityInfo.packageName;
                if (pkg != null && (pkg.startsWith("com.facebook.katana")
                        || pkg.startsWith("com.facebook.orca")
                        || pkg.startsWith("com.facebook.lite")
                        || pkg.startsWith("com.facebook.mlite"))) {
                    continue;
                }
            }
            filtered.add(ri);
        }
        return filtered;
    }

    public IPackageManagerProxy() {
        super(BRActivityThread.get().sPackageManager().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRActivityThread.get().sPackageManager();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        try {
            BRActivityThread.get()._set_sPackageManager(proxyInvocation);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        try {
            replaceSystemService("package");
        } catch (Throwable t) {
            t.printStackTrace();
        }

        try {
            injectPackageManager(HushCore.getContext().getPackageManager(), proxyInvocation);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        try {
            Object mainThread = HushCore.mainThread();
            if (mainThread != null) {
                Object systemContext = BRActivityThread.get(mainThread).getSystemContext();
                if (systemContext != null) {
                    BRContextImpl.get(systemContext).getPackageManager();
                    PackageManager pm = BRContextImpl.get(systemContext).mPackageManager();
                    if (pm != null) {
                        injectPackageManager(pm, proxyInvocation);
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void injectPackageManager(PackageManager pm, Object proxyInvocation) {
        if (pm == null)
            return;
        try {
            Reflector.on("android.app.ApplicationPackageManager")
                    .field("mPM").set(pm, proxyInvocation);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
    }

    @ProxyMethod("resolveIntent")
    public static class ResolveIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String type = (String) args[1];
            int flags = Integer.parseInt(args[2] + "");
            ResolveInfo ri = HushCore.getBPackageManager()
                    .resolveIntent(intent, type, flags, BActivityThread.getUserId());
            if (ri != null)
                return ri;
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("resolveService")
    public static class ResolveService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String type = (String) args[1];
            int flags = Integer.parseInt(args[2] + "");
            // Block CCT service binding — prevents Chrome from connecting as CCT provider.
            // SDK falls back to regular startActivity(https_url) which we intercept.
            if (isCustomTabsServiceIntent(intent))
                return null;
            ResolveInfo ri = HushCore.getBPackageManager()
                    .resolveService(intent, flags, type, BActivityThread.getUserId());
            if (ri != null)
                return ri;
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("queryIntentActivities")
    public static class QueryIntentActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = args.length > 0 && args[0] instanceof Intent ? (Intent) args[0] : null;
            String type = args.length > 1 && args[1] instanceof String ? (String) args[1] : null;
            int flags = args.length > 2 ? MethodParameterUtils.toInt(args[2]) : 0;
            List<ResolveInfo> vr = HushCore.getBPackageManager()
                    .queryIntentActivities(intent, flags, type, BActivityThread.getUserId());
            if (vr != null && !vr.isEmpty())
                return wrapResolveList(filterResolveInfo(vr));
            Object hostRes = method.invoke(who, args);
            if (hostRes instanceof List) {
                return filterResolveInfo((List<ResolveInfo>) hostRes);
            } else if (hostRes != null && hostRes.getClass().getName().contains("ParceledListSlice")) {
                try {
                    List<ResolveInfo> list = Reflector.with(hostRes).method("getList").call();
                    return wrapResolveList(filterResolveInfo(list));
                } catch (Exception ignored) {
                }
            }
            return hostRes;
        }
    }

    // queryIntentServices: NOT blocked — SDK uses it for isCustomTabsSupported()

    @ProxyMethod("setComponentEnabledSetting")
    public static class SetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            // 华为 / 荣耀服务走虚拟 PM：它们现在和 GMS 一样完整装进空间，
            // 返回虚拟副本（同一份官方签名）才是正确的。
            int userId = BActivityThread.getUserId();
            PackageInfo pi = HushCore.getBPackageManager().getPackageInfo(pkg, flags, userId);
            if (pi == null && !isConfigReady()) {
                // HMS Core 的 persistent / container 子进程会在 AppConfig
                // 就绪之前查自己的包（Hianalytics、NetworkKit 初始化都在这时），
                // 此时 userId 还是默认值，查不到会让 HMS 认为「自己没安装」，
                // 接着 kit / 网络模块全部初始化失败。按 0 再查一次兜底。
                pi = HushCore.getBPackageManager().getPackageInfo(pkg, flags, 0);
                if (pi != null) userId = 0;
            }
            if (pi != null)
                return pi;

            // 老版 HMS SDK 探测 HMS Core 时依次查 hwid / hms / hwid.tv 三个包名。
            // com.huawei.hms 只是 SDK 自己的历史包名，真机上并没有这个 APK，
            // 但容器里如果按「未安装」回答，SDK 会直接判定 HMS 不可用 ——
            // 光遇弹「HMS Core 安装提示」、应用市场拒登都是这条链路。
            // 这里把它别名到已经装进空间的 hwid，版本 / 签名 / 组件全部沿用。
            if (com.hush.core.HmsCore.ALIAS_PKG.equals(pkg)) {
                PackageInfo real = HushCore.getBPackageManager()
                        .getPackageInfo(com.hush.core.HmsCore.HMS_PKG, flags, userId);
                if (real != null) {
                    real.packageName = pkg;
                    if (real.applicationInfo != null) {
                        real.applicationInfo.packageName = pkg;
                    }
                    PmTrace.hit("getPackageInfo", pkg + "@" + userId, flags, "alias-hwid");
                    return real;
                }
            }

            // 华为 / 荣耀服务必须完全在空间内闭环：空间里没装就按「未安装」
            // 回答，不能回落到真实系统。否则游戏会以为 HMS 可用，绑定服务
            // 时又找不到虚拟进程，表现就是登录一直转圈。
            if (com.hush.core.HmsCore.isHuaweiFamily(pkg)
                    && !AppSystemEnv.isOpenPackage(pkg)) {
                PmTrace.hit("getPackageInfo", pkg + "@" + userId, flags, "hms-absent");
                throw new PackageManager.NameNotFoundException(pkg);
            }

            // 1.6.32：容器没虚拟化的包，一律回落到真实系统 PM。
            //
            // 1.6.31 的日志第一次把光遇真正的死因摆出来：
            //   ServiceConfigurationError: ... PackageInfo.versionCode ... null
            //   at WV.s02.<init> / AwBrowserProcess / WebViewChromium.init
            //   at WebView.<init> / UniWebView.<init> / ProtocolLauncher.initWebView
            // 也就是 WebView 初始化时拿到的 PackageInfo 是 null。
            //
            // 旧逻辑是「白名单透传，其余一律 null」。返回 null 违反
            // PackageManager 的契约（查不到要抛 NameNotFoundException），
            // 而 Chromium 只 catch 异常、不判空，读 versionCode 时直接 NPE。
            //
            // 现在只保留三类继续隐藏（root / Xposed / 明确要求隐身的社交包），
            // 其余按真机回答 —— 真机上这些包本来就能查到。
            String hide = hideReason(pkg);
            if (hide == null) {
                try {
                    Object real = method.invoke(who, args);
                    if (real != null) {
                        PmTrace.hit("getPackageInfo", pkg, flags, "host");
                        return real;
                    }
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof PackageManager.NameNotFoundException) {
                        PmTrace.hit("getPackageInfo", pkg, flags, "host-nfn");
                        throw cause;
                    }
                    PmTrace.hit("getPackageInfo", pkg, flags, "host-err:" + cause);
                    throw e;
                }
            }
            PmTrace.hit("getPackageInfo", pkg, flags, hide == null ? "null" : "hidden:" + hide);
            return null;
        }
    }

    /**
     * 这个包在 guest 里必须继续隐身的原因；返回 null 表示可以回落到真实 PM。
     *
     * 与 {@link AppSystemEnv#isOpenPackage} 的区别：那是一个「白名单」，
     * 白名单外的包会被吞成 null；这里反过来，只有明确该隐藏的才拦。
     */
    private static String hideReason(String pkg) {
        if (pkg == null) return "null";
        if (AppSystemEnv.isBlackPackage(pkg)) return "black";
        if (pkg.startsWith("com.facebook.") && !isFacebookInstalledInCore()) return "facebook";
        if (pkg.startsWith("com.whatsapp") && !isWhatsAppInstalledInCore()) return "whatsapp";
        return null;
    }

    @ProxyMethod("getPackageUid")
    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = (args != null && args.length > 0 && args[0] instanceof String) ? (String) args[0] : null;
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackageUidEtc")
    public static class getPackageUidEtc extends GetPackageUid {
    }

    @ProxyMethod("setApplicationBlockedSettingAsUser")
    public static class setApplicationBlockedSettingAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUserId(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getProviderInfo")
    public static class GetProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName cn = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ProviderInfo pi = HushCore.getBPackageManager().getProviderInfo(cn, flags, BActivityThread.getUserId());
            if (pi != null)
                return pi;
            // 同 getPackageInfo：WebView 就是靠 getProviderInfo 找到
            // org.chromium.android_webview.AwBrowserProcess 这个 provider 的，
            // 这里返回 null 会让 WebViewFactory 直接判定「没有可用的 WebView 实现」。
            if (hideReason(cn == null ? null : cn.getPackageName()) == null) {
                try {
                    Object real = method.invoke(who, args);
                    if (real != null) {
                        PmTrace.hit("getProviderInfo", cn == null ? "null" : cn.getPackageName(),
                                flags, "host");
                        return real;
                    }
                } catch (InvocationTargetException e) {
                    PmTrace.hit("getProviderInfo", cn == null ? "null" : cn.getPackageName(),
                            flags, "host-err:" + e.getCause());
                    throw e;
                }
            }
            return null;
        }
    }

    @ProxyMethod("getReceiverInfo")
    public static class GetReceiverInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName cn = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo ai = HushCore.getBPackageManager().getReceiverInfo(cn, flags, BActivityThread.getUserId());
            if (ai != null)
                return ai;
            if (AppSystemEnv.isOpenPackage(cn))
                return method.invoke(who, args);
            return null;
        }
    }

    @ProxyMethod("getActivityInfo")
    public static class GetActivityInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName cn = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo ai = HushCore.getBPackageManager().getActivityInfo(cn, flags, BActivityThread.getUserId());
            if (ai != null)
                return ai;
            if (AppSystemEnv.isOpenPackage(cn))
                return method.invoke(who, args);
            return null;
        }
    }

    @ProxyMethod("getServiceInfo")
    public static class GetServiceInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName cn = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ServiceInfo si = HushCore.getBPackageManager().getServiceInfo(cn, flags, BActivityThread.getUserId());
            if (si != null)
                return si;
            if (AppSystemEnv.isOpenPackage(cn))
                return method.invoke(who, args);
            return null;
        }
    }

    @ProxyMethod("getInstalledApplications")
    public static class GetInstalledApplications extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(HushCore.getBPackageManager()
                    .getInstalledApplications(MethodParameterUtils.toInt(args[0]), BActivityThread.getUserId()));
        }
    }

    @ProxyMethod("getInstalledPackages")
    public static class GetInstalledPackages extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(HushCore.getBPackageManager()
                    .getInstalledPackages(MethodParameterUtils.toInt(args[0]), BActivityThread.getUserId()));
        }
    }

    @ProxyMethod("getApplicationInfo")
    public static class GetApplicationInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            int userId = BActivityThread.getUserId();
            ApplicationInfo ai = HushCore.getBPackageManager().getApplicationInfo(pkg, flags, userId);
            if (ai == null && !isConfigReady()) {
                ai = HushCore.getBPackageManager().getApplicationInfo(pkg, flags, 0);
                if (ai != null) userId = 0;
            }
            if (ai != null)
                return ai;
            // 同 getPackageInfo：把 SDK 的历史包名 com.huawei.hms 别名到 hwid。
            if (com.hush.core.HmsCore.ALIAS_PKG.equals(pkg)) {
                ApplicationInfo real = HushCore.getBPackageManager()
                        .getApplicationInfo(com.hush.core.HmsCore.HMS_PKG, flags, userId);
                if (real != null) {
                    real.packageName = pkg;
                    PmTrace.hit("getApplicationInfo", pkg + "@" + userId, flags, "alias-hwid");
                    return real;
                }
            }
            if (com.hush.core.HmsCore.isHuaweiFamily(pkg)
                    && !AppSystemEnv.isOpenPackage(pkg)) {
                PmTrace.hit("getApplicationInfo", pkg + "@" + userId, flags, "hms-absent");
                throw new PackageManager.NameNotFoundException(pkg);
            }
            // 与 getPackageInfo 同一套回落策略：容器没虚拟化的包，按真机回答。
            if (hideReason(pkg) == null) {
                try {
                    Object real = method.invoke(who, args);
                    if (real != null) {
                        PmTrace.hit("getApplicationInfo", pkg, flags, "host");
                        return real;
                    }
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof PackageManager.NameNotFoundException) {
                        PmTrace.hit("getApplicationInfo", pkg, flags, "host-nfn");
                        throw cause;
                    }
                    PmTrace.hit("getApplicationInfo", pkg, flags, "host-err:" + cause);
                    throw e;
                }
            }
            PmTrace.hit("getApplicationInfo", pkg, flags, "null");
            return null;
        }
    }

    @ProxyMethod("queryContentProviders")
    public static class QueryContentProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(HushCore.getBPackageManager()
                    .queryContentProviders(BActivityThread.getAppProcessName(),
                            BActivityThread.getBUid(), MethodParameterUtils.toInt(args[2]),
                            BActivityThread.getUserId()));
        }
    }

    @ProxyMethod("queryIntentReceivers")
    public static class QueryBroadcastReceivers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);
            List<ResolveInfo> res = HushCore.getBPackageManager()
                    .queryBroadcastReceivers(intent, flags != null ? flags : 0, type, BActivityThread.getUserId());
            return BuildCompat.isN() ? ParceledListSliceCompat.create(res) : res;
        }
    }

    @ProxyMethod("resolveContentProvider")
    public static class ResolveContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String authority = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            if (authority != null && (authority.contains("com.google") || authority.contains("firebase")
                    || authority.contains("gservices"))) {
                return method.invoke(who, args);
            }
            ProviderInfo pi = HushCore.getBPackageManager()
                    .resolveContentProvider(authority, flags, BActivityThread.getUserId());
            return pi != null ? pi : method.invoke(who, args);
        }
    }

    @ProxyMethod("canRequestPackageInstalls")
    public static class CanRequestPackageInstalls extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackagesForUid")
    public static class GetPackagesForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = MethodParameterUtils.toInt(args[0]);
            if (uid == HushCore.getHostUid())
                args[0] = BActivityThread.getBUid();
            return HushCore.getBPackageManager().getPackagesForUid(MethodParameterUtils.toInt(args[0]));
        }
    }

    @ProxyMethod("getNameForUid")
    public static class GetNameForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = MethodParameterUtils.toInt(args[0]);
            if (uid == HushCore.getHostUid())
                uid = BActivityThread.getBUid();
            String[] pkgs = HushCore.getBPackageManager().getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) {
                return pkgs[0];
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getNamesForUids")
    public static class GetNamesForUids extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int[] uids = (int[]) args[0];
            String[] names = new String[uids.length];
            for (int i = 0; i < uids.length; i++) {
                int uid = uids[i];
                if (uid == HushCore.getHostUid())
                    uid = BActivityThread.getBUid();
                String[] pkgs = HushCore.getBPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) {
                    names[i] = pkgs[0];
                } else {
                    names[i] = null;
                }
            }
            return names;
        }
    }

    @ProxyMethod("getInstallerPackageName")
    public static class GetInstallerPackageName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return GmsCore.VENDING_PKG;
        }
    }

    @ProxyMethod("getSharedLibraries")
    public static class GetSharedLibraries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class getComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName cn = (ComponentName) args[0];
            ApplicationInfo ai = HushCore.getBPackageManager()
                    .getApplicationInfo(cn.getPackageName(), 0, BActivityThread.getUserId());
            if (ai != null)
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            if (AppSystemEnv.isOpenPackage(cn))
                return method.invoke(who, args);
            throw new IllegalArgumentException();
        }
    }

    @ProxyMethod("addPackageToPreferred")
    public static class addPackageToPreferred extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("setSplashScreenTheme")
    public static class SetSplashScreenTheme extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            boolean xiaomi = BuildCompat.isMIUI()
                    || Build.MANUFACTURER.toLowerCase().contains("xiaomi")
                    || Build.BRAND.toLowerCase().contains("xiaomi");
            if (xiaomi)
                return null;
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                return null;
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException)
                    return null;
                throw e;
            }
        }
    }

    @ProxyMethod("checkSignatures")
    public static class checkSignatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args.length == 2 && args[0] instanceof String && args[1] instanceof String) {
                String p1 = (String) args[0];
                String p2 = (String) args[1];
                if (p1 != null && p2 != null) {
                }
                try {
                    PackageManager pm = HushCore.getPackageManager();
                    PackageInfo p1Info = null;
                    if (HushCore.getBPackageManager().isInstalled(p1, BActivityThread.getUserId())) {
                        p1Info = HushCore.getBPackageManager().getPackageInfo(p1, PackageManager.GET_SIGNATURES,
                                BActivityThread.getUserId());
                    } else {
                        p1Info = pm.getPackageInfo(p1, PackageManager.GET_SIGNATURES);
                    }
                    PackageInfo p2Info = null;
                    if (HushCore.getBPackageManager().isInstalled(p2, BActivityThread.getUserId())) {
                        p2Info = HushCore.getBPackageManager().getPackageInfo(p2, PackageManager.GET_SIGNATURES,
                                BActivityThread.getUserId());
                    } else {
                        p2Info = pm.getPackageInfo(p2, PackageManager.GET_SIGNATURES);
                    }
                    Signature[] s1 = p1Info != null ? p1Info.signatures : null;
                    Signature[] s2 = p2Info != null ? p2Info.signatures : null;
                    if (s1 == null || s1.length == 0)
                        return (s2 == null || s2.length == 0) ? 1 : -1;
                    if (s2 == null || s2.length == 0)
                        return -2;
                    return Arrays.equals(s1, s2) ? 0 : -3;
                } catch (Exception ignored) {
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkUidSignatures")
    public static class checkUidSignatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("clearPackagePersistentPreferredActivities")
    public static class clearPackagePersistentPreferredActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("clearPackagePreferredActivities")
    public static class clearPackagePreferredActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getApplicationBlockedSettingAsUser")
    public static class getApplicationBlockedSettingAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    /**
     * 进程配置是否还没就绪。
     *
     * <p>HMS 的 persistent / container 子进程会在 attachBaseContext 阶段就查包，
     * 这时 {@code currentActivityThread()} 可能还没挂上，取 AppConfig 会抛 NPE。
     * 真抛了就按「已就绪」处理，不再兜底，避免把异常从这里漏出去。
     */
    private static boolean isConfigReady() {
        try {
            return BActivityThread.getAppConfig() != null;
        } catch (Throwable t) {
            return true;
        }
    }

    @ProxyMethod("setPackageStoppedState")
    public static class setPackageStoppedState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
}
