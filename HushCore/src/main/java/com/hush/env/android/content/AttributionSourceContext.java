package com.hush.env.android.content;

import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.AttributionSource")
public interface AttributionSourceContext {
  @BMethodCheckNotProcess
  Method _check_getNext();

  Object getNext();

  @BFieldSetNotProcess
  void _set_mAttributionSourceState(final Object value);

  @BFieldCheckNotProcess
  Field _check_mAttributionSourceState();

  @BFieldNotProcess
  Object mAttributionSourceState();
}
