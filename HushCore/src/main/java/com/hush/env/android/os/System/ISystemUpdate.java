package com.hush.env.android.os.System;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;
import com.hush.reflection.annotation.BField;
import com.hush.reflection.annotation.BMethod;

@BClassName("android.os.ISystemUpdate")
public interface ISystemUpdate {

    @BClassName("android.os.ISystemUpdate$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder binder);
    }
}

