package com.hush.env.com.android.internal.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPhoneConstantsMtk {
  public static PhoneConstantsMtkStatic getWithException() {
    return BlackReflection.create(PhoneConstantsMtkStatic.class, null, true);
  }

  public static PhoneConstantsMtkStatic get() {
    return BlackReflection.create(PhoneConstantsMtkStatic.class, null, false);
  }

  public static PhoneConstantsMtkContext getWithException(final Object caller) {
    return BlackReflection.create(PhoneConstantsMtkContext.class, caller, true);
  }

  public static PhoneConstantsMtkContext get(final Object caller) {
    return BlackReflection.create(PhoneConstantsMtkContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PhoneConstantsMtkContext.class);
  }
}
