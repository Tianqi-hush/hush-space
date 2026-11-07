package com.hush.fake.service;

import android.content.pm.ProviderInfo;
import android.util.Log;
import com.hush.env.android.content.BRIClipboardStub;
import com.hush.env.android.os.BRServiceManager;
import java.lang.reflect.Method;
import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.utils.Slog;

public class IClipboardManagerProxy extends BinderInvocationStub {
    private static final String TAG = "IClipboardManagerProxy";

    public IClipboardManagerProxy() {
        super(BRServiceManager.get().getService("clipboard"));
    }

    public Object getWho() {
        return BRIClipboardStub.get().asInterface(BRServiceManager.get().getService("clipboard"));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        int argIndex = getPackNameIndex(args);
        if (argIndex != -1) {
            args[argIndex] = HushCore.getHostPkg();
        }
        return super.invoke(proxy, method, args);
    }

    private int getPackNameIndex(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                Log.d(TAG, "args[" + i + "] " + args[i]);
                return i;
            }
        }
        return -1;
    }

    public void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("clipboard");
    }

    public boolean isBadEnv() {
        return false;
    }
}
