package com.hush.env.android.app;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticField;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.app.ActivityTaskManager")
public interface ActivityTaskManager {

    @BStaticMethod
    Object getService();

    @BStaticField
    Object IActivityTaskManagerSingleton();
}
