package com.hush.env.android.rms.resource;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.rms.resource.ReceiverResource")
public interface ReceiverResourceLPContext {
  @BFieldSetNotProcess
  void _set_mResourceConfig(final Object value);

  @BFieldCheckNotProcess
  Field _check_mResourceConfig();

  @BFieldNotProcess
  Object mResourceConfig();
}
