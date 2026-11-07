package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIContentService {
  public static IContentServiceStatic getWithException() {
    return BlackReflection.create(IContentServiceStatic.class, null, true);
  }

  public static IContentServiceStatic get() {
    return BlackReflection.create(IContentServiceStatic.class, null, false);
  }

  public static IContentServiceContext getWithException(final Object caller) {
    return BlackReflection.create(IContentServiceContext.class, caller, true);
  }

  public static IContentServiceContext get(final Object caller) {
    return BlackReflection.create(IContentServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IContentServiceContext.class);
  }
}
