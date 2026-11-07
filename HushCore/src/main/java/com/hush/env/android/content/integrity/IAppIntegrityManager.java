package com.hush.env.android.content.integrity;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

/**
 * @author gm
 * @function
 * @date :2024/4/23 16:12
 **/
@BClassName("android.content.integrity.IAppIntegrityManager")
public interface IAppIntegrityManager {
    @BClassName("android.content.integrity.IAppIntegrityManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
