package com.hush.env.android.os.health;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.os.health.SystemHealthManager")
public interface SystemHealthManagerContext {
  @BFieldSetNotProcess
  void _set_mBatteryStats(final Object value);

  @BFieldCheckNotProcess
  Field _check_mBatteryStats();

  @BFieldNotProcess
  IInterface mBatteryStats();
}
