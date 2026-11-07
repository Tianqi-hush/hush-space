package com.hush.env.android.location;

import android.location.Location;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.location.ILocationListener")
public interface ILocationListenerContext {
  @BMethodCheckNotProcess
  Method _check_onLocationChanged(Location Location0);

  Void onLocationChanged(Location Location0);
}
