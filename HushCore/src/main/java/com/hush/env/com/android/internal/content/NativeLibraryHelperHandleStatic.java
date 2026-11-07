package com.hush.env.com.android.internal.content;

import java.io.File;
import java.lang.Object;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("com.android.internal.content.NativeLibraryHelper$Handle")
public interface NativeLibraryHelperHandleStatic {
  @BMethodCheckNotProcess
  Method _check_create(File File0);

  Object create(File File0);
}
