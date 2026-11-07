package com.hush.env.android.app;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

@BClassName("android.app.LoadedApk")
public interface LoadedApkKitkat {
    @BField
    Object mDisplayAdjustments();
}
