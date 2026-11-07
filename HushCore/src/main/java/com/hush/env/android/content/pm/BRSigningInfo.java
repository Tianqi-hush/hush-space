package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSigningInfo {
  public static SigningInfoStatic getWithException() {
    return BlackReflection.create(SigningInfoStatic.class, null, true);
  }

  public static SigningInfoStatic get() {
    return BlackReflection.create(SigningInfoStatic.class, null, false);
  }

  public static SigningInfoContext getWithException(final Object caller) {
    return BlackReflection.create(SigningInfoContext.class, caller, true);
  }

  public static SigningInfoContext get(final Object caller) {
    return BlackReflection.create(SigningInfoContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SigningInfoContext.class);
  }
}
