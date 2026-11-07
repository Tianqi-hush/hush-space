package com.hush.env.java.io;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("java.io.File")
public interface FileStatic {
  @BFieldSetNotProcess
  void _set_fs(final Object value);

  @BFieldCheckNotProcess
  Field _check_fs();

  @BFieldNotProcess
  Object fs();
}
