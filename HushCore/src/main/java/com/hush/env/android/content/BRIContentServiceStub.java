package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIContentServiceStub {
  public static IContentServiceStubStatic getWithException() {
    return BlackReflection.create(IContentServiceStubStatic.class, null, true);
  }

  public static IContentServiceStubStatic get() {
    return BlackReflection.create(IContentServiceStubStatic.class, null, false);
  }

  public static IContentServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IContentServiceStubContext.class, caller, true);
  }

  public static IContentServiceStubContext get(final Object caller) {
    return BlackReflection.create(IContentServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IContentServiceStubContext.class);
  }
}
