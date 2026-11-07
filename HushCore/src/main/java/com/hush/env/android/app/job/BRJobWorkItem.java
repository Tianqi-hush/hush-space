package com.hush.env.android.app.job;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRJobWorkItem {
  public static JobWorkItemStatic getWithException() {
    return BlackReflection.create(JobWorkItemStatic.class, null, true);
  }

  public static JobWorkItemStatic get() {
    return BlackReflection.create(JobWorkItemStatic.class, null, false);
  }

  public static JobWorkItemContext getWithException(final Object caller) {
    return BlackReflection.create(JobWorkItemContext.class, caller, true);
  }

  public static JobWorkItemContext get(final Object caller) {
    return BlackReflection.create(JobWorkItemContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(JobWorkItemContext.class);
  }
}
