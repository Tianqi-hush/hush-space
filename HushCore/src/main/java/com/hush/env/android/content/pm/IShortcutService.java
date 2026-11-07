package com.hush.env.android.content.pm;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.content.pm.IShortcutService")
public interface IShortcutService {
    @BClassName("android.content.pm.IShortcutService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
