package com.hush.env.com.android.internal.appwidget;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIAppWidgetServiceStub {
  public static IAppWidgetServiceStubStatic getWithException() {
    return BlackReflection.create(IAppWidgetServiceStubStatic.class, null, true);
  }

  public static IAppWidgetServiceStubStatic get() {
    return BlackReflection.create(IAppWidgetServiceStubStatic.class, null, false);
  }

  public static IAppWidgetServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IAppWidgetServiceStubContext.class, caller, true);
  }

  public static IAppWidgetServiceStubContext get(final Object caller) {
    return BlackReflection.create(IAppWidgetServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IAppWidgetServiceStubContext.class);
  }
}
