package com.hush.env.android.location;

import android.util.ArrayMap;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.location.LocationManager")
public interface LocationManagerQContext {
  @BFieldSetNotProcess
  void _set_mGnssNmeaListeners(final Object value);

  @BFieldCheckNotProcess
  Field _check_mGnssNmeaListeners();

  @BFieldNotProcess
  ArrayMap mGnssNmeaListeners();

  @BFieldSetNotProcess
  void _set_mGnssStatusListeners(final Object value);

  @BFieldCheckNotProcess
  Field _check_mGnssStatusListeners();

  @BFieldNotProcess
  ArrayMap mGnssStatusListeners();

  @BFieldSetNotProcess
  void _set_mGpsNmeaListeners(final Object value);

  @BFieldCheckNotProcess
  Field _check_mGpsNmeaListeners();

  @BFieldNotProcess
  ArrayMap mGpsNmeaListeners();

  @BFieldSetNotProcess
  void _set_mGpsStatusListeners(final Object value);

  @BFieldCheckNotProcess
  Field _check_mGpsStatusListeners();

  @BFieldNotProcess
  ArrayMap mGpsStatusListeners();

  @BFieldSetNotProcess
  void _set_mListeners(final Object value);

  @BFieldCheckNotProcess
  Field _check_mListeners();

  @BFieldNotProcess
  ArrayMap mListeners();
}
