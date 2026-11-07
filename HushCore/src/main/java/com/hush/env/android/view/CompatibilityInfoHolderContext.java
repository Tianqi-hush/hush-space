package com.hush.env.android.view;

import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.view.CompatibilityInfoHolder")
public interface CompatibilityInfoHolderContext {
  @BMethodCheckNotProcess
  Method _check_set();

  Void set();
}
