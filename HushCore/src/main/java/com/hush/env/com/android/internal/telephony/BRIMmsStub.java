package com.hush.env.com.android.internal.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIMmsStub {
  public static IMmsStubStatic getWithException() {
    return BlackReflection.create(IMmsStubStatic.class, null, true);
  }

  public static IMmsStubStatic get() {
    return BlackReflection.create(IMmsStubStatic.class, null, false);
  }

  public static IMmsStubContext getWithException(final Object caller) {
    return BlackReflection.create(IMmsStubContext.class, caller, true);
  }

  public static IMmsStubContext get(final Object caller) {
    return BlackReflection.create(IMmsStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IMmsStubContext.class);
  }
}
