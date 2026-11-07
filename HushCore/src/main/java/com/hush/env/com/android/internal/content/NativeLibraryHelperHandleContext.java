package com.hush.env.com.android.internal.content;

import java.lang.Boolean;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("com.android.internal.content.NativeLibraryHelper$Handle")
public interface NativeLibraryHelperHandleContext {
  @BFieldSetNotProcess
  void _set_extractNativeLibs(final Object value);

  @BFieldCheckNotProcess
  Field _check_extractNativeLibs();

  @BFieldNotProcess
  Boolean extractNativeLibs();
}
