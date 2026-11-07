package com.hush.env.android.content.pm;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.pm.pkg.FrameworkPackageUserState")
public interface FrameworkPackageUserStateStatic {
  @BFieldSetNotProcess
  void _set_gDefault(final Object value);

  @BFieldCheckNotProcess
  Field _check_gDefault();

  @BFieldNotProcess
  Object gDefault();
}
