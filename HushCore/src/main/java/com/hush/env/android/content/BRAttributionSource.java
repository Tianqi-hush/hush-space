package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRAttributionSource {
  public static AttributionSourceStatic getWithException() {
    return BlackReflection.create(AttributionSourceStatic.class, null, true);
  }

  public static AttributionSourceStatic get() {
    return BlackReflection.create(AttributionSourceStatic.class, null, false);
  }

  public static AttributionSourceContext getWithException(final Object caller) {
    return BlackReflection.create(AttributionSourceContext.class, caller, true);
  }

  public static AttributionSourceContext get(final Object caller) {
    return BlackReflection.create(AttributionSourceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(AttributionSourceContext.class);
  }
}
