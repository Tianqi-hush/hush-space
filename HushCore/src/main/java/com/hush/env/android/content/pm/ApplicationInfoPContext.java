package com.hush.env.android.content.pm;

import java.lang.Object;
import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.pm.ApplicationInfo")
public interface ApplicationInfoPContext {
  @BMethodCheckNotProcess
  Method _check_setHiddenApiEnforcementPolicy(int int0);

  Void setHiddenApiEnforcementPolicy(int int0);

  @BFieldSetNotProcess
  void _set_splitClassLoaderNames(final Object value);

  @BFieldCheckNotProcess
  Field _check_splitClassLoaderNames();

  @BFieldNotProcess
  String[] splitClassLoaderNames();
}
