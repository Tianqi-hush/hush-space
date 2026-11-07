package com.hush.env.android.net.wifi;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRWifiSsid {
  public static WifiSsidStatic getWithException() {
    return BlackReflection.create(WifiSsidStatic.class, null, true);
  }

  public static WifiSsidStatic get() {
    return BlackReflection.create(WifiSsidStatic.class, null, false);
  }

  public static WifiSsidContext getWithException(final Object caller) {
    return BlackReflection.create(WifiSsidContext.class, caller, true);
  }

  public static WifiSsidContext get(final Object caller) {
    return BlackReflection.create(WifiSsidContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(WifiSsidContext.class);
  }
}
