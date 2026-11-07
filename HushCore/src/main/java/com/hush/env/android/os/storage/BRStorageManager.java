package com.hush.env.android.os.storage;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRStorageManager {
  public static StorageManagerStatic getWithException() {
    return BlackReflection.create(StorageManagerStatic.class, null, true);
  }

  public static StorageManagerStatic get() {
    return BlackReflection.create(StorageManagerStatic.class, null, false);
  }

  public static StorageManagerContext getWithException(final Object caller) {
    return BlackReflection.create(StorageManagerContext.class, caller, true);
  }

  public static StorageManagerContext get(final Object caller) {
    return BlackReflection.create(StorageManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(StorageManagerContext.class);
  }
}
