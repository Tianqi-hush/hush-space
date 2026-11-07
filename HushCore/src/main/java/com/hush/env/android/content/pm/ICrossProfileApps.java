package com.hush.env.android.content.pm;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

/**
 * @author gm
 * @function
 * @date :2024/4/20 22:14
 **/
@BClassName("android.content.pm.ICrossProfileApps")
public interface ICrossProfileApps {
    @BClassName("android.content.pm.ICrossProfileApps$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
