package com.hush.env.android.app;

import android.os.IBinder;
import java.lang.Void;
import java.lang.reflect.Method;
import java.util.List;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.ActivityThread")
public interface ActivityThreadNMR1Context {
  @BMethodCheckNotProcess
  Method _check_performNewIntents(IBinder IBinder0, List List1, boolean boolean2);

  Void performNewIntents(IBinder IBinder0, List List1, boolean boolean2);
}
