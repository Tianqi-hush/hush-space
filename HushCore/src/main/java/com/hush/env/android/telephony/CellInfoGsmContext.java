package com.hush.env.android.telephony;

import android.telephony.CellIdentityGsm;
import android.telephony.CellSignalStrengthGsm;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.telephony.CellInfoGsm")
public interface CellInfoGsmContext {
  @BFieldSetNotProcess
  void _set_mCellIdentityGsm(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCellIdentityGsm();

  @BFieldNotProcess
  CellIdentityGsm mCellIdentityGsm();

  @BFieldSetNotProcess
  void _set_mCellSignalStrengthGsm(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCellSignalStrengthGsm();

  @BFieldNotProcess
  CellSignalStrengthGsm mCellSignalStrengthGsm();
}
