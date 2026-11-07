package com.hush.env.android.ddm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRDdmHandleAppName {
  public static DdmHandleAppNameStatic getWithException() {
    return BlackReflection.create(DdmHandleAppNameStatic.class, null, true);
  }

  public static DdmHandleAppNameStatic get() {
    return BlackReflection.create(DdmHandleAppNameStatic.class, null, false);
  }

  public static DdmHandleAppNameContext getWithException(final Object caller) {
    return BlackReflection.create(DdmHandleAppNameContext.class, caller, true);
  }

  public static DdmHandleAppNameContext get(final Object caller) {
    return BlackReflection.create(DdmHandleAppNameContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(DdmHandleAppNameContext.class);
  }
}
