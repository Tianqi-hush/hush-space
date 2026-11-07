package com.hush.env.com.cosmos.apm.framework.page;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivityLifeCycleHelper$ApplicationInstrumentation {
  public static ActivityLifeCycleHelper$ApplicationInstrumentationStatic getWithException() {
    return BlackReflection.create(ActivityLifeCycleHelper$ApplicationInstrumentationStatic.class, null, true);
  }

  public static ActivityLifeCycleHelper$ApplicationInstrumentationStatic get() {
    return BlackReflection.create(ActivityLifeCycleHelper$ApplicationInstrumentationStatic.class, null, false);
  }

  public static ActivityLifeCycleHelper$ApplicationInstrumentationContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityLifeCycleHelper$ApplicationInstrumentationContext.class, caller, true);
  }

  public static ActivityLifeCycleHelper$ApplicationInstrumentationContext get(final Object caller) {
    return BlackReflection.create(ActivityLifeCycleHelper$ApplicationInstrumentationContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityLifeCycleHelper$ApplicationInstrumentationContext.class);
  }
}
