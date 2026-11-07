package com.hush.env.android.app.job;

import android.content.Intent;
import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.job.JobWorkItem")
public interface JobWorkItemContext {
  @BMethodCheckNotProcess
  Method _check_getIntent();

  Intent getIntent();

  @BFieldSetNotProcess
  void _set_mDeliveryCount(final Object value);

  @BFieldCheckNotProcess
  Field _check_mDeliveryCount();

  @BFieldNotProcess
  Integer mDeliveryCount();

  @BFieldSetNotProcess
  void _set_mGrants(final Object value);

  @BFieldCheckNotProcess
  Field _check_mGrants();

  @BFieldNotProcess
  Object mGrants();

  @BFieldSetNotProcess
  void _set_mWorkId(final Object value);

  @BFieldCheckNotProcess
  Field _check_mWorkId();

  @BFieldNotProcess
  Integer mWorkId();
}
