package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRBroadcastReceiver {
  public static BroadcastReceiverStatic getWithException() {
    return BlackReflection.create(BroadcastReceiverStatic.class, null, true);
  }

  public static BroadcastReceiverStatic get() {
    return BlackReflection.create(BroadcastReceiverStatic.class, null, false);
  }

  public static BroadcastReceiverContext getWithException(final Object caller) {
    return BlackReflection.create(BroadcastReceiverContext.class, caller, true);
  }

  public static BroadcastReceiverContext get(final Object caller) {
    return BlackReflection.create(BroadcastReceiverContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(BroadcastReceiverContext.class);
  }
}
