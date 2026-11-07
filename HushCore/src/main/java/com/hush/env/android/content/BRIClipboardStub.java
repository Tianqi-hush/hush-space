package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIClipboardStub {
  public static IClipboardStubStatic getWithException() {
    return BlackReflection.create(IClipboardStubStatic.class, null, true);
  }

  public static IClipboardStubStatic get() {
    return BlackReflection.create(IClipboardStubStatic.class, null, false);
  }

  public static IClipboardStubContext getWithException(final Object caller) {
    return BlackReflection.create(IClipboardStubContext.class, caller, true);
  }

  public static IClipboardStubContext get(final Object caller) {
    return BlackReflection.create(IClipboardStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IClipboardStubContext.class);
  }
}
