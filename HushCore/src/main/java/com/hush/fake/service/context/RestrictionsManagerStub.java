package com.hush.fake.service.context;

import android.content.Context;
import android.os.Bundle;

import com.hush.env.android.content.BRIRestrictionsManagerStub;
import com.hush.env.android.os.BRServiceManager;

import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;

/**
 * Fixed RestrictionsManager Stub
 * Crash-free for Play Store & GMS
 */
public class RestrictionsManagerStub extends BinderInvocationStub {

    public RestrictionsManagerStub() {
        super(BRServiceManager.get().getService(Context.RESTRICTIONS_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIRestrictionsManagerStub.get().asInterface(BRServiceManager.get().getService(Context.RESTRICTIONS_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.RESTRICTIONS_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // 🔥 MAIN FIX — DO NOT CALL SYSTEM
    @ProxyMethod("getApplicationRestrictions")
    public static class GetApplicationRestrictions extends MethodHook {
        @Override
        protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) {
            // Return fake empty restrictions
            return new Bundle();
        }
    }

    // 🔥 EXTRA SAFETY (Android 11+)
    @ProxyMethod("getApplicationRestrictionsForUser")
    public static class GetApplicationRestrictionsForUser extends MethodHook {
        @Override
        protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) {
            return new Bundle();
        }
    }
}