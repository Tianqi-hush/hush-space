package com.hush.env.android.location;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;
import com.hush.reflection.annotation.BMethod;

@BClassName("android.location.LocationRequest")
public interface LocationRequestL {
    @BField
    boolean mHideFromAppOps();

    @BField
    String mProvider();

    @BField
    Object mWorkSource();

    @BMethod
    String getProvider();
}
