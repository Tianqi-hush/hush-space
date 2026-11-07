package com.hush.env.android.accounts;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.accounts.IAccountManager")
public interface IAccountManager {
    @BClassName("android.accounts.IAccountManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
