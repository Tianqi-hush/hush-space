package com.hush.env.com.android.internal.telephony;

import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("com.android.internal.telephony.PhoneConstants")
public interface PhoneConstantsMtkStatic {
  @BFieldSetNotProcess
  void _set_GEMINI_SIM_NUM(final Object value);

  @BFieldCheckNotProcess
  Field _check_GEMINI_SIM_NUM();

  @BFieldNotProcess
  Integer GEMINI_SIM_NUM();
}
