package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIClipboard {
  public static IClipboardStatic getWithException() {
    return BlackReflection.create(IClipboardStatic.class, null, true);
  }

  public static IClipboardStatic get() {
    return BlackReflection.create(IClipboardStatic.class, null, false);
  }

  public static IClipboardContext getWithException(final Object caller) {
    return BlackReflection.create(IClipboardContext.class, caller, true);
  }

  public static IClipboardContext get(final Object caller) {
    return BlackReflection.create(IClipboardContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IClipboardContext.class);
  }
}
