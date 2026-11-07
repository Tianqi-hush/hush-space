package com.hush.env.com.android.internal.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRNativeLibraryHelperHandle {
  public static NativeLibraryHelperHandleStatic getWithException() {
    return BlackReflection.create(NativeLibraryHelperHandleStatic.class, null, true);
  }

  public static NativeLibraryHelperHandleStatic get() {
    return BlackReflection.create(NativeLibraryHelperHandleStatic.class, null, false);
  }

  public static NativeLibraryHelperHandleContext getWithException(final Object caller) {
    return BlackReflection.create(NativeLibraryHelperHandleContext.class, caller, true);
  }

  public static NativeLibraryHelperHandleContext get(final Object caller) {
    return BlackReflection.create(NativeLibraryHelperHandleContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(NativeLibraryHelperHandleContext.class);
  }
}
