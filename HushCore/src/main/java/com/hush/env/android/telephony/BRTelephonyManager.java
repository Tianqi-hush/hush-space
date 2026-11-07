package com.hush.env.android.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRTelephonyManager {
  public static TelephonyManagerStatic getWithException() {
    return BlackReflection.create(TelephonyManagerStatic.class, null, true);
  }

  public static TelephonyManagerStatic get() {
    return BlackReflection.create(TelephonyManagerStatic.class, null, false);
  }

  public static TelephonyManagerContext getWithException(final Object caller) {
    return BlackReflection.create(TelephonyManagerContext.class, caller, true);
  }

  public static TelephonyManagerContext get(final Object caller) {
    return BlackReflection.create(TelephonyManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(TelephonyManagerContext.class);
  }
}
