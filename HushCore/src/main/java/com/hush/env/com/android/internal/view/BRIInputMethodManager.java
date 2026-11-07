package com.hush.env.com.android.internal.view;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIInputMethodManager {
  public static IInputMethodManagerStatic getWithException() {
    return BlackReflection.create(IInputMethodManagerStatic.class, null, true);
  }

  public static IInputMethodManagerStatic get() {
    return BlackReflection.create(IInputMethodManagerStatic.class, null, false);
  }

  public static IInputMethodManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IInputMethodManagerContext.class, caller, true);
  }

  public static IInputMethodManagerContext get(final Object caller) {
    return BlackReflection.create(IInputMethodManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IInputMethodManagerContext.class);
  }
}
