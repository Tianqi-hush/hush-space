package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParser {
  public static PackageParserStatic getWithException() {
    return BlackReflection.create(PackageParserStatic.class, null, true);
  }

  public static PackageParserStatic get() {
    return BlackReflection.create(PackageParserStatic.class, null, false);
  }

  public static PackageParserContext getWithException(final Object caller) {
    return BlackReflection.create(PackageParserContext.class, caller, true);
  }

  public static PackageParserContext get(final Object caller) {
    return BlackReflection.create(PackageParserContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserContext.class);
  }
}
