package com.hush.env.android.app;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

@BClassName("android.app.NotificationChannel")
public interface NotificationChannel {
    @BField
    String mId();
}
