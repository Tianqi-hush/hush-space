package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIApplicationThreadOreoStub {
  public static IApplicationThreadOreoStubStatic getWithException() {
    return BlackReflection.create(IApplicationThreadOreoStubStatic.class, null, true);
  }

  public static IApplicationThreadOreoStubStatic get() {
    return BlackReflection.create(IApplicationThreadOreoStubStatic.class, null, false);
  }

  public static IApplicationThreadOreoStubContext getWithException(final Object caller) {
    return BlackReflection.create(IApplicationThreadOreoStubContext.class, caller, true);
  }

  public static IApplicationThreadOreoStubContext get(final Object caller) {
    return BlackReflection.create(IApplicationThreadOreoStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IApplicationThreadOreoStubContext.class);
  }
}
