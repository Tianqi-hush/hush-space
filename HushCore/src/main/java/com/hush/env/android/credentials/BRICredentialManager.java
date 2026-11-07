package com.hush.env.android.credentials;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRICredentialManager {
  public static ICredentialManagerStatic getWithException() {
    return BlackReflection.create(ICredentialManagerStatic.class, null, true);
  }

  public static ICredentialManagerStatic get() {
    return BlackReflection.create(ICredentialManagerStatic.class, null, false);
  }

  public static ICredentialManagerContext getWithException(final Object caller) {
    return BlackReflection.create(ICredentialManagerContext.class, caller, true);
  }

  public static ICredentialManagerContext get(final Object caller) {
    return BlackReflection.create(ICredentialManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ICredentialManagerContext.class);
  }
}
