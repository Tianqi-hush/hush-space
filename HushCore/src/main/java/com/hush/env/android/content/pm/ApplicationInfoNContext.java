package com.hush.env.android.content.pm;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.pm.ApplicationInfo")
public interface ApplicationInfoNContext {
  @BFieldSetNotProcess
  void _set_credentialEncryptedDataDir(final Object value);

  @BFieldCheckNotProcess
  Field _check_credentialEncryptedDataDir();

  @BFieldNotProcess
  String credentialEncryptedDataDir();

  @BFieldSetNotProcess
  void _set_credentialProtectedDataDir(final Object value);

  @BFieldCheckNotProcess
  Field _check_credentialProtectedDataDir();

  @BFieldNotProcess
  String credentialProtectedDataDir();

  @BFieldSetNotProcess
  void _set_deviceEncryptedDataDir(final Object value);

  @BFieldCheckNotProcess
  Field _check_deviceEncryptedDataDir();

  @BFieldNotProcess
  String deviceEncryptedDataDir();

  @BFieldSetNotProcess
  void _set_deviceProtectedDataDir(final Object value);

  @BFieldCheckNotProcess
  Field _check_deviceProtectedDataDir();

  @BFieldNotProcess
  String deviceProtectedDataDir();
}
