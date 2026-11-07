package com.hush.env.android.telephony;

import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.telephony.NeighboringCellInfo")
public interface NeighboringCellInfoContext {
  @BFieldSetNotProcess
  void _set_mCid(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCid();

  @BFieldNotProcess
  Integer mCid();

  @BFieldSetNotProcess
  void _set_mLac(final Object value);

  @BFieldCheckNotProcess
  Field _check_mLac();

  @BFieldNotProcess
  Integer mLac();

  @BFieldSetNotProcess
  void _set_mRssi(final Object value);

  @BFieldCheckNotProcess
  Field _check_mRssi();

  @BFieldNotProcess
  Integer mRssi();
}
