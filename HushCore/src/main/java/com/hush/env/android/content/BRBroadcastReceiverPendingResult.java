package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRBroadcastReceiverPendingResult {
  public static BroadcastReceiverPendingResultStatic getWithException() {
    return BlackReflection.create(BroadcastReceiverPendingResultStatic.class, null, true);
  }

  public static BroadcastReceiverPendingResultStatic get() {
    return BlackReflection.create(BroadcastReceiverPendingResultStatic.class, null, false);
  }

  public static BroadcastReceiverPendingResultContext getWithException(final Object caller) {
    return BlackReflection.create(BroadcastReceiverPendingResultContext.class, caller, true);
  }

  public static BroadcastReceiverPendingResultContext get(final Object caller) {
    return BlackReflection.create(BroadcastReceiverPendingResultContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(BroadcastReceiverPendingResultContext.class);
  }
}
