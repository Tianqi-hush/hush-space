package com.hush.env.android.media;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.media.MediaRouter$Static")
public interface MediaRouterStaticContext {
  @BFieldSetNotProcess
  void _set_mAudioService(final Object value);

  @BFieldCheckNotProcess
  Field _check_mAudioService();

  @BFieldNotProcess
  IInterface mAudioService();
}
