package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRINetworkManagementServiceStub {
  public static INetworkManagementServiceStubStatic getWithException() {
    return BlackReflection.create(INetworkManagementServiceStubStatic.class, null, true);
  }

  public static INetworkManagementServiceStubStatic get() {
    return BlackReflection.create(INetworkManagementServiceStubStatic.class, null, false);
  }

  public static INetworkManagementServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(INetworkManagementServiceStubContext.class, caller, true);
  }

  public static INetworkManagementServiceStubContext get(final Object caller) {
    return BlackReflection.create(INetworkManagementServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(INetworkManagementServiceStubContext.class);
  }
}
