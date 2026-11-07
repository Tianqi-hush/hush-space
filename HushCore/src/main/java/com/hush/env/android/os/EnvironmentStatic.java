package com.hush.env.android.os;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.os.Environment")
public interface EnvironmentStatic {
  @BFieldSetNotProcess
  void _set_sCurrentUser(final Object value);

  @BFieldCheckNotProcess
  Field _check_sCurrentUser();

  @BFieldNotProcess
  Object sCurrentUser();
}
