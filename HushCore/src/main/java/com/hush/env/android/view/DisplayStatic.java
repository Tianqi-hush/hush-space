package com.hush.env.android.view;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.view.Display")
public interface DisplayStatic {
  @BFieldSetNotProcess
  void _set_sWindowManager(final Object value);

  @BFieldCheckNotProcess
  Field _check_sWindowManager();

  @BFieldNotProcess
  IInterface sWindowManager();
}
