package com.hush.env.dalvik.system;

import java.lang.Boolean;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("dalvik.system.VMRuntime")
public interface VMRuntimeContext {
  @BMethodCheckNotProcess
  Method _check_is64Bit();

  Boolean is64Bit();

  @BMethodCheckNotProcess
  Method _check_isJavaDebuggable();

  Boolean isJavaDebuggable();

  @BMethodCheckNotProcess
  Method _check_setTargetSdkVersion(int int0);

  Void setTargetSdkVersion(int int0);
}
