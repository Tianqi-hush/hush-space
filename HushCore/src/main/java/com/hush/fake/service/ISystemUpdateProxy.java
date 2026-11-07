package com.hush.fake.service;


import com.hush.env.android.os.BRServiceManager;
import com.hush.env.android.view.BRIAutoFillManagerStub;
import com.hush.fake.hook.BinderInvocationStub;

/**
 * @author Findger
 * @function
 * @date :2022/4/2 21:59
 **/
public class ISystemUpdateProxy extends BinderInvocationStub {
    public ISystemUpdateProxy() {
        super(BRServiceManager.get().getService("system_update"));
    }

    @Override
    protected Object getWho() {
        android.os.IBinder service = BRServiceManager.get().getService("system_update");
        if (service == null) {
            return null;
        }
        return android.os.ISystemUpdateManager.Stub.asInterface(service);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("system_update");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
