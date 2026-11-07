package com.hush.fake.service.context.providers;

import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.util.Log;

import com.hush.HushCore;
import com.hush.utils.compat.ContextCompat;
import java.lang.reflect.Method;

import com.hush.env.android.content.BRAttributionSource;
import com.hush.app.BActivityThread;
import com.hush.fake.hook.ClassInvocationStub;

/**
 * HARD FINAL FIX
 * Android 10 → 16
 * Solves AttributionSource.enforceCallingUid crash
 */
public class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String TAG = "ContentProviderStub";
    private IInterface mBase;
    private String mAppPkg;

    public IInterface wrapper(final IInterface contentProviderProxy, final String appPkg) {
        mBase = contentProviderProxy;
        mAppPkg = appPkg;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        if (args != null && args.length > 0) {
            if (args[0] instanceof String) {
                args[0] = mAppPkg;
            }
            for (Object arg : args) {
                if (arg != null && arg.getClass().getName().equals(BRAttributionSource.getRealClass().getName())) {
                    ContextCompat.fixAttributionSourceState(arg, HushCore.getHostUid());
                }
            }
        }
        try {
            return method.invoke(mBase, args);
        } catch (Throwable e) {
            throw e.getCause();
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
