package com.hush.env.dalvik.system;

import java.lang.Boolean;
import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("dalvik.system.VMRuntime")
public interface VMRuntimeStatic {
  @BMethodCheckNotProcess
  Method _check_getCurrentInstructionSet();

  String getCurrentInstructionSet();

  @BMethodCheckNotProcess
  Method _check_getRuntime();

  Object getRuntime();

  @BMethodCheckNotProcess
  Method _check_is64BitAbi(String String0);

  Boolean is64BitAbi(String String0);
}
