package com.hush.env.com.android.internal.view;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIInputMethodManagerStub {
  public static IInputMethodManagerStubStatic getWithException() {
    return BlackReflection.create(IInputMethodManagerStubStatic.class, null, true);
  }

  public static IInputMethodManagerStubStatic get() {
    return BlackReflection.create(IInputMethodManagerStubStatic.class, null, false);
  }

  public static IInputMethodManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IInputMethodManagerStubContext.class, caller, true);
  }

  public static IInputMethodManagerStubContext get(final Object caller) {
    return BlackReflection.create(IInputMethodManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IInputMethodManagerStubContext.class);
  }
}
