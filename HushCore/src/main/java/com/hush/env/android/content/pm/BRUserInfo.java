package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRUserInfo {
  public static UserInfoStatic getWithException() {
    return BlackReflection.create(UserInfoStatic.class, null, true);
  }

  public static UserInfoStatic get() {
    return BlackReflection.create(UserInfoStatic.class, null, false);
  }

  public static UserInfoContext getWithException(final Object caller) {
    return BlackReflection.create(UserInfoContext.class, caller, true);
  }

  public static UserInfoContext get(final Object caller) {
    return BlackReflection.create(UserInfoContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(UserInfoContext.class);
  }
}
