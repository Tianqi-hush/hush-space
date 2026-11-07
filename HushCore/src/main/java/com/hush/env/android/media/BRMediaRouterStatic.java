package com.hush.env.android.media;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRMediaRouterStatic {
  public static MediaRouterStaticStatic getWithException() {
    return BlackReflection.create(MediaRouterStaticStatic.class, null, true);
  }

  public static MediaRouterStaticStatic get() {
    return BlackReflection.create(MediaRouterStaticStatic.class, null, false);
  }

  public static MediaRouterStaticContext getWithException(final Object caller) {
    return BlackReflection.create(MediaRouterStaticContext.class, caller, true);
  }

  public static MediaRouterStaticContext get(final Object caller) {
    return BlackReflection.create(MediaRouterStaticContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(MediaRouterStaticContext.class);
  }
}
