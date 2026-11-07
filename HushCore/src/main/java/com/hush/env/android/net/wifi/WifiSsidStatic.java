package com.hush.env.android.net.wifi;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.net.wifi.WifiSsid")
public interface WifiSsidStatic {
  @BMethodCheckNotProcess
  Method _check_createFromAsciiEncoded(String asciiEncoded);

  Object createFromAsciiEncoded(String asciiEncoded);
}
