package com.hush.env.android.app;

import android.os.IBinder;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.app.PendingIntent")
public interface PendingIntentJBMR2Static {
  @BConstructorNotProcess
  PendingIntentJBMR2 _new(IBinder IBinder0);
}
