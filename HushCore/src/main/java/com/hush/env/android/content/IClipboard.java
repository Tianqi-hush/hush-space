package com.hush.env.android.content;

import android.os.IBinder;
import android.os.IInterface;
import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.content.IClipboard")
public interface IClipboard {

    @BClassName("android.content.IClipboard$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder iBinder);
    }
}
