package com.hush.env.android.app;

import java.io.File;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ContextImpl")
public interface ContextImplICSContext {
  @BFieldSetNotProcess
  void _set_mExternalCacheDir(final Object value);

  @BFieldCheckNotProcess
  Field _check_mExternalCacheDir();

  @BFieldNotProcess
  File mExternalCacheDir();

  @BFieldSetNotProcess
  void _set_mExternalFilesDir(final Object value);

  @BFieldCheckNotProcess
  Field _check_mExternalFilesDir();

  @BFieldNotProcess
  File mExternalFilesDir();
}
