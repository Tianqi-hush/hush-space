package com.hush.env.android.nfc;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRINfcAdapterStub {
  public static INfcAdapterStubStatic getWithException() {
    return BlackReflection.create(INfcAdapterStubStatic.class, null, true);
  }

  public static INfcAdapterStubStatic get() {
    return BlackReflection.create(INfcAdapterStubStatic.class, null, false);
  }

  public static INfcAdapterStubContext getWithException(final Object caller) {
    return BlackReflection.create(INfcAdapterStubContext.class, caller, true);
  }

  public static INfcAdapterStubContext get(final Object caller) {
    return BlackReflection.create(INfcAdapterStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(INfcAdapterStubContext.class);
  }
}
