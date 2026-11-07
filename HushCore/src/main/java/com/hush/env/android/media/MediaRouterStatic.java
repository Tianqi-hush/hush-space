package com.hush.env.android.media;

import android.content.Context;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.media.MediaRouter")
public interface MediaRouterStatic {
  @BMethodCheckNotProcess
  Method _check_getInstance(Context context);

  Object getInstance(Context context);

  @BFieldSetNotProcess
  void _set_sStatic(final Object value);

  @BFieldCheckNotProcess
  Field _check_sStatic();

  @BFieldNotProcess
  Object sStatic();
}
