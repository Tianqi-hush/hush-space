package com.hush.env.com.android.internal.os;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.os.IVibratorService$Stub")
public interface IVibratorServiceStubStatic {
  @BMethodCheckNotProcess
  Method _check_asInterface(IBinder IBinder0);

  IInterface asInterface(IBinder IBinder0);
}
