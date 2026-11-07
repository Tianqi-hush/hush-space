package com.hush.env.android.rms.resource;

import java.lang.Integer;
import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.rms.resource.ReceiverResource")
public interface ReceiverResourceOContext {
  @BFieldSetNotProcess
  void _set_mWhiteListMap(final Object value);

  @BFieldCheckNotProcess
  Field _check_mWhiteListMap();

  @BFieldNotProcess
  Map<Integer, List<String>> mWhiteListMap();
}
