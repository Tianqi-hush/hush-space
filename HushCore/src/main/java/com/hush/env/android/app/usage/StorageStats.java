package com.hush.env.android.app.usage;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BConstructor;
import com.hush.reflection.annotation.BField;

@BClassName("android.app.usage.StorageStats")
public interface StorageStats {
    @BConstructor
    StorageStats _new();

    @BField
    long cacheBytes();

    @BField
    long codeBytes();

    @BField
    long dataBytes();
}
