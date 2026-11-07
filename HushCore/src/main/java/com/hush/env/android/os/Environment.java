package com.hush.env.android.os;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticField;

@BClassName("android.os.Environment")
public interface Environment {

  @BStaticField
  Object sCurrentUser();
}