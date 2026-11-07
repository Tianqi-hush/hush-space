package com.hush.env.android.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRNeighboringCellInfo {
  public static NeighboringCellInfoStatic getWithException() {
    return BlackReflection.create(NeighboringCellInfoStatic.class, null, true);
  }

  public static NeighboringCellInfoStatic get() {
    return BlackReflection.create(NeighboringCellInfoStatic.class, null, false);
  }

  public static NeighboringCellInfoContext getWithException(final Object caller) {
    return BlackReflection.create(NeighboringCellInfoContext.class, caller, true);
  }

  public static NeighboringCellInfoContext get(final Object caller) {
    return BlackReflection.create(NeighboringCellInfoContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(NeighboringCellInfoContext.class);
  }
}
