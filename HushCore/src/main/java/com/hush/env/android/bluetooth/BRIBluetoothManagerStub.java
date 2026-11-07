package com.hush.env.android.bluetooth;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIBluetoothManagerStub {
  public static IBluetoothManagerStubStatic getWithException() {
    return BlackReflection.create(IBluetoothManagerStubStatic.class, null, true);
  }

  public static IBluetoothManagerStubStatic get() {
    return BlackReflection.create(IBluetoothManagerStubStatic.class, null, false);
  }

  public static IBluetoothManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IBluetoothManagerStubContext.class, caller, true);
  }

  public static IBluetoothManagerStubContext get(final Object caller) {
    return BlackReflection.create(IBluetoothManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IBluetoothManagerStubContext.class);
  }
}
