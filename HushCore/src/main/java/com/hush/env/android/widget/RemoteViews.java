package com.hush.env.android.widget;

import android.content.pm.ApplicationInfo;

import java.util.ArrayList;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

@BClassName("android.widget.RemoteViews")
public interface RemoteViews {
    @BField
    ArrayList<Object> mActions();

    @BField
    ApplicationInfo mApplication();

    @BField
    String mPackage();
}
