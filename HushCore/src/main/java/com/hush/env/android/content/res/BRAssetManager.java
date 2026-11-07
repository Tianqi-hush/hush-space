package com.hush.env.android.content.res;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRAssetManager {
  public static AssetManagerStatic getWithException() {
    return BlackReflection.create(AssetManagerStatic.class, null, true);
  }

  public static AssetManagerStatic get() {
    return BlackReflection.create(AssetManagerStatic.class, null, false);
  }

  public static AssetManagerContext getWithException(final Object caller) {
    return BlackReflection.create(AssetManagerContext.class, caller, true);
  }

  public static AssetManagerContext get(final Object caller) {
    return BlackReflection.create(AssetManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(AssetManagerContext.class);
  }
}
