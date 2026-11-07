package com.hush.fake.service;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import java.lang.reflect.Method;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.utils.MethodParameterUtils;

import com.hush.env.android.app.BRActivityThread;
import com.hush.env.android.app.BRContextImpl;
import com.hush.env.android.os.BRServiceManager;
import com.hush.env.android.permission.BRIPermissionManagerStub;

import com.hush.HushCore;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.service.base.PkgMethodProxy;
import com.hush.fake.service.base.ValueMethodProxy;
import com.hush.utils.Reflector;
import com.hush.utils.compat.BuildCompat;

/**
 * Fixed for Android 10–15
 * No mid-game crash
 */
public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        try {
            replaceSystemService("permissionmgr");
        } catch (Throwable t) {
            t.printStackTrace();
        }

        try {
            BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        } catch (Throwable t) {
            try {
                BRActivityThread.get()._set_sPermissionManager(proxyInvocation);
            } catch (Throwable t2) {
                t2.printStackTrace();
            }
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

    private void injectPackageManager(PackageManager packageManager, Object proxyInvocation) {
        if (packageManager == null) return;
        try {
            Object mPermissionManager = Reflector.on("android.app.ApplicationPackageManager")
                    .field("mPermissionManager")
                    .get(packageManager);
            if (mPermissionManager != null) {
                if (mPermissionManager.getClass().getName().equals("android.permission.PermissionManager")) {
                    try {
                        Reflector.on("android.permission.PermissionManager")
                                .field("mPermissionManager")
                                .set(mPermissionManager, proxyInvocation);
                    } catch (Throwable e1) {
                        try {
                            Reflector.on("android.permission.PermissionManager")
                                    .field("mService")
                                    .set(mPermissionManager, proxyInvocation);
                        } catch (Throwable e2) {
                            e2.printStackTrace();
                        }
                    }
                } else {
                    Reflector.on("android.app.ApplicationPackageManager")
                            .field("mPermissionManager")
                            .set(packageManager, proxyInvocation);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    /**
     * On Android 12+, DeviceConfig.enforceReadPermission() uses
     * PermissionChecker.checkPermissionForPreflight() which calls
     * IPermissionManager.checkPermission() rather than
     * IActivityManager.checkPermission().
     * Granting READ_DEVICE_CONFIG here prevents the SecurityException
     * that crashes GservicesProvider.onCreate() on these versions.
     */
    @ProxyMethod("checkPermission")
    public static class CheckPermissionProxy extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                String permission = (String) args[0];
                switch (permission) {
                    case "android.permission.READ_DEVICE_CONFIG":
                    case "android.permission.WRITE_DEVICE_CONFIG":
                    case "android.permission.INTERACT_ACROSS_USERS":
                    case "android.permission.INTERACT_ACROSS_USERS_FULL":
                    case "android.permission.MANAGE_USERS":
                    case Manifest.permission.ACCOUNT_MANAGER:
                    // GMS internal permissions (AppDoctorProvider, C2DM, GServices, etc.)
                    case "com.google.android.gms.permission.INTERNAL_BROADCAST":
                    case "com.google.android.gms.permission.INTERNAL_BROADCAST_RECEIVE":
                    case "com.google.android.providers.gsf.permission.READ_GSERVICES":
                    case "com.google.android.c2dm.permission.RECEIVE":
                    case "android.permission.GET_ACCOUNTS":
                    case "android.permission.AUTHENTICATE_ACCOUNTS":
                    case "android.permission.MANAGE_ACCOUNTS":
                    case "android.permission.USE_CREDENTIALS":
                    case "android.permission.READ_PRIVILEGED_PHONE_STATE":
                    // 2.6.33：Android 11+ 的 PermissionChecker.checkSelfPermission
                    // 走 IPermissionManager，HMS 用这个 API 判断「有没有网」。
                    // 容器里查不到虚拟包的权限记录，必然 DENIED，登录页就会显示
                    // 「没有网络，无法下一步」。这些是 normal 级、安装即授予。
                    case "android.permission.ACCESS_NETWORK_STATE":
                    case "android.permission.INTERNET":
                    case "android.permission.ACCESS_WIFI_STATE":
                    case "android.permission.CHANGE_NETWORK_STATE":
                    case "android.permission.CHANGE_WIFI_STATE":
                    case "android.permission.ACCESS_NETWORK_CONDITIONS":
                        return PackageManager.PERMISSION_GRANTED;
                }
                // 同 IActivityManagerProxy：华为/荣耀私有权限整段放行
                if (permission.startsWith("com.huawei.") || permission.startsWith("com.hihonor.")) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

}
