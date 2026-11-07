package com.hush.env.android.view.accessibility;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIAccessibilityManager {
  public static IAccessibilityManagerStatic getWithException() {
    return BlackReflection.create(IAccessibilityManagerStatic.class, null, true);
  }

  public static IAccessibilityManagerStatic get() {
    return BlackReflection.create(IAccessibilityManagerStatic.class, null, false);
  }

  public static IAccessibilityManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IAccessibilityManagerContext.class, caller, true);
  }

  public static IAccessibilityManagerContext get(final Object caller) {
    return BlackReflection.create(IAccessibilityManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IAccessibilityManagerContext.class);
  }
}
