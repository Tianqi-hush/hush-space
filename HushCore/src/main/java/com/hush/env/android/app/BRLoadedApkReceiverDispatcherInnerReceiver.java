package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLoadedApkReceiverDispatcherInnerReceiver {
  public static LoadedApkReceiverDispatcherInnerReceiverStatic getWithException() {
    return BlackReflection.create(LoadedApkReceiverDispatcherInnerReceiverStatic.class, null, true);
  }

  public static LoadedApkReceiverDispatcherInnerReceiverStatic get() {
    return BlackReflection.create(LoadedApkReceiverDispatcherInnerReceiverStatic.class, null, false);
  }

  public static LoadedApkReceiverDispatcherInnerReceiverContext getWithException(final Object caller) {
    return BlackReflection.create(LoadedApkReceiverDispatcherInnerReceiverContext.class, caller, true);
  }

  public static LoadedApkReceiverDispatcherInnerReceiverContext get(final Object caller) {
    return BlackReflection.create(LoadedApkReceiverDispatcherInnerReceiverContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LoadedApkReceiverDispatcherInnerReceiverContext.class);
  }
}
