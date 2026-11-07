package com.hush.env.android.media;

import java.lang.Boolean;
import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.media.IAudioService")
public interface IAudioServiceStatic {
  @BMethodCheckNotProcess
  Method _check_setMicrophoneMute(boolean mute, String callingPackage, int userId);

  Void setMicrophoneMute(boolean mute, String callingPackage, int userId);

  @BMethodCheckNotProcess
  Method _check_isMicrophoneMute();

  Boolean isMicrophoneMute();

  @BMethodCheckNotProcess
  Method _check_startBluetoothSco(String callingPackage);

  Void startBluetoothSco(String callingPackage);

  @BMethodCheckNotProcess
  Method _check_stopBluetoothSco(String callingPackage);

  Void stopBluetoothSco(String callingPackage);
}
