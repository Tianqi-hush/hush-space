package com.hush.env.android.app.job;

import android.content.ComponentName;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.job.JobInfo")
public interface JobInfoContext {
  @BFieldSetNotProcess
  void _set_flexMillis(final Object value);

  @BFieldCheckNotProcess
  Field _check_flexMillis();

  @BFieldNotProcess
  Long flexMillis();

  @BFieldSetNotProcess
  void _set_intervalMillis(final Object value);

  @BFieldCheckNotProcess
  Field _check_intervalMillis();

  @BFieldNotProcess
  Long intervalMillis();

  @BFieldSetNotProcess
  void _set_jobId(final Object value);

  @BFieldCheckNotProcess
  Field _check_jobId();

  @BFieldNotProcess
  Integer jobId();

  @BFieldSetNotProcess
  void _set_service(final Object value);

  @BFieldCheckNotProcess
  Field _check_service();

  @BFieldNotProcess
  ComponentName service();
}
