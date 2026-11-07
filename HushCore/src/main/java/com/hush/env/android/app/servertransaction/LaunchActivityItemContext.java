package com.hush.env.android.app.servertransaction;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.servertransaction.LaunchActivityItem")
public interface LaunchActivityItemContext {
  @BFieldSetNotProcess
  void _set_mInfo(final Object value);

  @BFieldCheckNotProcess
  Field _check_mInfo();

  @BFieldNotProcess
  ActivityInfo mInfo();

  @BFieldSetNotProcess
  void _set_mIntent(final Object value);

  @BFieldCheckNotProcess
  Field _check_mIntent();

  @BFieldNotProcess
  Intent mIntent();
}
