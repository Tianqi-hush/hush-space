package com.hush.env.android.webkit;

import java.lang.Boolean;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.webkit.WebViewFactory")
public interface WebViewFactoryStatic {
  @BMethodCheckNotProcess
  Method _check_getUpdateService();

  Object getUpdateService();

  @BFieldSetNotProcess
  void _set_sWebViewSupported(final Object value);

  @BFieldCheckNotProcess
  Field _check_sWebViewSupported();

  @BFieldNotProcess
  Boolean sWebViewSupported();
}
