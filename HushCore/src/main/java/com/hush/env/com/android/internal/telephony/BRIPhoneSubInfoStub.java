package com.hush.env.com.android.internal.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIPhoneSubInfoStub {
  public static IPhoneSubInfoStubStatic getWithException() {
    return BlackReflection.create(IPhoneSubInfoStubStatic.class, null, true);
  }

  public static IPhoneSubInfoStubStatic get() {
    return BlackReflection.create(IPhoneSubInfoStubStatic.class, null, false);
  }

  public static IPhoneSubInfoStubContext getWithException(final Object caller) {
    return BlackReflection.create(IPhoneSubInfoStubContext.class, caller, true);
  }

  public static IPhoneSubInfoStubContext get(final Object caller) {
    return BlackReflection.create(IPhoneSubInfoStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IPhoneSubInfoStubContext.class);
  }
}
