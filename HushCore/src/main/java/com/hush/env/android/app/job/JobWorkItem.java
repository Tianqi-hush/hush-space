package com.hush.env.android.app.job;

import android.content.Intent;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BConstructor;
import com.hush.reflection.annotation.BField;
import com.hush.reflection.annotation.BMethod;

@BClassName("android.app.job.JobWorkItem")
public interface JobWorkItem {
    @BConstructor
    JobWorkItem _new(Intent Intent0);

    @BField
    int mDeliveryCount();

    @BField
    Object mGrants();

    @BField
    int mWorkId();

    @BMethod
    Intent getIntent();
}
