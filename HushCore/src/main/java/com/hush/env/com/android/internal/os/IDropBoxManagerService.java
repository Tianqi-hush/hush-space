package com.hush.env.com.android.internal.os;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("com.android.internal.os.IDropBoxManagerService")
public interface IDropBoxManagerService {
    @BClassName("com.android.internal.os.IDropBoxManagerService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
