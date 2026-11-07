package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParserService {
  public static PackageParserServiceStatic getWithException() {
    return BlackReflection.create(PackageParserServiceStatic.class, null, true);
  }

  public static PackageParserServiceStatic get() {
    return BlackReflection.create(PackageParserServiceStatic.class, null, false);
  }

  public static PackageParserServiceContext getWithException(final Object caller) {
    return BlackReflection.create(PackageParserServiceContext.class, caller, true);
  }

  public static PackageParserServiceContext get(final Object caller) {
    return BlackReflection.create(PackageParserServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserServiceContext.class);
  }
}
