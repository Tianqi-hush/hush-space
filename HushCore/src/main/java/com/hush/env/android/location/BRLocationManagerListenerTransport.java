package com.hush.env.android.location;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLocationManagerListenerTransport {
  public static LocationManagerListenerTransportStatic getWithException() {
    return BlackReflection.create(LocationManagerListenerTransportStatic.class, null, true);
  }

  public static LocationManagerListenerTransportStatic get() {
    return BlackReflection.create(LocationManagerListenerTransportStatic.class, null, false);
  }

  public static LocationManagerListenerTransportContext getWithException(final Object caller) {
    return BlackReflection.create(LocationManagerListenerTransportContext.class, caller, true);
  }

  public static LocationManagerListenerTransportContext get(final Object caller) {
    return BlackReflection.create(LocationManagerListenerTransportContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LocationManagerListenerTransportContext.class);
  }
}
