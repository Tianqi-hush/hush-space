package com.hush.env.android.app;

import java.lang.Object;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.ContextImpl")
public interface ContextImplStatic {
  @BMethodCheckNotProcess
  Method _check_createAppContext();

  Object createAppContext();
}
