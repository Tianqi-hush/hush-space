package com.hush.env.android.app.job;

import android.os.IBinder;
import android.os.PersistableBundle;
import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.job.JobParameters")
public interface JobParametersContext {
  @BFieldSetNotProcess
  void _set_callback(final Object value);

  @BFieldCheckNotProcess
  Field _check_callback();

  @BFieldNotProcess
  IBinder callback();

  @BFieldSetNotProcess
  void _set_extras(final Object value);

  @BFieldCheckNotProcess
  Field _check_extras();

  @BFieldNotProcess
  PersistableBundle extras();

  @BFieldSetNotProcess
  void _set_jobId(final Object value);

  @BFieldCheckNotProcess
  Field _check_jobId();

  @BFieldNotProcess
  Integer jobId();
}
