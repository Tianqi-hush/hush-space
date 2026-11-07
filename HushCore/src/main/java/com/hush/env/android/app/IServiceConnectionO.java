package com.hush.env.android.app;

import android.content.ComponentName;
import android.os.IBinder;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BMethod;

@BClassName("android.app.IServiceConnection")
public interface IServiceConnectionO {
    @BMethod
    void connected(ComponentName ComponentName0, IBinder IBinder1, boolean boolean2);
}
