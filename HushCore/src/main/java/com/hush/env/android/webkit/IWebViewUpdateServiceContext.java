package com.hush.env.android.webkit;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.webkit.IWebViewUpdateService")
public interface IWebViewUpdateServiceContext {
  @BMethodCheckNotProcess
  Method _check_getCurrentWebViewPackageName();

  String getCurrentWebViewPackageName();

  @BMethodCheckNotProcess
  Method _check_waitForAndGetProvider();

  Object waitForAndGetProvider();
}
