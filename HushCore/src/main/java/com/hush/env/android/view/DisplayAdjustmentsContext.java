package com.hush.env.android.view;

import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.view.DisplayAdjustments")
public interface DisplayAdjustmentsContext {
  @BMethodCheckNotProcess
  Method _check_setCompatibilityInfo();

  Void setCompatibilityInfo();
}
