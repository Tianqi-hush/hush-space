package com.hush.env.android.credentials;

import android.credentials.CreateCredentialResponse;
import android.credentials.GetCredentialResponse;
import android.credentials.PrepareGetCredentialResponse;
import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.credentials.ICredentialManager")
public interface ICredentialManagerContext {
  @BMethodCheckNotProcess
  Method _check_getCredential(String requestType);

  GetCredentialResponse getCredential(String requestType);

  @BMethodCheckNotProcess
  Method _check_createCredential(String requestType);

  CreateCredentialResponse createCredential(String requestType);

  @BMethodCheckNotProcess
  Method _check_prepareGetCredential(String requestType);

  PrepareGetCredentialResponse prepareGetCredential(String requestType);

  @BMethodCheckNotProcess
  Method _check_setAllowedProviders(String[] packageNames);

  Void setAllowedProviders(String[] packageNames);

  @BMethodCheckNotProcess
  Method _check_getAllowedProviders();

  String[] getAllowedProviders();
}
