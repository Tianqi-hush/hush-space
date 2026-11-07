package com.hush.env.android.location;

import java.lang.Boolean;
import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.location.LocationRequest")
public interface LocationRequestLContext {
  @BMethodCheckNotProcess
  Method _check_getProvider();

  String getProvider();

  @BFieldSetNotProcess
  void _set_mHideFromAppOps(final Object value);

  @BFieldCheckNotProcess
  Field _check_mHideFromAppOps();

  @BFieldNotProcess
  Boolean mHideFromAppOps();

  @BFieldSetNotProcess
  void _set_mProvider(final Object value);

  @BFieldCheckNotProcess
  Field _check_mProvider();

  @BFieldNotProcess
  String mProvider();

  @BFieldSetNotProcess
  void _set_mWorkSource(final Object value);

  @BFieldCheckNotProcess
  Field _check_mWorkSource();

  @BFieldNotProcess
  Object mWorkSource();
}
