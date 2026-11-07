package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageParserLollipop22 {
  public static PackageParserLollipop22Static getWithException() {
    return BlackReflection.create(PackageParserLollipop22Static.class, null, true);
  }

  public static PackageParserLollipop22Static get() {
    return BlackReflection.create(PackageParserLollipop22Static.class, null, false);
  }

  public static PackageParserLollipop22Context getWithException(final Object caller) {
    return BlackReflection.create(PackageParserLollipop22Context.class, caller, true);
  }

  public static PackageParserLollipop22Context get(final Object caller) {
    return BlackReflection.create(PackageParserLollipop22Context.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageParserLollipop22Context.class);
  }
}
