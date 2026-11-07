package com.hush.env.android.net.wifi;

import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.net.wifi.WifiInfo")
public interface WifiInfoStatic {
  @BConstructorNotProcess
  WifiInfo _new();
}
