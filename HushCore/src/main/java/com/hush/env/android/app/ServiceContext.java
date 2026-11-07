package com.hush.env.android.app;

import android.app.Application;
import android.content.Context;
import android.os.IBinder;
import java.lang.Object;
import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;
import com.hush.reflection.annotation.BParamClass;

@BClassNameNotProcess("android.app.Service")
public interface ServiceContext {
  @BMethodCheckNotProcess
  Method _check_attach(Context context,
      @BParamClass(android.app.ActivityThread.class) Object thread, String className, IBinder token,
      Application application, Object activityManager);

  Void attach(Context context, @BParamClass(android.app.ActivityThread.class) Object thread,
      String className, IBinder token, Application application, Object activityManager);
}
