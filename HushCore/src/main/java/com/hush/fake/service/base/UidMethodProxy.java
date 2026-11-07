package com.hush.fake.service.base;

import java.lang.reflect.Method;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.fake.hook.MethodHook;

/**
 * Created by BlackBox on 2022/3/5.
 */
public class UidMethodProxy extends MethodHook {
    private final int index;
    private final String name;

    public UidMethodProxy(String name, int index) {
        this.index = index;
        this.name = name;
    }

    @Override
    protected String getMethodName() {
        return name;
    }

    @Override
    protected Object hook(Object who, Method method, Object[] args) throws Throwable {
        int uid = (int) args[index];
        if (uid == BActivityThread.getBUid() || uid == BActivityThread.getBAppId()) {
            args[index] = HushCore.getHostUid();
        }
        return method.invoke(who, args);
    }
}
