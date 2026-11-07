package com.hush.env.android.security.net.config;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRNetworkSecurityConfigProvider {
  public static NetworkSecurityConfigProviderStatic getWithException() {
    return BlackReflection.create(NetworkSecurityConfigProviderStatic.class, null, true);
  }

  public static NetworkSecurityConfigProviderStatic get() {
    return BlackReflection.create(NetworkSecurityConfigProviderStatic.class, null, false);
  }

  public static NetworkSecurityConfigProviderContext getWithException(final Object caller) {
    return BlackReflection.create(NetworkSecurityConfigProviderContext.class, caller, true);
  }

  public static NetworkSecurityConfigProviderContext get(final Object caller) {
    return BlackReflection.create(NetworkSecurityConfigProviderContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(NetworkSecurityConfigProviderContext.class);
  }
}
