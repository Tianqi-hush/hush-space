package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSharedPreferencesImpl {
  public static SharedPreferencesImplStatic getWithException() {
    return BlackReflection.create(SharedPreferencesImplStatic.class, null, true);
  }

  public static SharedPreferencesImplStatic get() {
    return BlackReflection.create(SharedPreferencesImplStatic.class, null, false);
  }

  public static SharedPreferencesImplContext getWithException(final Object caller) {
    return BlackReflection.create(SharedPreferencesImplContext.class, caller, true);
  }

  public static SharedPreferencesImplContext get(final Object caller) {
    return BlackReflection.create(SharedPreferencesImplContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SharedPreferencesImplContext.class);
  }
}
