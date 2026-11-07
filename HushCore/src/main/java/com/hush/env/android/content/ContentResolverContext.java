package com.hush.env.android.content;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.ContentResolver")
public interface ContentResolverContext {
  @BFieldSetNotProcess
  void _set_mPackageName(final Object value);

  @BFieldCheckNotProcess
  Field _check_mPackageName();

  @BFieldNotProcess
  String mPackageName();
}
