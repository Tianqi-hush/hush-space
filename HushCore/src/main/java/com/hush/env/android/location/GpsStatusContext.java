package com.hush.env.android.location;

import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.location.GpsStatus")
public interface GpsStatusContext {
  @BMethodCheckNotProcess
  Method _check_setStatus(int int0, int[] ints1, float[] floats2, float[] floats3, float[] floats4,
      int int5, int int6, int int7);

  Void setStatus(int int0, int[] ints1, float[] floats2, float[] floats3, float[] floats4, int int5,
      int int6, int int7);
}
