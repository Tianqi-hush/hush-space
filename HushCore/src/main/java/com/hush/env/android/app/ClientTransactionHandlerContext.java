package com.hush.env.android.app;

import android.os.IBinder;
import java.lang.Object;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.ClientTransactionHandler")
public interface ClientTransactionHandlerContext {
  @BMethodCheckNotProcess
  Method _check_getActivityClient(IBinder IBinder0);

  Object getActivityClient(IBinder IBinder0);
}
