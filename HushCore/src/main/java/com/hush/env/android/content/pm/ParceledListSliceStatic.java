package com.hush.env.android.content.pm;

import android.os.Parcelable;
import java.lang.Object;
import java.lang.reflect.Field;
import java.util.List;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.pm.ParceledListSlice")
public interface ParceledListSliceStatic {
  @BFieldSetNotProcess
  void _set_CREATOR(final Object value);

  @BFieldCheckNotProcess
  Field _check_CREATOR();

  @BFieldNotProcess
  Parcelable.Creator CREATOR();

  @BConstructorNotProcess
  Object _new();

  @BConstructorNotProcess
  Object _new(List<?> List0);
}
