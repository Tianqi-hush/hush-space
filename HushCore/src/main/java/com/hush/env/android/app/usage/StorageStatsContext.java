package com.hush.env.android.app.usage;

import java.lang.Long;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.usage.StorageStats")
public interface StorageStatsContext {
  @BFieldSetNotProcess
  void _set_cacheBytes(final Object value);

  @BFieldCheckNotProcess
  Field _check_cacheBytes();

  @BFieldNotProcess
  Long cacheBytes();

  @BFieldSetNotProcess
  void _set_codeBytes(final Object value);

  @BFieldCheckNotProcess
  Field _check_codeBytes();

  @BFieldNotProcess
  Long codeBytes();

  @BFieldSetNotProcess
  void _set_dataBytes(final Object value);

  @BFieldCheckNotProcess
  Field _check_dataBytes();

  @BFieldNotProcess
  Long dataBytes();
}
