package com.hush.env.android.net;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIConnectivityManager {
  public static IConnectivityManagerStatic getWithException() {
    return BlackReflection.create(IConnectivityManagerStatic.class, null, true);
  }

  public static IConnectivityManagerStatic get() {
    return BlackReflection.create(IConnectivityManagerStatic.class, null, false);
  }

  public static IConnectivityManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IConnectivityManagerContext.class, caller, true);
  }

  public static IConnectivityManagerContext get(final Object caller) {
    return BlackReflection.create(IConnectivityManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IConnectivityManagerContext.class);
  }
}
