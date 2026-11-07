package com.hush.env.android.content.pm;

import android.content.pm.Signature;
import java.lang.Boolean;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.pm.PackageParser$SigningDetails")
public interface PackageParserSigningDetailsContext {
  @BMethodCheckNotProcess
  Method _check_hasPastSigningCertificates();

  Boolean hasPastSigningCertificates();

  @BMethodCheckNotProcess
  Method _check_hasSignatures();

  Boolean hasSignatures();

  @BFieldSetNotProcess
  void _set_pastSigningCertificates(final Object value);

  @BFieldCheckNotProcess
  Field _check_pastSigningCertificates();

  @BFieldNotProcess
  Signature[] pastSigningCertificates();

  @BFieldSetNotProcess
  void _set_signatures(final Object value);

  @BFieldCheckNotProcess
  Field _check_signatures();

  @BFieldNotProcess
  Signature[] signatures();
}
