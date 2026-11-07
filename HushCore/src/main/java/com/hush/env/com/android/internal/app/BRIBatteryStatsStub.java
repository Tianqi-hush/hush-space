package com.hush.env.com.android.internal.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIBatteryStatsStub {
  public static IBatteryStatsStubStatic getWithException() {
    return BlackReflection.create(IBatteryStatsStubStatic.class, null, true);
  }

  public static IBatteryStatsStubStatic get() {
    return BlackReflection.create(IBatteryStatsStubStatic.class, null, false);
  }

  public static IBatteryStatsStubContext getWithException(final Object caller) {
    return BlackReflection.create(IBatteryStatsStubContext.class, caller, true);
  }

  public static IBatteryStatsStubContext get(final Object caller) {
    return BlackReflection.create(IBatteryStatsStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IBatteryStatsStubContext.class);
  }
}
