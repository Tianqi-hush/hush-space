package com.hush.env.android.content;

import java.lang.Integer;
import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.AttributionSourceState")
public interface AttributionSourceStateContext {
  @BFieldSetNotProcess
  void _set_packageName(final Object value);

  @BFieldCheckNotProcess
  Field _check_packageName();

  @BFieldNotProcess
  String packageName();

  @BFieldSetNotProcess
  void _set_pid(final Object value);

  @BFieldCheckNotProcess
  Field _check_pid();

  @BFieldNotProcess
  Integer pid();

  @BFieldSetNotProcess
  void _set_uid(final Object value);

  @BFieldCheckNotProcess
  Field _check_uid();

  @BFieldNotProcess
  Integer uid();
}
