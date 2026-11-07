package com.hush.env.android.app;

import android.content.Intent;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.PendingIntent")
public interface PendingIntentJBMR2Context {
  @BMethodCheckNotProcess
  Method _check_getIntent();

  Intent getIntent();
}
