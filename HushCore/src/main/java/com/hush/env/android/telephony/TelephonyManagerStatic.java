package com.hush.env.android.telephony;

import android.os.IInterface;
import java.lang.Boolean;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.telephony.TelephonyManager")
public interface TelephonyManagerStatic {
  @BMethodCheckNotProcess
  Method _check_getSubscriberInfoService();

  Object getSubscriberInfoService();

  @BFieldSetNotProcess
  void _set_sServiceHandleCacheEnabled(final Object value);

  @BFieldCheckNotProcess
  Field _check_sServiceHandleCacheEnabled();

  @BFieldNotProcess
  Boolean sServiceHandleCacheEnabled();

  @BFieldSetNotProcess
  void _set_sIPhoneSubInfo(final Object value);

  @BFieldCheckNotProcess
  Field _check_sIPhoneSubInfo();

  @BFieldNotProcess
  IInterface sIPhoneSubInfo();
}
