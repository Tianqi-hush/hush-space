package com.hush.fake.service;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import com.hush.env.android.os.BRIVibratorManagerServiceStub;
import com.hush.env.android.os.BRServiceManager;
import com.hush.env.com.android.internal.os.BRIVibratorServiceStub;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.utils.MethodParameterUtils;
import com.hush.utils.compat.BuildCompat;

/**
 * Created by BlackBox on 2022/3/7.
 */
public class IVibratorServiceProxy extends BinderInvocationStub {
    private static final String NAME;
    static {
        if (BuildCompat.isS_V2()) {
            NAME = "vibrator_manager";
        } else {
            NAME = Context.VIBRATOR_SERVICE;
        }
    }

    public IVibratorServiceProxy() {
        super(BRServiceManager.get().getService(NAME));
    }

    @Override
    protected Object getWho() {
        IBinder service = BRServiceManager.get().getService(NAME);
        if (BuildCompat.isS()) {
            return BRIVibratorManagerServiceStub.get().asInterface(service);
        }
        return BRIVibratorServiceStub.get().asInterface(service);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceFirstUid(args);
        MethodParameterUtils.replaceFirstAppPkg(args);
        return super.invoke(proxy, method, args);
    }
}
