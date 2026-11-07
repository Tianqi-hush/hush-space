package com.hush.env.android.os.mount;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.os.storage.IMountService")
public interface IMountService {
    @BClassName("android.os.storage.IMountService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
