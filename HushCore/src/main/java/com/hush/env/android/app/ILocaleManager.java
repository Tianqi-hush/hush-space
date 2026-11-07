package com.hush.env.android.app;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

/**
 * @author gm
 * @function
 * @date :2024/4/20 20:04
 **/
@BClassName("android.app.ILocaleManager")
public interface ILocaleManager {
    @BClassName("android.app.ILocaleManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
