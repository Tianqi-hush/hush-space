package com.hush.env.com.android.internal.policy;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("com.android.internal.policy.PhoneWindow$WindowManagerHolder")
public interface PhoneWindowStatic {
  @BFieldSetNotProcess
  void _set_sWindowManager(final Object value);

  @BFieldCheckNotProcess
  Field _check_sWindowManager();

  @BFieldNotProcess
  IInterface sWindowManager();
}
