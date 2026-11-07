package com.hush.env.java.lang;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRThreadGroup {
  public static ThreadGroupStatic getWithException() {
    return BlackReflection.create(ThreadGroupStatic.class, null, true);
  }

  public static ThreadGroupStatic get() {
    return BlackReflection.create(ThreadGroupStatic.class, null, false);
  }

  public static ThreadGroupContext getWithException(final Object caller) {
    return BlackReflection.create(ThreadGroupContext.class, caller, true);
  }

  public static ThreadGroupContext get(final Object caller) {
    return BlackReflection.create(ThreadGroupContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ThreadGroupContext.class);
  }
}
