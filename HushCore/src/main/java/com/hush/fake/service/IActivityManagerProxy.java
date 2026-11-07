package com.hush.fake.service;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Application;
import android.app.IServiceConnection;
import android.app.Notification;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import com.hush.env.android.app.BRActivityManagerNative;
import com.hush.env.android.app.BRActivityManagerOreo;
import com.hush.env.android.app.BRLoadedApkReceiverDispatcher;
import com.hush.env.android.app.BRLoadedApkReceiverDispatcherInnerReceiver;
import com.hush.env.android.app.BRLoadedApkServiceDispatcher;
import com.hush.env.android.app.BRLoadedApkServiceDispatcherInnerConnection;
import com.hush.env.android.content.BRContentProviderNative;
import com.hush.env.android.content.pm.BRUserInfo;
import com.hush.env.android.util.BRSingleton;
import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.core.env.AppSystemEnv;
import com.hush.core.system.DaemonService;
import com.hush.core.system.user.BUserHandle;
import com.hush.entity.AppConfig;
import com.hush.entity.am.RunningAppProcessInfo;
import com.hush.entity.am.RunningServiceInfo;
import com.hush.fake.delegate.ContentProviderDelegate;
import com.hush.fake.delegate.InnerReceiverDelegate;
import com.hush.fake.delegate.ServiceConnectionDelegate;
import com.hush.fake.frameworks.BActivityManager;
import com.hush.fake.frameworks.BPackageManager;
import com.hush.fake.hook.ClassInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.fake.hook.ScanClass;
import com.hush.fake.service.base.PkgMethodProxy;
import com.hush.fake.service.context.providers.ContentProviderStub;
import com.hush.fake.service.context.providers.SystemProviderStub;
import com.hush.proxy.ProxyManifest;
import com.hush.proxy.record.ProxyBroadcastRecord;
import com.hush.proxy.record.ProxyPendingRecord;
import com.hush.utils.ArrayUtils;
import com.hush.utils.ComponentUtils;
import com.hush.utils.FileUtils;
import com.hush.utils.MethodParameterUtils;
import com.hush.utils.Reflector;
import com.hush.utils.Slog;
import com.hush.utils.compat.ActivityManagerCompat;
import com.hush.utils.compat.BuildCompat;
import com.hush.utils.compat.ParceledListSliceCompat;
import com.hush.utils.compat.TaskDescriptionCompat;

import static android.content.Context.RECEIVER_EXPORTED;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * Created by @officialakshit01 on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
@Obfuscate
@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityManagerProxy extends ClassInvocationStub {
    public static final String TAG = "ActivityManagerStub";

    @Override
    protected Object getWho() {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        return BRSingleton.get(iActivityManager).get();
    }

    @Override
    protected void inject(Object base, Object proxy) {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        BRSingleton.get(iActivityManager)._set_mInstance(proxy);
    }

    @Override
    public boolean isBadEnv() {
        return getProxyInvocation() != getWho();
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("getAppStartMode"));
        addMethodHook(new PkgMethodProxy("setAppLockedVerifying"));
        addMethodHook(new PkgMethodProxy("reportJunkFromApp"));
    }

    @ProxyMethod("getContentProvider")
    public static class GetContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Exception {
            int authIndex = getAuthIndex();
            Object auth = args[authIndex];
            Object content = null;

            if (auth instanceof String) {
                if (ProxyManifest.isProxy((String) auth)) {
                    return method.invoke(who, args);
                }

                if (BuildCompat.isQ()) {
                    args[1] = HushCore.getHostPkg();
                }

                if (auth.equals("settings") || auth.equals("media") || auth.equals("telephony")
                        || auth.equals("com.google.android.gsf.gservices")
                        || auth.equals("com.google.android.gsf.gservices.internal")) {
                    content = method.invoke(who, args);
                    if (content != null) {
                        ContentProviderDelegate.update(content, (String) auth);
                    } else {
                        Log.w(TAG, "system getContentProvider returned null for: " + auth);
                    }
                    return content;
                } else {
                    Log.d(TAG, "hook getContentProvider: " + auth);

                    ProviderInfo providerInfo = HushCore.getBPackageManager().resolveContentProvider((String) auth,
                            FileUtils.FileMode.MODE_IWUSR, BActivityThread.getUserId());
                    if (providerInfo == null) {
                        content = method.invoke(who, args);
                        // Only wrap known system authorities. Wrapping guest-app internal
                        // providers (Finsky's SecurityHubContentProvider, etc.) with a Proxy
                        // causes ClassCastException when Play Services casts it to its concrete type.
                        if (content != null && isKnownSystemAuthority((String) auth)) {
                            ContentProviderDelegate.update(content, (String) auth);
                        }
                        return content;
                    }

                    IBinder providerBinder = null;
                    boolean isLocal = false;
                    if (BActivityThread.getAppPid() != -1) {
                        AppConfig appConfig = HushCore.getBActivityManager().initProcess(providerInfo.packageName,
                                providerInfo.processName, BActivityThread.getUserId());
                        isLocal = (appConfig.bpid == BActivityThread.getAppPid());
                        if (!isLocal) {
                            providerBinder = HushCore.getBActivityManager().acquireContentProviderClient(providerInfo);
                        } else {
                            providerBinder = getLocalProviderBinder((String) auth);
                            if (providerBinder == null) {
                                try {
                                    android.content.Context context = null;
                                    if (providerInfo.packageName.equals(BActivityThread.getAppPackageName())
                                            && BActivityThread.getApplication() != null) {
                                        context = BActivityThread.getApplication();
                                    } else {
                                        context = com.hush.app.BActivityThread
                                                .createPackageContext(providerInfo.applicationInfo);
                                        try {
                                            Object loadedApk = com.hush.env.android.app.BRContextImpl.get(context)
                                                    .mPackageInfo();
                                            com.hush.env.android.app.BRLoadedApk.getWithException(loadedApk)
                                                    .makeApplication(false, null);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    com.hush.app.BActivityThread.installProvider(HushCore.mainThread(), context,
                                            providerInfo, null);
                                    providerBinder = getLocalProviderBinder((String) auth);
                                } catch (Throwable e) {
                                    android.util.Log.e(TAG, "Failed to install provider locally: " + auth, e);
                                }
                            }
                        }
                        args[authIndex] = ProxyManifest.getProxyAuthorities(appConfig.bpid);
                        args[getUserIndex()] = HushCore.getHostUserId();
                    }
                    if (providerBinder == null)
                        return null;

                    content = method.invoke(who, args);
                    Reflector.with(content).field("info").set(providerInfo);

                    android.os.IInterface realProvider = BRContentProviderNative.get().asInterface(providerBinder);
                    if (isLocal) {
                        Reflector.with(content).field("provider").set(realProvider);
                    } else {
                        // Only wrap known Hush system providers. Wrapping guest-app internal
                        // providers (Finsky's SecurityHubContentProvider, CachedPackageContentProvider)
                        // with a Java Proxy causes ClassCastException when Play Services casts
                        // the IContentProvider back to its concrete Transport class.
                        if (isKnownSystemAuthority((String) auth)) {
                            Reflector.with(content).field("provider").set(
                                    new ContentProviderStub().wrapper(realProvider, BActivityThread.getAppPackageName()));
                        } else {
                            Reflector.with(content).field("provider").set(realProvider);
                        }
                    }
                }

                return content;
            }
            return method.invoke(who, args);
        }

        private IBinder getLocalProviderBinder(String targetAuth) {
            try {
                Object activityThread = HushCore.mainThread();
                java.util.Map<?, ?> map = com.hush.env.android.app.BRActivityThread.get(activityThread).mProviderMap();
                for (Object value : map.values()) {
                    String[] mNames = com.hush.env.android.app.BRActivityThreadProviderClientRecordP.get(value)
                            .mNames();
                    if (mNames != null) {
                        for (String name : mNames) {
                            if (name.equals(targetAuth)) {
                                android.os.IInterface provider = com.hush.env.android.app.BRActivityThreadProviderClientRecordP
                                        .get(value).mProvider();
                                if (provider != null)
                                    return provider.asBinder();
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return null;
        }

        private static boolean isKnownSystemAuthority(String auth) {
            return "settings".equals(auth) || "media".equals(auth) || "telephony".equals(auth)
                    || "com.google.android.gsf.gservices".equals(auth)
                    || "com.google.android.gsf.gservices.internal".equals(auth);
        }

        private int getAuthIndex() {
            // 10.0
            if (BuildCompat.isQ()) {
                return 2;
            } else {
                return 1;
            }
        }

        private int getUserIndex() {
            return getAuthIndex() + 1;
        }
    }

    @ProxyMethod("startService")
    public static class StartService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            String pkg = null;
            if (intent != null) {
                pkg = intent.getPackage();
                if (pkg == null && intent.getComponent() != null) {
                    pkg = intent.getComponent().getPackageName();
                }
            }
            ResolveInfo resolveInfo = HushCore.getBPackageManager().resolveService(intent, 0, resolvedType,
                    BActivityThread.getUserId());
            com.hush.utils.Diag.log("svc", "startService caller=" + BActivityThread.getAppPackageName()
                    + " intent=" + intent + " resolve=" + (resolveInfo == null ? "null"
                    : resolveInfo.serviceInfo.packageName + "/" + resolveInfo.serviceInfo.name));
            if (resolveInfo == null) {
                // 华为 / 荣耀服务必须完全在空间内闭环。解析不到就说明这个
                // 空间里没装，绝不能回落到真实系统 —— 华为手机上系统自带
                // HMS Core，一旦回落，服务跑在系统里、游戏跑在容器里，
                // 两边 Binder 对不上，表现就是登录一直转圈或直接失败。
                if (isHuaweiServiceIntent(intent, pkg)) {
                    if (isInstalledInSpace(pkg, BActivityThread.getUserId())) {
                        // 包装着，只是 service 没被解析到（子进程 / action 别名），
                        // 仍然让虚拟 AM 起一次，别直接吞掉。
                        com.hush.utils.Diag.log("svc", "startService 包已装但未解析到，交给虚拟 AM: " + intent);
                        return HushCore.getBActivityManager().startService(intent, resolvedType,
                                false, BActivityThread.getUserId());
                    }
                    com.hush.utils.Diag.log("svc", "startService 拒绝回落系统 HMS: " + intent);
                    return null;
                }
                return method.invoke(who, args);
            }

            int requireForegroundIndex = getRequireForeground();
            boolean requireForeground = false;
            if (requireForegroundIndex != -1) {
                requireForeground = (boolean) args[requireForegroundIndex];
            }
            return HushCore.getBActivityManager().startService(intent, resolvedType, requireForeground,
                    BActivityThread.getUserId());
        }

        public int getRequireForeground() {
            if (BuildCompat.isOreo()) {
                return 3;
            }
            return -1;
        }
    }

    @ProxyMethod("stopService")
    public static class StopService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            return HushCore.getBActivityManager().stopService(intent, resolvedType, BActivityThread.getUserId());
        }
    }

    @ProxyMethod("stopServiceToken")
    public static class StopServiceToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            IBinder token = (IBinder) args[1];
            HushCore.getBActivityManager().stopServiceToken(componentName, token, BActivityThread.getUserId());
            return true;
        }
    }

    // TODO 待修复
    @ProxyMethod("bindService")
    public static class BindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[2];
            String resolvedType = (String) args[3];
            IServiceConnection connection = (IServiceConnection) args[4];

            String pkg = null;
            if (intent != null) {
                pkg = intent.getPackage();
                if (pkg == null && intent.getComponent() != null) {
                    pkg = intent.getComponent().getPackageName();
                }
            }

            int userId = intent.getIntExtra("_G_|_UserId", -1);
            userId = userId == -1 ? BActivityThread.getUserId() : userId;
            ResolveInfo resolveInfo = HushCore.getBPackageManager().resolveService(intent, 0, resolvedType, userId);
            // 跨 App 通信排查：把调用方、目标、解析结果都记下来。
            com.hush.utils.Diag.log("svc", "bindService caller=" + BActivityThread.getAppPackageName()
                    + " intent=" + intent + " resolve=" + (resolveInfo == null ? "null"
                    : resolveInfo.serviceInfo.packageName + "/" + resolveInfo.serviceInfo.name));
            boolean installedInSpace = false;
            if (resolveInfo == null && isHuaweiServiceIntent(intent, pkg)) {
                // 同 startService：不允许绑到真实系统的 HMS Core。
                //
                // 但「解析不到」有两种：空间里真没装；装了、只是这个 service
                // 声明在子进程 / 用了 action 别名，BComponentResolver 没命中。
                // 后者若也拒绝，HMS 账号 UI 绑 :core 就会一直等到超时
                // （日志：bind core service time out）。所以包在空间里装着时
                // 仍然让虚拟 AM 去绑，它内部还会再解析一次。
                installedInSpace = isInstalledInSpace(pkg, userId);
                if (!installedInSpace) {
                    com.hush.utils.Diag.log("svc", "bindService 拒绝回落系统 HMS: " + intent);
                    return 0;
                }
                com.hush.utils.Diag.log("svc", "bindService 包已装但未解析到 service，交给虚拟 AM: " + intent);
            }
            if (resolveInfo != null || installedInSpace
                    || AppSystemEnv.isOpenPackage(intent.getComponent())) {
                Intent bindService = HushCore.getBActivityManager().bindService(intent,
                        connection == null ? null : connection.asBinder(), resolvedType, userId);
                if (connection != null) {
                    if (intent.getComponent() == null && resolveInfo != null) {
                        intent.setComponent(
                                new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
                    }
                    IServiceConnection proxy = ServiceConnectionDelegate.createProxy(connection, intent);
                    args[4] = proxy;

                    WeakReference<?> weakReference = BRLoadedApkServiceDispatcherInnerConnection.get(connection)
                            .mDispatcher();
                    if (weakReference != null) {
                        BRLoadedApkServiceDispatcher.get(weakReference.get())._set_mConnection(proxy);
                    }
                }
                if (bindService != null) {
                    args[2] = bindService;
                    Object ret = method.invoke(who, args);
                    // 绑定是异步的：这里只反映「系统收下了没有」，真正连上
                    // 要等 ServiceConnectionDelegate.connected()。两行日志
                    // 一对照，就知道 HMS 的 core service 是没绑上，还是绑上
                    // 了但一直不回包。
                    com.hush.utils.Diag.log("svc", "bindService -> " + ret
                            + " " + (resolveInfo == null ? pkg : resolveInfo.serviceInfo.packageName));
                    return ret;
                }
                return 0;
            }
            return method.invoke(who, args);
        }

        @Override
        protected boolean isEnable() {
            return HushCore.get().isBlackProcess() || HushCore.get().isServerProcess();
        }
    }

    // android 13.0变更
    @ProxyMethod("bindServiceInstance")
    public static class BindServiceInstance extends BindIsolatedService {

    }

    // 10.0
    @ProxyMethod("bindIsolatedService")
    public static class BindIsolatedService extends BindService {
        @Override
        protected Object beforeHook(Object who, Method method, Object[] args) throws Throwable {
            // instanceName
            args[6] = null;
            return super.beforeHook(who, method, args);
        }
    }

    /** 这个包在指定空间里是不是真的装了（不是系统里装着）。 */
    private static boolean isInstalledInSpace(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            return HushCore.getBPackageManager().getPackageInfo(pkg, 0, userId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** intent 的目标是不是华为 / 荣耀账号服务（含只给 action 没给包名的情况）。 */
    private static boolean isHuaweiServiceIntent(Intent intent, String pkg) {
        if (intent == null) return false;
        if (pkg != null && com.hush.core.HmsCore.isHuaweiFamily(pkg)) return true;
        android.content.ComponentName cn = intent.getComponent();
        if (cn != null && com.hush.core.HmsCore.isHuaweiFamily(cn.getPackageName())) return true;
        // HMS SDK 常用 action 不带包名，这里按 action 兜一层
        String action = intent.getAction();
        return action != null && (action.startsWith("com.huawei.hms.")
                || action.startsWith("com.huawei.hwid.")
                || action.startsWith("com.uodis.opendevice."));
    }

    @ProxyMethod("unbindService")
    public static class UnbindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IServiceConnection iServiceConnection = (IServiceConnection) args[0];
            if (iServiceConnection == null) {
                return method.invoke(who, args);
            }
            HushCore.getBActivityManager().unbindService(iServiceConnection.asBinder(), BActivityThread.getUserId());
            ServiceConnectionDelegate delegate = ServiceConnectionDelegate.getDelegate(iServiceConnection.asBinder());
            if (delegate != null) {
                args[0] = delegate;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getRunningAppProcesses")
    public static class GetRunningAppProcesses extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningAppProcessInfo runningAppProcesses = BActivityManager.get()
                    .getRunningAppProcesses(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningAppProcesses == null) {
                return new ArrayList<>();
            }
            // 辅助类 App 判断「游戏在不在跑」全靠这里，落一条日志方便对账。
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (android.app.ActivityManager.RunningAppProcessInfo info : runningAppProcesses.mAppProcessInfoList) {
                if (n++ >= 8) {
                    sb.append(",…");
                    break;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(info.processName).append('(').append(info.pid).append(')');
            }
            com.hush.utils.Diag.log("proc", "getRunningAppProcesses caller="
                    + BActivityThread.getAppPackageName() + " -> [" + sb + "]");
            return runningAppProcesses.mAppProcessInfoList;
        }
    }

    @ProxyMethod("getServices")
    public static class GetServices extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningServiceInfo runningServices = BActivityManager.get()
                    .getRunningServices(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningServices == null) {
                return new ArrayList<>();
            }
            return runningServices.mRunningServiceInfoList;
        }
    }

    @ProxyMethod("getIntentSender")
    public static class GetIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int type = (int) args[0];
            Intent[] intents = (Intent[]) args[getIntentsIndex(args)];
            MethodParameterUtils.replaceFirstAppPkg(args);

            for (int i = 0; i < intents.length; i++) {
                Intent intent = intents[i];
                switch (type) {
                    case ActivityManagerCompat.INTENT_SENDER_ACTIVITY:
                        Intent shadow = new Intent();
                        shadow.setComponent(new ComponentName(HushCore.getHostPkg(),
                                ProxyManifest.getProxyPendingActivity(BActivityThread.getAppPid())));
                        ProxyPendingRecord.saveStub(shadow, intent, BActivityThread.getUserId());
                        intents[i] = shadow;
                        break;
                }
            }

            // Android 12 (API 31) compat: PendingIntent requires FLAG_IMMUTABLE or
            // FLAG_MUTABLE
            if (BuildCompat.isS()) {
                int flagsIndex = getIntentsIndex(args) + 2;
                if (flagsIndex < args.length && args[flagsIndex] instanceof Integer) {
                    int flags = (int) args[flagsIndex];
                    if ((flags & (0x4000000 | 0x2000000)) == 0) {
                        flags |= 0x4000000; // FLAG_IMMUTABLE
                        args[flagsIndex] = flags;
                    }
                }
            }

            IInterface invoke = (IInterface) method.invoke(who, args);
            if (invoke != null) {
                String[] packagesForUid = BPackageManager.get().getPackagesForUid(BActivityThread.getCallingBUid());
                if (packagesForUid.length < 1) {
                    packagesForUid = new String[] { HushCore.getHostPkg() };
                }
                HushCore.getBActivityManager().getIntentSender(invoke.asBinder(), packagesForUid[0],
                        BActivityThread.getCallingBUid());
            }
            return invoke;
        }

        private int getIntentsIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent[]) {
                    return i;
                }
            }
            if (BuildCompat.isR()) {
                return 6;
            } else {
                return 5;
            }
        }
    }

    @ProxyMethod("getPackageForIntentSender")
    public static class getPackageForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return HushCore.getBActivityManager().getPackageForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getUidForIntentSender")
    public static class getUidForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return HushCore.getBActivityManager().getUidForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getIntentSenderWithSourceToken")
    public static class GetIntentSenderWithSourceToken extends GetIntentSender {
    }

    @ProxyMethod("getIntentSenderWithFeature")
    public static class GetIntentSenderWithFeature extends GetIntentSender {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("broadcastIntent")
    public static class BroadcastIntent extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int intentIndex = getIntentIndex(args);
            Intent intent = (Intent) args[intentIndex];
            String resolvedType = (String) args[intentIndex + 1];

            Intent proxyIntent = HushCore.getBActivityManager().sendBroadcast(intent, resolvedType,
                    BActivityThread.getUserId());
            if (proxyIntent != null) {
                // getApplication() may be null during GmsApplication.attachBaseContext (before
                // Application.onCreate), so guard against NPE here.
                android.app.Application app = BActivityThread.getApplication();
                if (app != null) {
                    proxyIntent.setExtrasClassLoader(app.getClassLoader());
                }

                ProxyBroadcastRecord.saveStub(proxyIntent, intent, BActivityThread.getUserId());
                args[intentIndex] = proxyIntent;
            }
            // ignore permission and fix userId=-1 (UserHandle.ALL)
            for (int i = 0; i < args.length; i++) {
                Object o = args[i];
                if (o instanceof String[]) {
                    args[i] = null;
                }
            }
            if (args.length > 0 && args[args.length - 1] instanceof Integer) {
                if (((Integer) args[args.length - 1]) == -1) {
                    args[args.length - 1] = 0;
                }
            }
            return method.invoke(who, args);
        }

        int getIntentIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Intent) {
                    return i;
                }
            }
            return 1;
        }
    }

    @ProxyMethod("unregisterReceiver")
    public static class unregisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args[0] instanceof android.content.IIntentReceiver) {
                android.content.IIntentReceiver intentReceiver = (android.content.IIntentReceiver) args[0];
                com.hush.fake.delegate.InnerReceiverDelegate delegate = com.hush.fake.delegate.InnerReceiverDelegate
                        .getDelegate(intentReceiver.asBinder());
                if (delegate != null) {
                    args[0] = delegate;
                }
            }
            try {
                return method.invoke(who, args);
            } catch (IllegalArgumentException e) {
                // Frameworks like Oppo might still double unregister or unregister invalid
                // ones.
                // Ignore these to avoid crashing since it's just a system cleanup attempt.
                if (e.getMessage() != null && e.getMessage().contains("Receiver not registered")) {
                    return null;
                }
                throw e;
            }
        }
    }

    @ProxyMethod("finishReceiver")
    public static class finishReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("publishService")
    public static class PublishService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("peekService")
    public static class PeekService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            return HushCore.getBActivityManager().peekService(intent, resolvedType, BActivityThread.getUserId());
        }
    }

    // todo
    @ProxyMethod("sendIntentSender")
    public static class SendIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    // android 10
    @ProxyMethod("registerReceiverWithFeature")
    public static class RegisterReceiverWithFeature extends RegisterReceiver {

    }

    @ProxyMethod("registerReceiver")
    public static class RegisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            int receiverIndex = MethodParameterUtils.getIndex(args, IIntentReceiver.class);
            if (receiverIndex != -1 && args[receiverIndex] != null) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);

                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver)
                        .mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }

                args[receiverIndex] = proxy;
            }
            // ignore permission
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof String && ((String) args[i]).startsWith("android.permission.")) {
                    args[i] = null;
                }
            }
            return method.invoke(who, args);
        }
    }

    // 这里需要修复
    @ProxyMethod("grantUriPermission")
    public static class GrantUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setServiceForeground")
    public static class setServiceForeground extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getHistoricalProcessExitReasons")
    public static class getHistoricalProcessExitReasons extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getCurrentUser")
    public static class getCurrentUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BRUserInfo.get()._new(BActivityThread.getUserId(), "EliteCore", BRUserInfo.get().FLAG_PRIMARY());
        }
    }

    @ProxyMethod("checkPermission")
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            String permission = (String) args[0];
            if (permission != null) {
                switch (permission) {
                    case Manifest.permission.ACCOUNT_MANAGER:
                    case Manifest.permission.SEND_SMS:
                        // GMS: allow config reads and cross-user broadcasts
                    case "android.permission.READ_DEVICE_CONFIG":
                    case "android.permission.WRITE_DEVICE_CONFIG":
                    case "android.permission.INTERACT_ACROSS_USERS":
                    case "android.permission.INTERACT_ACROSS_USERS_FULL":
                    case "android.permission.MANAGE_USERS":
                        // GMS internal permissions (AppDoctorProvider, etc.)
                    case "com.google.android.gms.permission.INTERNAL_BROADCAST":
                    case "com.google.android.gms.permission.INTERNAL_BROADCAST_RECEIVE":
                    case "com.google.android.providers.gsf.permission.READ_GSERVICES":
                    case "com.google.android.c2dm.permission.RECEIVE":
                    // HMS 账号体系依赖的 AOSP 账号权限（宿主本身没申请）
                    case "android.permission.GET_ACCOUNTS":
                    case "android.permission.AUTHENTICATE_ACCOUNTS":
                    case "android.permission.MANAGE_ACCOUNTS":
                    case "android.permission.USE_CREDENTIALS":
                    case "android.permission.READ_PRIVILEGED_PHONE_STATE":
                    // 网络类权限（2.6.33）。这些在真机上是 normal 级、安装即授予，
                    // 容器里却全部落到 checkUidPermission -> 宿主 PM 查虚拟包名
                    // -> 查不到 -> DENIED。
                    // 后果：HMS 的 NetworkUtil.isNetworkAvailable() 第一句就是
                    //   ContextCompat.checkSelfPermission(ctx, ACCESS_NETWORK_STATE)
                    // 拿到 DENIED 直接返回 false，连 getActiveNetworkInfo() 都不调，
                    // 登录页于是显示「没有网络，无法下一步」——而手机网络其实是通的。
                    // 虚拟 app 跑在宿主 UID 下，真实网络能力受宿主 INTERNET 约束，
                    // 这里放行不会越权。
                    case "android.permission.ACCESS_NETWORK_STATE":
                    case "android.permission.INTERNET":
                    case "android.permission.ACCESS_WIFI_STATE":
                    case "android.permission.CHANGE_NETWORK_STATE":
                    case "android.permission.CHANGE_WIFI_STATE":
                    case "android.permission.ACCESS_NETWORK_CONDITIONS":
                        return PackageManager.PERMISSION_GRANTED;
                }
                // 华为 / 荣耀生态的私有权限都是 signature 级，正常只授予同签名
                // 的官方应用。空间里 HMS 用的是官方 APK（签名一致），但系统并
                // 不知道这层关系，会一律拒绝，于是 HMS 初始化第一步读自己的
                // provider 就被挡住。这里直接放行华为/荣耀命名空间下的权限。
                if (permission.startsWith("com.huawei.") || permission.startsWith("com.hihonor.")) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkUriPermission")
    public static class checkUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    // for < Android 10
    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setRequestedOrientation")
    public static class setRequestedOrientation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return 0;
        }
    }

    @ProxyMethod("registerUidObserver")
    public static class registerUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("unregisterUidObserver")
    public static class unregisterUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("updateConfiguration")
    public static class updateConfiguration extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    /**
     * GMS persistent process (e.g. TrustedTime) calls sendBroadcastAsUser() with
     * UserHandle.ALL (-1) which triggers:
     * SecurityException: Permission Denial: broadcast from com.bgmi asks to run as
     * user -1 ... requires INTERACT_ACROSS_USERS_FULL
     *
     * Fix: remap userId=-1 to the host userId (0), and swallow SecurityExceptions
     * that slip through so GMS does not crash.
     */
    @ProxyMethod("broadcastIntentWithFeature")
    public static class BroadcastIntentWithFeature extends BroadcastIntent {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Clamp last integer arg (userId) from -1 to 0
            for (int i = args.length - 1; i >= 0; i--) {
                if (args[i] instanceof Integer) {
                    if ((int) args[i] == -1) {
                        args[i] = 0;
                    }
                    break;
                }
            }
            try {
                return super.hook(who, method, args);
            } catch (SecurityException se) {
                // Absorb INTERACT_ACROSS_USERS denials from GMS service broadcasts
                Log.d(TAG, "broadcastIntentWithFeature suppressed SecurityException: " + se.getMessage());
                return 0;
            }
        }
    }

}
