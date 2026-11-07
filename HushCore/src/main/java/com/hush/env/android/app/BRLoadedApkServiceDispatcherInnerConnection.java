package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLoadedApkServiceDispatcherInnerConnection {
  public static LoadedApkServiceDispatcherInnerConnectionStatic getWithException() {
    return BlackReflection.create(LoadedApkServiceDispatcherInnerConnectionStatic.class, null, true);
  }

  public static LoadedApkServiceDispatcherInnerConnectionStatic get() {
    return BlackReflection.create(LoadedApkServiceDispatcherInnerConnectionStatic.class, null, false);
  }

  public static LoadedApkServiceDispatcherInnerConnectionContext getWithException(final Object caller) {
    return BlackReflection.create(LoadedApkServiceDispatcherInnerConnectionContext.class, caller, true);
  }

  public static LoadedApkServiceDispatcherInnerConnectionContext get(final Object caller) {
    return BlackReflection.create(LoadedApkServiceDispatcherInnerConnectionContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LoadedApkServiceDispatcherInnerConnectionContext.class);
  }
}
