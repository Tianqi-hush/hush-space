package com.hush.env.android.net.wifi;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.net.wifi.WifiScanner")
public interface WifiScannerStatic {
  @BFieldSetNotProcess
  void _set_GET_AVAILABLE_CHANNELS_EXTRA(final Object value);

  @BFieldCheckNotProcess
  Field _check_GET_AVAILABLE_CHANNELS_EXTRA();

  @BFieldNotProcess
  String GET_AVAILABLE_CHANNELS_EXTRA();
}
