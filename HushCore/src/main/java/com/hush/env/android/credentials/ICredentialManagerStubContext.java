package com.hush.env.android.credentials;

import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.credentials.ICredentialManager$Stub")
public interface ICredentialManagerStubContext {
  @BFieldSetNotProcess
  void _set_TRANSACTION_getCredential(final Object value);

  @BFieldCheckNotProcess
  Field _check_TRANSACTION_getCredential();

  @BFieldNotProcess
  Integer TRANSACTION_getCredential();

  @BFieldSetNotProcess
  void _set_TRANSACTION_createCredential(final Object value);

  @BFieldCheckNotProcess
  Field _check_TRANSACTION_createCredential();

  @BFieldNotProcess
  Integer TRANSACTION_createCredential();

  @BFieldSetNotProcess
  void _set_TRANSACTION_prepareGetCredential(final Object value);

  @BFieldCheckNotProcess
  Field _check_TRANSACTION_prepareGetCredential();

  @BFieldNotProcess
  Integer TRANSACTION_prepareGetCredential();

  @BFieldSetNotProcess
  void _set_TRANSACTION_setAllowedProviders(final Object value);

  @BFieldCheckNotProcess
  Field _check_TRANSACTION_setAllowedProviders();

  @BFieldNotProcess
  Integer TRANSACTION_setAllowedProviders();

  @BFieldSetNotProcess
  void _set_TRANSACTION_getAllowedProviders(final Object value);

  @BFieldCheckNotProcess
  Field _check_TRANSACTION_getAllowedProviders();

  @BFieldNotProcess
  Integer TRANSACTION_getAllowedProviders();
}
