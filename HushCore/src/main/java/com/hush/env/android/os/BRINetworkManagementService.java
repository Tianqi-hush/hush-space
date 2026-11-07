package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRINetworkManagementService {
  public static INetworkManagementServiceStatic getWithException() {
    return BlackReflection.create(INetworkManagementServiceStatic.class, null, true);
  }

  public static INetworkManagementServiceStatic get() {
    return BlackReflection.create(INetworkManagementServiceStatic.class, null, false);
  }

  public static INetworkManagementServiceContext getWithException(final Object caller) {
    return BlackReflection.create(INetworkManagementServiceContext.class, caller, true);
  }

  public static INetworkManagementServiceContext get(final Object caller) {
    return BlackReflection.create(INetworkManagementServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(INetworkManagementServiceContext.class);
  }
}
