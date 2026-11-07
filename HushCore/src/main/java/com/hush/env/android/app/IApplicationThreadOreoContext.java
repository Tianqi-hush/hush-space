package com.hush.env.android.app;

import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.IApplicationThread")
public interface IApplicationThreadOreoContext {
  @BMethodCheckNotProcess
  Method _check_scheduleServiceArgs();

  Void scheduleServiceArgs();
}
