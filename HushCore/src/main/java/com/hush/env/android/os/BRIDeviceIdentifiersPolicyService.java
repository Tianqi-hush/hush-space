package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIDeviceIdentifiersPolicyService {
  public static IDeviceIdentifiersPolicyServiceStatic getWithException() {
    return BlackReflection.create(IDeviceIdentifiersPolicyServiceStatic.class, null, true);
  }

  public static IDeviceIdentifiersPolicyServiceStatic get() {
    return BlackReflection.create(IDeviceIdentifiersPolicyServiceStatic.class, null, false);
  }

  public static IDeviceIdentifiersPolicyServiceContext getWithException(final Object caller) {
    return BlackReflection.create(IDeviceIdentifiersPolicyServiceContext.class, caller, true);
  }

  public static IDeviceIdentifiersPolicyServiceContext get(final Object caller) {
    return BlackReflection.create(IDeviceIdentifiersPolicyServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IDeviceIdentifiersPolicyServiceContext.class);
  }
}
