package com.hush.env.android.telephony;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BConstructor;
import com.hush.reflection.annotation.BField;

@BClassName("android.telephony.CellSignalStrengthGsm")
public interface CellSignalStrengthGsm {
    @BConstructor
    CellSignalStrengthGsm _new();

    @BField
    int mBitErrorRate();

    @BField
    int mSignalStrength();
}
