package com.hush.env.android.net.wifi;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIWifiManagerStub {
  public static IWifiManagerStubStatic getWithException() {
    return BlackReflection.create(IWifiManagerStubStatic.class, null, true);
  }

  public static IWifiManagerStubStatic get() {
    return BlackReflection.create(IWifiManagerStubStatic.class, null, false);
  }

  public static IWifiManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IWifiManagerStubContext.class, caller, true);
  }

  public static IWifiManagerStubContext get(final Object caller) {
    return BlackReflection.create(IWifiManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IWifiManagerStubContext.class);
  }
}
