package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParserActivity {
  public static PackageParserActivityStatic getWithException() {
    return BlackReflection.create(PackageParserActivityStatic.class, null, true);
  }

  public static PackageParserActivityStatic get() {
    return BlackReflection.create(PackageParserActivityStatic.class, null, false);
  }

  public static PackageParserActivityContext getWithException(final Object caller) {
    return BlackReflection.create(PackageParserActivityContext.class, caller, true);
  }

  public static PackageParserActivityContext get(final Object caller) {
    return BlackReflection.create(PackageParserActivityContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserActivityContext.class);
  }
}
