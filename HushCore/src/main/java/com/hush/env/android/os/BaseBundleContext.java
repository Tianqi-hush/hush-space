package com.hush.env.android.os;

import android.os.Parcel;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.os.BaseBundle")
public interface BaseBundleContext {
  @BFieldSetNotProcess
  void _set_mParcelledData(final Object value);

  @BFieldCheckNotProcess
  Field _check_mParcelledData();

  @BFieldNotProcess
  Parcel mParcelledData();
}
