package com.hush.env.android.telephony;

import java.lang.Boolean;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.telephony.SmsManager")
public interface SmsManagerContext {
  @BMethodCheckNotProcess
  Method _check_getAutoPersisting();

  Boolean getAutoPersisting();

  @BMethodCheckNotProcess
  Method _check_setAutoPersisting(boolean boolean0);

  Void setAutoPersisting(boolean boolean0);
}
