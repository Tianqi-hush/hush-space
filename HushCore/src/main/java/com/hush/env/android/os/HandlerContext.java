package com.hush.env.android.os;

import android.os.Handler;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.os.Handler")
public interface HandlerContext {
  @BFieldSetNotProcess
  void _set_mCallback(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCallback();

  @BFieldNotProcess
  Handler.Callback mCallback();
}
