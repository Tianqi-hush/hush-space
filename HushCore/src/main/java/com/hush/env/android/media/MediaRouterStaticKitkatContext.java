package com.hush.env.android.media;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.media.MediaRouter$Static")
public interface MediaRouterStaticKitkatContext {
  @BFieldSetNotProcess
  void _set_mMediaRouterService(final Object value);

  @BFieldCheckNotProcess
  Field _check_mMediaRouterService();

  @BFieldNotProcess
  IInterface mMediaRouterService();
}
