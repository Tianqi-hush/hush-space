package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParserSigningDetails {
  public static PackageParserSigningDetailsStatic getWithException() {
    return BlackReflection.create(PackageParserSigningDetailsStatic.class, null, true);
  }

  public static PackageParserSigningDetailsStatic get() {
    return BlackReflection.create(PackageParserSigningDetailsStatic.class, null, false);
  }

  public static PackageParserSigningDetailsContext getWithException(final Object caller) {
    return BlackReflection.create(PackageParserSigningDetailsContext.class, caller, true);
  }

  public static PackageParserSigningDetailsContext get(final Object caller) {
    return BlackReflection.create(PackageParserSigningDetailsContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserSigningDetailsContext.class);
  }
}
