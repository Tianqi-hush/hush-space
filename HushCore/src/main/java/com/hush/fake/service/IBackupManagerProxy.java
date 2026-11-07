package com.hush.fake.service;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import com.hush.env.android.app.backup.BRIBackupManager;
import com.hush.env.android.app.backup.BRIBackupManagerStub;
import com.hush.env.android.os.BRServiceManager;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.utils.MethodParameterUtils;

/**
 * @author gm
 * @function
 * @date :2024/4/20 21:39
 **/
public class IBackupManagerProxy extends BinderInvocationStub {
    public IBackupManagerProxy() {
        super(BRServiceManager.get().getService("backup"));
    }

    @Override
    protected Object getWho() {
        return BRIBackupManagerStub.get().asInterface(BRServiceManager.get().getService("backup"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("backup");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("beginRestoreSession")
    public static class beginRestoreSession extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("hasBackupPassword")
    public static class hasBackupPassword extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("setBackupPassword")
    public static class setBackupPassword extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("isBackupEnabled")
    public static class isBackupEnabled extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("selectBackupTransport")
    public static class selectBackupTransport extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("listAllTransports")
    public static class listAllTransports extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new String[0];
        }
    }

    @ProxyMethod("getCurrentTransport")
    public static class getCurrentTransport extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("acknowledgeFullBackupOrRestore")
    public static class acknowledgeFullBackupOrRestore extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("fullRestore")
    public static class fullRestore extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("fullTransportBackup")
    public static class fullTransportBackup extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("fullBackup")
    public static class fullBackup extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("backupNow")
    public static class backupNow extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("setBackupProvisioned")
    public static class setBackupProvisioned extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("setBackupEnabled")
    public static class setBackupEnabled extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("restoreAtInstall")
    public static class restoreAtInstall extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("agentDisconnected")
    public static class agentDisconnected extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("agentConnected")
    public static class agentConnected extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("clearBackupData")
    public static class clearBackupData extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("dataChanged")
    public static class dataChanged extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }
}
