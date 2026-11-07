package com.hush.env.android.view.accessibility;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIAccessibilityManagerStub {
  public static IAccessibilityManagerStubStatic getWithException() {
    return BlackReflection.create(IAccessibilityManagerStubStatic.class, null, true);
  }

  public static IAccessibilityManagerStubStatic get() {
    return BlackReflection.create(IAccessibilityManagerStubStatic.class, null, false);
  }

  public static IAccessibilityManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IAccessibilityManagerStubContext.class, caller, true);
  }

  public static IAccessibilityManagerStubContext get(final Object caller) {
    return BlackReflection.create(IAccessibilityManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IAccessibilityManagerStubContext.class);
  }
}
