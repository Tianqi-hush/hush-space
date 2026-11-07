package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParserNougat {
  public static PackageParserNougatStatic getWithException() {
    return BlackReflection.create(PackageParserNougatStatic.class, null, true);
  }

  public static PackageParserNougatStatic get() {
    return BlackReflection.create(PackageParserNougatStatic.class, null, false);
  }

  public static PackageParserNougatContext getWithException(final Object caller) {
    return BlackReflection.create(PackageParserNougatContext.class, caller, true);
  }

  public static PackageParserNougatContext get(final Object caller) {
    return BlackReflection.create(PackageParserNougatContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserNougatContext.class);
  }
}
