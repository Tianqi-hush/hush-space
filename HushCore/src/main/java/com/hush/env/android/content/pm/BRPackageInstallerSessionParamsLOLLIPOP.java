package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPackageInstallerSessionParamsLOLLIPOP {
  public static PackageInstallerSessionParamsLOLLIPOPStatic getWithException() {
    return BlackReflection.create(PackageInstallerSessionParamsLOLLIPOPStatic.class, null, true);
  }

  public static PackageInstallerSessionParamsLOLLIPOPStatic get() {
    return BlackReflection.create(PackageInstallerSessionParamsLOLLIPOPStatic.class, null, false);
  }

  public static PackageInstallerSessionParamsLOLLIPOPContext getWithException(final Object caller) {
    return BlackReflection.create(PackageInstallerSessionParamsLOLLIPOPContext.class, caller, true);
  }

  public static PackageInstallerSessionParamsLOLLIPOPContext get(final Object caller) {
    return BlackReflection.create(PackageInstallerSessionParamsLOLLIPOPContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PackageInstallerSessionParamsLOLLIPOPContext.class);
  }
}
