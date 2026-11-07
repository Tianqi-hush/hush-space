package com.hush.env.android.net;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.net.IConnectivityManager")
public interface IConnectivityManager {
    @BClassName("android.net.IConnectivityManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder binder);
    }
}
