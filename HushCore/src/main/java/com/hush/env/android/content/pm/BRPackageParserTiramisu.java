package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParserTiramisu {
  public static PackageParserTiramisuStatic getWithException() {
    return BlackReflection.create(PackageParserTiramisuStatic.class, null, true);
  }

  public static PackageParserTiramisuStatic get() {
    return BlackReflection.create(PackageParserTiramisuStatic.class, null, false);
  }

  public static PackageParserTiramisuContext getWithException(final Object caller) {
    return BlackReflection.create(PackageParserTiramisuContext.class, caller, true);
  }

  public static PackageParserTiramisuContext get(final Object caller) {
    return BlackReflection.create(PackageParserTiramisuContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserTiramisuContext.class);
  }
}
