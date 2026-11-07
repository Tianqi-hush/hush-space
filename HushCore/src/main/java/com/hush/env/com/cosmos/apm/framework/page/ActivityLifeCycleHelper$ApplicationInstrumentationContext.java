package com.hush.env.com.cosmos.apm.framework.page;

import android.app.Instrumentation;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("com.cosmos.apm.framework.page.ActivityLifeCycleHelper$ApplicationInstrumentation")
public interface ActivityLifeCycleHelper$ApplicationInstrumentationContext {
  @BFieldSetNotProcess
  void _set_mBase(final Object value);

  @BFieldCheckNotProcess
  Field _check_mBase();

  @BFieldNotProcess
  Instrumentation mBase();
}
