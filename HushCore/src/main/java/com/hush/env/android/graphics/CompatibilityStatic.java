package com.hush.env.android.graphics;

import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.graphics.Compatibility")
public interface CompatibilityStatic {
  @BMethodCheckNotProcess
  Method _check_setTargetSdkVersion(int targetSdkVersion);

  Void setTargetSdkVersion(int targetSdkVersion);
}
