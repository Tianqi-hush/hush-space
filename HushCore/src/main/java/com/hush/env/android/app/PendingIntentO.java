package com.hush.env.android.app;

import android.os.IBinder;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BConstructor;

@BClassName("android.app.PendingIntent")
public interface PendingIntentO {
    @BConstructor
    PendingIntentO _new(IBinder IBinder0, Object Object1);
}
