package com.hush.env.android.rms.resource;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRReceiverResourceO {
  public static ReceiverResourceOStatic getWithException() {
    return BlackReflection.create(ReceiverResourceOStatic.class, null, true);
  }

  public static ReceiverResourceOStatic get() {
    return BlackReflection.create(ReceiverResourceOStatic.class, null, false);
  }

  public static ReceiverResourceOContext getWithException(final Object caller) {
    return BlackReflection.create(ReceiverResourceOContext.class, caller, true);
  }

  public static ReceiverResourceOContext get(final Object caller) {
    return BlackReflection.create(ReceiverResourceOContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ReceiverResourceOContext.class);
  }
}
