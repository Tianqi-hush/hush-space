package com.hush.env.android.media;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIAudioServiceStub {
  public static IAudioServiceStubStatic getWithException() {
    return BlackReflection.create(IAudioServiceStubStatic.class, null, true);
  }

  public static IAudioServiceStubStatic get() {
    return BlackReflection.create(IAudioServiceStubStatic.class, null, false);
  }

  public static IAudioServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IAudioServiceStubContext.class, caller, true);
  }

  public static IAudioServiceStubContext get(final Object caller) {
    return BlackReflection.create(IAudioServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IAudioServiceStubContext.class);
  }
}
