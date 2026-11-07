package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParserProvider {
  public static PackageParserProviderStatic getWithException() {
    return BlackReflection.create(PackageParserProviderStatic.class, null, true);
  }

  public static PackageParserProviderStatic get() {
    return BlackReflection.create(PackageParserProviderStatic.class, null, false);
  }

  public static PackageParserProviderContext getWithException(final Object caller) {
    return BlackReflection.create(PackageParserProviderContext.class, caller, true);
  }

  public static PackageParserProviderContext get(final Object caller) {
    return BlackReflection.create(PackageParserProviderContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserProviderContext.class);
  }
}
