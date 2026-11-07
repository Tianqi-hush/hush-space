package com.hush.env.android.bluetooth;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIBluetoothStub {
  public static IBluetoothStubStatic getWithException() {
    return BlackReflection.create(IBluetoothStubStatic.class, null, true);
  }

  public static IBluetoothStubStatic get() {
    return BlackReflection.create(IBluetoothStubStatic.class, null, false);
  }

  public static IBluetoothStubContext getWithException(final Object caller) {
    return BlackReflection.create(IBluetoothStubContext.class, caller, true);
  }

  public static IBluetoothStubContext get(final Object caller) {
    return BlackReflection.create(IBluetoothStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IBluetoothStubContext.class);
  }
}
