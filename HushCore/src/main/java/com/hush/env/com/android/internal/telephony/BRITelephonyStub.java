package com.hush.env.com.android.internal.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRITelephonyStub {
  public static ITelephonyStubStatic getWithException() {
    return BlackReflection.create(ITelephonyStubStatic.class, null, true);
  }

  public static ITelephonyStubStatic get() {
    return BlackReflection.create(ITelephonyStubStatic.class, null, false);
  }

  public static ITelephonyStubContext getWithException(final Object caller) {
    return BlackReflection.create(ITelephonyStubContext.class, caller, true);
  }

  public static ITelephonyStubContext get(final Object caller) {
    return BlackReflection.create(ITelephonyStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ITelephonyStubContext.class);
  }
}
