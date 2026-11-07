package com.hush.env.android.bluetooth;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIBluetooth {
  public static IBluetoothStatic getWithException() {
    return BlackReflection.create(IBluetoothStatic.class, null, true);
  }

  public static IBluetoothStatic get() {
    return BlackReflection.create(IBluetoothStatic.class, null, false);
  }

  public static IBluetoothContext getWithException(final Object caller) {
    return BlackReflection.create(IBluetoothContext.class, caller, true);
  }

  public static IBluetoothContext get(final Object caller) {
    return BlackReflection.create(IBluetoothContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IBluetoothContext.class);
  }
}
