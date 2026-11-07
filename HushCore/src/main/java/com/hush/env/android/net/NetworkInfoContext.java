package com.hush.env.android.net;

import android.net.NetworkInfo;
import java.lang.Boolean;
import java.lang.Integer;
import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.net.NetworkInfo")
public interface NetworkInfoContext {
  @BFieldSetNotProcess
  void _set_mDetailedState(final Object value);

  @BFieldCheckNotProcess
  Field _check_mDetailedState();

  @BFieldNotProcess
  NetworkInfo.DetailedState mDetailedState();

  @BFieldSetNotProcess
  void _set_mState(final Object value);

  @BFieldCheckNotProcess
  Field _check_mState();

  @BFieldNotProcess
  NetworkInfo.State mState();

  @BFieldSetNotProcess
  void _set_mIsAvailable(final Object value);

  @BFieldCheckNotProcess
  Field _check_mIsAvailable();

  @BFieldNotProcess
  Boolean mIsAvailable();

  @BFieldSetNotProcess
  void _set_mTypeName(final Object value);

  @BFieldCheckNotProcess
  Field _check_mTypeName();

  @BFieldNotProcess
  String mTypeName();

  @BFieldSetNotProcess
  void _set_mNetworkType(final Object value);

  @BFieldCheckNotProcess
  Field _check_mNetworkType();

  @BFieldNotProcess
  Integer mNetworkType();
}
