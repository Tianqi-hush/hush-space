package com.hush.env.android.app;

import android.app.Notification;
import android.content.Context;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.Notification$Builder")
public interface NotificationLBuilderStatic {
  @BMethodCheckNotProcess
  Method _check_rebuild(Context Context0, Notification Notification1);

  Notification rebuild(Context Context0, Notification Notification1);
}
