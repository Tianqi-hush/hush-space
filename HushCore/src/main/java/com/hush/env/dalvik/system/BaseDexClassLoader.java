package com.hush.env.dalvik.system;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

@BClassName("dalvik.system.BaseDexClassLoader")
public interface BaseDexClassLoader {

  @BField
  Object sharedLibraryLoaders();
}
