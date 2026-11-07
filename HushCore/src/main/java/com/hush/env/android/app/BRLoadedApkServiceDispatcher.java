package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLoadedApkServiceDispatcher {
  public static LoadedApkServiceDispatcherStatic getWithException() {
    return BlackReflection.create(LoadedApkServiceDispatcherStatic.class, null, true);
  }

  public static LoadedApkServiceDispatcherStatic get() {
    return BlackReflection.create(LoadedApkServiceDispatcherStatic.class, null, false);
  }

  public static LoadedApkServiceDispatcherContext getWithException(final Object caller) {
    return BlackReflection.create(LoadedApkServiceDispatcherContext.class, caller, true);
  }

  public static LoadedApkServiceDispatcherContext get(final Object caller) {
    return BlackReflection.create(LoadedApkServiceDispatcherContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LoadedApkServiceDispatcherContext.class);
  }
}
