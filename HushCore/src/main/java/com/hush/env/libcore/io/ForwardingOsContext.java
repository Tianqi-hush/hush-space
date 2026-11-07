package com.hush.env.libcore.io;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("libcore.io.ForwardingOs")
public interface ForwardingOsContext {
  @BFieldSetNotProcess
  void _set_os(final Object value);

  @BFieldCheckNotProcess
  Field _check_os();

  @BFieldNotProcess
  Object os();
}
