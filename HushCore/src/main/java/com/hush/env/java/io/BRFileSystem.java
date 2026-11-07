package com.hush.env.java.io;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRFileSystem {
  public static FileSystemStatic getWithException() {
    return BlackReflection.create(FileSystemStatic.class, null, true);
  }

  public static FileSystemStatic get() {
    return BlackReflection.create(FileSystemStatic.class, null, false);
  }

  public static FileSystemContext getWithException(final Object caller) {
    return BlackReflection.create(FileSystemContext.class, caller, true);
  }

  public static FileSystemContext get(final Object caller) {
    return BlackReflection.create(FileSystemContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(FileSystemContext.class);
  }
}
