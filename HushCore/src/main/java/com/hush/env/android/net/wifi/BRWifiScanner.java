package com.hush.env.android.net.wifi;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRWifiScanner {
  public static WifiScannerStatic getWithException() {
    return BlackReflection.create(WifiScannerStatic.class, null, true);
  }

  public static WifiScannerStatic get() {
    return BlackReflection.create(WifiScannerStatic.class, null, false);
  }

  public static WifiScannerContext getWithException(final Object caller) {
    return BlackReflection.create(WifiScannerContext.class, caller, true);
  }

  public static WifiScannerContext get(final Object caller) {
    return BlackReflection.create(WifiScannerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(WifiScannerContext.class);
  }
}
