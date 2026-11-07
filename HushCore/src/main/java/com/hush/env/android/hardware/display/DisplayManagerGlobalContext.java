package com.hush.env.android.hardware.display;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.hardware.display.DisplayManagerGlobal")
public interface DisplayManagerGlobalContext {
  @BFieldSetNotProcess
  void _set_mDm(final Object value);

  @BFieldCheckNotProcess
  Field _check_mDm();

  @BFieldNotProcess
  IInterface mDm();
}
