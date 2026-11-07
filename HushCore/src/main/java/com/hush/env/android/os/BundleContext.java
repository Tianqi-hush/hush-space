package com.hush.env.android.os;

import android.os.IBinder;
import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.os.Bundle")
public interface BundleContext {
  @BMethodCheckNotProcess
  Method _check_getIBinder(String String0);

  IBinder getIBinder(String String0);

  @BMethodCheckNotProcess
  Method _check_putIBinder(String String0, IBinder IBinder1);

  Void putIBinder(String String0, IBinder IBinder1);
}
