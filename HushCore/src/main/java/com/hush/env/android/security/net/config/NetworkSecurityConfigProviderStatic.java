package com.hush.env.android.security.net.config;

import android.content.Context;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.security.net.config.NetworkSecurityConfigProvider")
public interface NetworkSecurityConfigProviderStatic {
  @BMethodCheckNotProcess
  Method _check_install(Context Context0);

  Void install(Context Context0);
}
