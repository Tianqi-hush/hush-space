package com.hush.env.android.content;

import java.lang.Object;
import java.lang.String;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.content.AttributionSource")
public interface AttributionSourceStatic {
  @BConstructorNotProcess
  Object _new(int i, int i2, String str, String str2);

  @BConstructorNotProcess
  Object _new(int i, String str, String str2);
}
