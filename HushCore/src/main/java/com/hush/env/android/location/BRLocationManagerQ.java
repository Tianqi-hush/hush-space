package com.hush.env.android.location;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLocationManagerQ {
  public static LocationManagerQStatic getWithException() {
    return BlackReflection.create(LocationManagerQStatic.class, null, true);
  }

  public static LocationManagerQStatic get() {
    return BlackReflection.create(LocationManagerQStatic.class, null, false);
  }

  public static LocationManagerQContext getWithException(final Object caller) {
    return BlackReflection.create(LocationManagerQContext.class, caller, true);
  }

  public static LocationManagerQContext get(final Object caller) {
    return BlackReflection.create(LocationManagerQContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LocationManagerQContext.class);
  }
}
