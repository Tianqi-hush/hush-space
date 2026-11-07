package com.hush.fake.service;

import android.content.Context;
import android.os.IBinder;

import com.hush.env.android.app.BRILocaleManager;
import com.hush.env.android.app.BRILocaleManagerStub;
import com.hush.env.android.os.BRServiceManager;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.service.base.PkgMethodProxy;

/**
 * @author gm
 * @function
 * @date :2024/4/20 20:06
 **/
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService("locale"));
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new com.hush.fake.hook.MethodHook() {
            @Override
            protected String getMethodName() {
                return "setApplicationLocales";
            }

            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                try {
                    com.hush.utils.MethodParameterUtils.replaceFirstAppPkg(args);
                    return method.invoke(who, args);
                } catch (Throwable t) {
                    return null;
                }
            }
        });
        addMethodHook(new com.hush.fake.hook.MethodHook() {
            @Override
            protected String getMethodName() {
                return "getApplicationLocales";
            }

            @Override
            protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
                try {
                    com.hush.utils.MethodParameterUtils.replaceFirstAppPkg(args);
                    return method.invoke(who, args);
                } catch (Throwable t) {
                    try {
                        return android.os.LocaleList.getEmptyLocaleList();
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        });
    }

    @Override
    protected Object getWho() {
        return BRILocaleManagerStub.get().asInterface(BRServiceManager.get().getService("locale"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("locale");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
