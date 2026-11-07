package com.hush.env.android.hardware.display;

import java.lang.Object;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.hardware.display.DisplayManagerGlobal")
public interface DisplayManagerGlobalStatic {
  @BMethodCheckNotProcess
  Method _check_getInstance();

  Object getInstance();
}
