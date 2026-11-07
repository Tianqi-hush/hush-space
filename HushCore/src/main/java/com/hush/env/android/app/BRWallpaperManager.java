package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRWallpaperManager {
  public static WallpaperManagerStatic getWithException() {
    return BlackReflection.create(WallpaperManagerStatic.class, null, true);
  }

  public static WallpaperManagerStatic get() {
    return BlackReflection.create(WallpaperManagerStatic.class, null, false);
  }

  public static WallpaperManagerContext getWithException(final Object caller) {
    return BlackReflection.create(WallpaperManagerContext.class, caller, true);
  }

  public static WallpaperManagerContext get(final Object caller) {
    return BlackReflection.create(WallpaperManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(WallpaperManagerContext.class);
  }
}
