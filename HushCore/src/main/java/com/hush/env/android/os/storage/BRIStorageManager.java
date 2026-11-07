package com.hush.env.android.os.storage;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIStorageManager {
  public static IStorageManagerStatic getWithException() {
    return BlackReflection.create(IStorageManagerStatic.class, null, true);
  }

  public static IStorageManagerStatic get() {
    return BlackReflection.create(IStorageManagerStatic.class, null, false);
  }

  public static IStorageManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IStorageManagerContext.class, caller, true);
  }

  public static IStorageManagerContext get(final Object caller) {
    return BlackReflection.create(IStorageManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IStorageManagerContext.class);
  }
}
