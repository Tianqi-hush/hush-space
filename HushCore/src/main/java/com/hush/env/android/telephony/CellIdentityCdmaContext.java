package com.hush.env.android.telephony;

import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.telephony.CellIdentityCdma")
public interface CellIdentityCdmaContext {
  @BFieldSetNotProcess
  void _set_mBasestationId(final Object value);

  @BFieldCheckNotProcess
  Field _check_mBasestationId();

  @BFieldNotProcess
  Integer mBasestationId();

  @BFieldSetNotProcess
  void _set_mNetworkId(final Object value);

  @BFieldCheckNotProcess
  Field _check_mNetworkId();

  @BFieldNotProcess
  Integer mNetworkId();

  @BFieldSetNotProcess
  void _set_mSystemId(final Object value);

  @BFieldCheckNotProcess
  Field _check_mSystemId();

  @BFieldNotProcess
  Integer mSystemId();
}
