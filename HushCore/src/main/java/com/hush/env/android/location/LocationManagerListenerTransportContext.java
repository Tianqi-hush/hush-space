package com.hush.env.android.location;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import java.lang.Object;
import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.location.LocationManager$GnssStatusListenerTransport")
public interface LocationManagerListenerTransportContext {
  @BMethodCheckNotProcess
  Method _check_onLocationChanged(Location Location0);

  Void onLocationChanged(Location Location0);

  @BMethodCheckNotProcess
  Method _check_onProviderDisabled(String String0);

  Void onProviderDisabled(String String0);

  @BMethodCheckNotProcess
  Method _check_onProviderEnabled(String String0);

  Void onProviderEnabled(String String0);

  @BMethodCheckNotProcess
  Method _check_onStatusChanged(String String0, int int1, Bundle Bundle2);

  Void onStatusChanged(String String0, int int1, Bundle Bundle2);

  @BFieldSetNotProcess
  void _set_mListener(final Object value);

  @BFieldCheckNotProcess
  Field _check_mListener();

  @BFieldNotProcess
  LocationListener mListener();

  @BFieldSetNotProcess
  void _set_this$0(final Object value);

  @BFieldCheckNotProcess
  Field _check_this$0();

  @BFieldNotProcess
  Object this$0();
}
