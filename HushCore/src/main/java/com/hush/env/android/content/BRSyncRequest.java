package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSyncRequest {
  public static SyncRequestStatic getWithException() {
    return BlackReflection.create(SyncRequestStatic.class, null, true);
  }

  public static SyncRequestStatic get() {
    return BlackReflection.create(SyncRequestStatic.class, null, false);
  }

  public static SyncRequestContext getWithException(final Object caller) {
    return BlackReflection.create(SyncRequestContext.class, caller, true);
  }

  public static SyncRequestContext get(final Object caller) {
    return BlackReflection.create(SyncRequestContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SyncRequestContext.class);
  }
}
