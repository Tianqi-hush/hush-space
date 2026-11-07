package com.hush.env.android.location;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLocationManagerGpsStatusListenerTransportVIVO {
  public static LocationManagerGpsStatusListenerTransportVIVOStatic getWithException() {
    return BlackReflection.create(LocationManagerGpsStatusListenerTransportVIVOStatic.class, null, true);
  }

  public static LocationManagerGpsStatusListenerTransportVIVOStatic get() {
    return BlackReflection.create(LocationManagerGpsStatusListenerTransportVIVOStatic.class, null, false);
  }

  public static LocationManagerGpsStatusListenerTransportVIVOContext getWithException(final Object caller) {
    return BlackReflection.create(LocationManagerGpsStatusListenerTransportVIVOContext.class, caller, true);
  }

  public static LocationManagerGpsStatusListenerTransportVIVOContext get(final Object caller) {
    return BlackReflection.create(LocationManagerGpsStatusListenerTransportVIVOContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LocationManagerGpsStatusListenerTransportVIVOContext.class);
  }
}
