package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRBroadcastReceiverPendingResultM {
  public static BroadcastReceiverPendingResultMStatic getWithException() {
    return BlackReflection.create(BroadcastReceiverPendingResultMStatic.class, null, true);
  }

  public static BroadcastReceiverPendingResultMStatic get() {
    return BlackReflection.create(BroadcastReceiverPendingResultMStatic.class, null, false);
  }

  public static BroadcastReceiverPendingResultMContext getWithException(final Object caller) {
    return BlackReflection.create(BroadcastReceiverPendingResultMContext.class, caller, true);
  }

  public static BroadcastReceiverPendingResultMContext get(final Object caller) {
    return BlackReflection.create(BroadcastReceiverPendingResultMContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(BroadcastReceiverPendingResultMContext.class);
  }
}
