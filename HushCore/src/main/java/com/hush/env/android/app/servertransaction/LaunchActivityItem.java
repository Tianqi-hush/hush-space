package com.hush.env.android.app.servertransaction;

import android.content.Intent;
import android.content.pm.ActivityInfo;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

@BClassName("android.app.servertransaction.LaunchActivityItem")
public interface LaunchActivityItem {
    @BField
    ActivityInfo mInfo();

    @BField
    Intent mIntent();
}
