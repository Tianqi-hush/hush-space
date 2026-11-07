package com.hush.env.android.telephony;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BMethod;

@BClassName("android.telephony.SmsManager")
public interface SmsManager {
    @BMethod
    Boolean getAutoPersisting();

    @BMethod
    void setAutoPersisting(boolean boolean0);
}
