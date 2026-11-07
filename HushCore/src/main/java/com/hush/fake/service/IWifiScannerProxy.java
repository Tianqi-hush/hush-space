package com.hush.fake.service;

import android.content.Context;
import android.os.IBinder;

import com.hush.env.android.net.wifi.BRIWifiManagerStub;
import com.hush.env.android.os.BRServiceManager;
import com.hush.fake.hook.BinderInvocationStub;

/**
 * @author Findger
 * @function
 * @date :2022/4/3 13:05
 **/
public class IWifiScannerProxy extends BinderInvocationStub {

    public IWifiScannerProxy() {
        super(BRServiceManager.get().getService("wifiscanner"));
    }

    @Override
    protected Object getWho() {
        android.os.IBinder binder = BRServiceManager.get().getService("wifiscanner");
        if (binder == null) {
            return null;
        }
        return BRIWifiManagerStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("wifiscanner");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
