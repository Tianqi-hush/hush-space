package com.hush.env.android.os.storage;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIStorageManagerStub {
  public static IStorageManagerStubStatic getWithException() {
    return BlackReflection.create(IStorageManagerStubStatic.class, null, true);
  }

  public static IStorageManagerStubStatic get() {
    return BlackReflection.create(IStorageManagerStubStatic.class, null, false);
  }

  public static IStorageManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IStorageManagerStubContext.class, caller, true);
  }

  public static IStorageManagerStubContext get(final Object caller) {
    return BlackReflection.create(IStorageManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IStorageManagerStubContext.class);
  }
}
