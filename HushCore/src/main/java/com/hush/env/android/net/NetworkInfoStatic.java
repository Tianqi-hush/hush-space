package com.hush.env.android.net;

import java.lang.String;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.net.NetworkInfo")
public interface NetworkInfoStatic {
  @BConstructorNotProcess
  NetworkInfo _new(int type);

  @BConstructorNotProcess
  NetworkInfo _new(int type, int subType, String typeName, String subTypeName);
}
