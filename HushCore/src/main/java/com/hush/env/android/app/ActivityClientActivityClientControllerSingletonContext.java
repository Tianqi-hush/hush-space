package com.hush.env.android.app;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ActivityClient$ActivityClientControllerSingleton")
public interface ActivityClientActivityClientControllerSingletonContext {
  @BFieldSetNotProcess
  void _set_mKnownInstance(final Object value);

  @BFieldCheckNotProcess
  Field _check_mKnownInstance();

  @BFieldNotProcess
  IInterface mKnownInstance();
}
