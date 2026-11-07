package com.hush.env.android.location;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLocationManagerGnssStatusListenerTransportO {
  public static LocationManagerGnssStatusListenerTransportOStatic getWithException() {
    return BlackReflection.create(LocationManagerGnssStatusListenerTransportOStatic.class, null, true);
  }

  public static LocationManagerGnssStatusListenerTransportOStatic get() {
    return BlackReflection.create(LocationManagerGnssStatusListenerTransportOStatic.class, null, false);
  }

  public static LocationManagerGnssStatusListenerTransportOContext getWithException(final Object caller) {
    return BlackReflection.create(LocationManagerGnssStatusListenerTransportOContext.class, caller, true);
  }

  public static LocationManagerGnssStatusListenerTransportOContext get(final Object caller) {
    return BlackReflection.create(LocationManagerGnssStatusListenerTransportOContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LocationManagerGnssStatusListenerTransportOContext.class);
  }
}
