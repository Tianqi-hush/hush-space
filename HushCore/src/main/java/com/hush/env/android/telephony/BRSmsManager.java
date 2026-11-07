package com.hush.env.android.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSmsManager {
  public static SmsManagerStatic getWithException() {
    return BlackReflection.create(SmsManagerStatic.class, null, true);
  }

  public static SmsManagerStatic get() {
    return BlackReflection.create(SmsManagerStatic.class, null, false);
  }

  public static SmsManagerContext getWithException(final Object caller) {
    return BlackReflection.create(SmsManagerContext.class, caller, true);
  }

  public static SmsManagerContext get(final Object caller) {
    return BlackReflection.create(SmsManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SmsManagerContext.class);
  }
}
