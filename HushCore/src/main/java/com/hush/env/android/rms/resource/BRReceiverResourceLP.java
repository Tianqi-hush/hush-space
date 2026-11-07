package com.hush.env.android.rms.resource;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRReceiverResourceLP {
  public static ReceiverResourceLPStatic getWithException() {
    return BlackReflection.create(ReceiverResourceLPStatic.class, null, true);
  }

  public static ReceiverResourceLPStatic get() {
    return BlackReflection.create(ReceiverResourceLPStatic.class, null, false);
  }

  public static ReceiverResourceLPContext getWithException(final Object caller) {
    return BlackReflection.create(ReceiverResourceLPContext.class, caller, true);
  }

  public static ReceiverResourceLPContext get(final Object caller) {
    return BlackReflection.create(ReceiverResourceLPContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ReceiverResourceLPContext.class);
  }
}
