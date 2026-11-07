package com.hush.utils.compat;

import android.os.IBinder;
import android.os.IInterface;

import com.hush.env.android.app.BRApplicationThreadNative;
import com.hush.env.android.app.BRIApplicationThreadOreoStub;

public class ApplicationThreadCompat {

    public static IInterface asInterface(IBinder binder) {
        if (BuildCompat.isOreo()) {
            return BRIApplicationThreadOreoStub.get().asInterface(binder);
        }
        return BRApplicationThreadNative.get().asInterface(binder);
    }
}
