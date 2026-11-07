package com.hush.env.android.ddm;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.ddm.DdmHandleAppName")
public interface DdmHandleAppName {
    @BStaticMethod
    void setAppName(String String0, int i);
}
