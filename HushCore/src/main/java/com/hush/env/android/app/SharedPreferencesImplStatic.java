package com.hush.env.android.app;

import java.io.File;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.app.SharedPreferencesImpl")
public interface SharedPreferencesImplStatic {
  @BConstructorNotProcess
  SharedPreferencesImpl _new(File File0, int int1);
}
