package com.hush.env.android.renderscript;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRRenderScriptCacheDir {
  public static RenderScriptCacheDirStatic getWithException() {
    return BlackReflection.create(RenderScriptCacheDirStatic.class, null, true);
  }

  public static RenderScriptCacheDirStatic get() {
    return BlackReflection.create(RenderScriptCacheDirStatic.class, null, false);
  }

  public static RenderScriptCacheDirContext getWithException(final Object caller) {
    return BlackReflection.create(RenderScriptCacheDirContext.class, caller, true);
  }

  public static RenderScriptCacheDirContext get(final Object caller) {
    return BlackReflection.create(RenderScriptCacheDirContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(RenderScriptCacheDirContext.class);
  }
}
