package com.hush.env.android.telephony;

import android.telephony.CellIdentityCdma;
import android.telephony.CellSignalStrengthCdma;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.telephony.CellInfoCdma")
public interface CellInfoCdmaContext {
  @BFieldSetNotProcess
  void _set_mCellIdentityCdma(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCellIdentityCdma();

  @BFieldNotProcess
  CellIdentityCdma mCellIdentityCdma();

  @BFieldSetNotProcess
  void _set_mCellSignalStrengthCdma(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCellSignalStrengthCdma();

  @BFieldNotProcess
  CellSignalStrengthCdma mCellSignalStrengthCdma();
}
