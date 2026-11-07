package com.hush.env.android.bluetooth;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

/**
 * @author gm
 * @function
 * @date :2024/4/23 15:24
 **/
@BClassName("android.bluetooth.IBluetooth")
public interface IBluetooth {
    @BClassName("android.bluetooth.IBluetooth$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
