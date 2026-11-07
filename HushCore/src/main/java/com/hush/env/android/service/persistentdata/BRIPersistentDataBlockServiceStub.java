package com.hush.env.android.service.persistentdata;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIPersistentDataBlockServiceStub {
  public static IPersistentDataBlockServiceStubStatic getWithException() {
    return BlackReflection.create(IPersistentDataBlockServiceStubStatic.class, null, true);
  }

  public static IPersistentDataBlockServiceStubStatic get() {
    return BlackReflection.create(IPersistentDataBlockServiceStubStatic.class, null, false);
  }

  public static IPersistentDataBlockServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IPersistentDataBlockServiceStubContext.class, caller, true);
  }

  public static IPersistentDataBlockServiceStubContext get(final Object caller) {
    return BlackReflection.create(IPersistentDataBlockServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IPersistentDataBlockServiceStubContext.class);
  }
}
