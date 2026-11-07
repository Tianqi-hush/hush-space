package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRAttributionSourceState {
  public static AttributionSourceStateStatic getWithException() {
    return BlackReflection.create(AttributionSourceStateStatic.class, null, true);
  }

  public static AttributionSourceStateStatic get() {
    return BlackReflection.create(AttributionSourceStateStatic.class, null, false);
  }

  public static AttributionSourceStateContext getWithException(final Object caller) {
    return BlackReflection.create(AttributionSourceStateContext.class, caller, true);
  }

  public static AttributionSourceStateContext get(final Object caller) {
    return BlackReflection.create(AttributionSourceStateContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(AttributionSourceStateContext.class);
  }
}
