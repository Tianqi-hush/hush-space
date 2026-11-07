package com.hush.env.android.os;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.os.Build")
public interface BuildStatic {
  @BFieldSetNotProcess
  void _set_BOARD(final Object value);

  @BFieldCheckNotProcess
  Field _check_BOARD();

  @BFieldNotProcess
  String BOARD();

  @BFieldSetNotProcess
  void _set_BRAND(final Object value);

  @BFieldCheckNotProcess
  Field _check_BRAND();

  @BFieldNotProcess
  String BRAND();

  @BFieldSetNotProcess
  void _set_DEVICE(final Object value);

  @BFieldCheckNotProcess
  Field _check_DEVICE();

  @BFieldNotProcess
  String DEVICE();

  @BFieldSetNotProcess
  void _set_DISPLAY(final Object value);

  @BFieldCheckNotProcess
  Field _check_DISPLAY();

  @BFieldNotProcess
  String DISPLAY();

  @BFieldSetNotProcess
  void _set_HOST(final Object value);

  @BFieldCheckNotProcess
  Field _check_HOST();

  @BFieldNotProcess
  String HOST();

  @BFieldSetNotProcess
  void _set_ID(final Object value);

  @BFieldCheckNotProcess
  Field _check_ID();

  @BFieldNotProcess
  String ID();

  @BFieldSetNotProcess
  void _set_MANUFACTURER(final Object value);

  @BFieldCheckNotProcess
  Field _check_MANUFACTURER();

  @BFieldNotProcess
  String MANUFACTURER();

  @BFieldSetNotProcess
  void _set_MODEL(final Object value);

  @BFieldCheckNotProcess
  Field _check_MODEL();

  @BFieldNotProcess
  String MODEL();

  @BFieldSetNotProcess
  void _set_PRODUCT(final Object value);

  @BFieldCheckNotProcess
  Field _check_PRODUCT();

  @BFieldNotProcess
  String PRODUCT();

  @BFieldSetNotProcess
  void _set_TAGS(final Object value);

  @BFieldCheckNotProcess
  Field _check_TAGS();

  @BFieldNotProcess
  String TAGS();

  @BFieldSetNotProcess
  void _set_TYPE(final Object value);

  @BFieldCheckNotProcess
  Field _check_TYPE();

  @BFieldNotProcess
  String TYPE();

  @BFieldSetNotProcess
  void _set_USER(final Object value);

  @BFieldCheckNotProcess
  Field _check_USER();

  @BFieldNotProcess
  String USER();
}
