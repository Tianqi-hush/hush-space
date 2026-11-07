package com.hush.env.android.app;

import android.content.Intent;
import android.os.IBinder;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BMethod;

@BClassName("android.app.IActivityManager")
public interface IActivityManagerL {
    @BMethod
    Boolean finishActivity(IBinder IBinder0, int int1, Intent Intent2, boolean boolean3);
}
