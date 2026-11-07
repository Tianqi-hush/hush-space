package com.hush.env.android.security.net.config;

import android.content.Context;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.security.net.config.NetworkSecurityConfigProvider")
public interface NetworkSecurityConfigProvider {
    @BStaticMethod
    void install(Context Context0);
}
