package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIIntentReceiver {
  public static IIntentReceiverStatic getWithException() {
    return BlackReflection.create(IIntentReceiverStatic.class, null, true);
  }

  public static IIntentReceiverStatic get() {
    return BlackReflection.create(IIntentReceiverStatic.class, null, false);
  }

  public static IIntentReceiverContext getWithException(final Object caller) {
    return BlackReflection.create(IIntentReceiverContext.class, caller, true);
  }

  public static IIntentReceiverContext get(final Object caller) {
    return BlackReflection.create(IIntentReceiverContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IIntentReceiverContext.class);
  }
}
