package com.hush.env.android.app;

import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BMethod;

@BClassName("android.app.Instrumentation")
public interface Instrumentation {
    @BMethod
    ActivityResult execStartActivity(Context Context0, IBinder IBinder1, IBinder IBinder2, Activity Activity3, Intent Intent4, int int5, Bundle Bundle6);
}
