package com.hush.env.android.media;

import android.content.Context;
import android.os.IInterface;
import java.lang.Boolean;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.media.AudioManager")
public interface AudioManagerStatic {
  @BMethodCheckNotProcess
  Method _check_getService(Context context);

  Object getService(Context context);

  @BMethodCheckNotProcess
  Method _check_isStreamMute(int streamType);

  Boolean isStreamMute(int streamType);

  @BFieldSetNotProcess
  void _set_sService(final Object value);

  @BFieldCheckNotProcess
  Field _check_sService();

  @BFieldNotProcess
  IInterface sService();
}
