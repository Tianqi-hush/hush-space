package com.hush.env.android.app;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.app.ApplicationThreadNative")
public interface ApplicationThreadNative {
    @BStaticMethod
    IInterface asInterface(IBinder IBinder0);
}
