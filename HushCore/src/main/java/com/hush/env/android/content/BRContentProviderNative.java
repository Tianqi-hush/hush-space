package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRContentProviderNative {
  public static ContentProviderNativeStatic getWithException() {
    return BlackReflection.create(ContentProviderNativeStatic.class, null, true);
  }

  public static ContentProviderNativeStatic get() {
    return BlackReflection.create(ContentProviderNativeStatic.class, null, false);
  }

  public static ContentProviderNativeContext getWithException(final Object caller) {
    return BlackReflection.create(ContentProviderNativeContext.class, caller, true);
  }

  public static ContentProviderNativeContext get(final Object caller) {
    return BlackReflection.create(ContentProviderNativeContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ContentProviderNativeContext.class);
  }
}
