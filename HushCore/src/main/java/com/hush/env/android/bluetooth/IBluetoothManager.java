package com.hush.env.android.bluetooth;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.bluetooth.IBluetoothManager")
public interface IBluetoothManager {
    @BClassName("android.bluetooth.IBluetoothManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
