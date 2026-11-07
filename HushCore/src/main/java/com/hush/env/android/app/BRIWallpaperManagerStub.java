package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIWallpaperManagerStub {
  public static IWallpaperManagerStubStatic getWithException() {
    return BlackReflection.create(IWallpaperManagerStubStatic.class, null, true);
  }

  public static IWallpaperManagerStubStatic get() {
    return BlackReflection.create(IWallpaperManagerStubStatic.class, null, false);
  }

  public static IWallpaperManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IWallpaperManagerStubContext.class, caller, true);
  }

  public static IWallpaperManagerStubContext get(final Object caller) {
    return BlackReflection.create(IWallpaperManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IWallpaperManagerStubContext.class);
  }
}
