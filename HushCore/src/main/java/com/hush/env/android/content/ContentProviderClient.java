package com.hush.env.android.content;

import android.os.IInterface;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

@BClassName("android.content.ContentProviderClient")
public interface ContentProviderClient {
    @BField
    IInterface mContentProvider();
}
