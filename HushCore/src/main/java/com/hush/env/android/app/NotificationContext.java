package com.hush.env.android.app;

import android.app.PendingIntent;
import android.content.Context;
import java.lang.CharSequence;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.Notification")
public interface NotificationContext {
  @BMethodCheckNotProcess
  Method _check_setLatestEventInfo(Context Context0, CharSequence CharSequence1,
      CharSequence CharSequence2, PendingIntent PendingIntent3);

  Void setLatestEventInfo(Context Context0, CharSequence CharSequence1, CharSequence CharSequence2,
      PendingIntent PendingIntent3);
}
