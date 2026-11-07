package com.hush.env.android.telephony;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BConstructor;
import com.hush.reflection.annotation.BField;

@BClassName("android.telephony.CellIdentityGsm")
public interface CellIdentityGsm {
    @BConstructor
    CellIdentityGsm _new();

    @BField
    int mCid();

    @BField
    int mLac();

    @BField
    int mMcc();

    @BField
    int mMnc();
}
