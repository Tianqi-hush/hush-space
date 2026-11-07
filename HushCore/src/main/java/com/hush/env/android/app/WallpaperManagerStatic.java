package com.hush.env.android.app;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.WallpaperManager")
public interface WallpaperManagerStatic {
  @BFieldSetNotProcess
  void _set_sGlobals(final Object value);

  @BFieldCheckNotProcess
  Field _check_sGlobals();

  @BFieldNotProcess
  Object sGlobals();
}
