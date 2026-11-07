package com.hush.env.android.content;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.ContentResolver")
public interface ContentResolverStatic {
  @BFieldSetNotProcess
  void _set_sContentService(final Object value);

  @BFieldCheckNotProcess
  Field _check_sContentService();

  @BFieldNotProcess
  IInterface sContentService();
}
