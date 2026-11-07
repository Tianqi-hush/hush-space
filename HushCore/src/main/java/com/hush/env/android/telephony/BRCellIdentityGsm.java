package com.hush.env.android.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRCellIdentityGsm {
  public static CellIdentityGsmStatic getWithException() {
    return BlackReflection.create(CellIdentityGsmStatic.class, null, true);
  }

  public static CellIdentityGsmStatic get() {
    return BlackReflection.create(CellIdentityGsmStatic.class, null, false);
  }

  public static CellIdentityGsmContext getWithException(final Object caller) {
    return BlackReflection.create(CellIdentityGsmContext.class, caller, true);
  }

  public static CellIdentityGsmContext get(final Object caller) {
    return BlackReflection.create(CellIdentityGsmContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(CellIdentityGsmContext.class);
  }
}
