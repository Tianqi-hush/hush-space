package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRClipboardManagerOreo {
  public static ClipboardManagerOreoStatic getWithException() {
    return BlackReflection.create(ClipboardManagerOreoStatic.class, null, true);
  }

  public static ClipboardManagerOreoStatic get() {
    return BlackReflection.create(ClipboardManagerOreoStatic.class, null, false);
  }

  public static ClipboardManagerOreoContext getWithException(final Object caller) {
    return BlackReflection.create(ClipboardManagerOreoContext.class, caller, true);
  }

  public static ClipboardManagerOreoContext get(final Object caller) {
    return BlackReflection.create(ClipboardManagerOreoContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ClipboardManagerOreoContext.class);
  }
}
