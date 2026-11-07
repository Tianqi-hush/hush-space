package com.hush.env.android.rms.resource;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRReceiverResourceN {
  public static ReceiverResourceNStatic getWithException() {
    return BlackReflection.create(ReceiverResourceNStatic.class, null, true);
  }

  public static ReceiverResourceNStatic get() {
    return BlackReflection.create(ReceiverResourceNStatic.class, null, false);
  }

  public static ReceiverResourceNContext getWithException(final Object caller) {
    return BlackReflection.create(ReceiverResourceNContext.class, caller, true);
  }

  public static ReceiverResourceNContext get(final Object caller) {
    return BlackReflection.create(ReceiverResourceNContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ReceiverResourceNContext.class);
  }
}
