package com.hush.env.android.app.job;

import android.content.Intent;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.app.job.JobWorkItem")
public interface JobWorkItemStatic {
  @BConstructorNotProcess
  JobWorkItem _new(Intent Intent0);
}
