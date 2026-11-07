package com.hush.env.android.app.servertransaction;

import java.lang.Integer;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.servertransaction.ActivityLifecycleItem")
public interface ActivityLifecycleItemContext {
  @BMethodCheckNotProcess
  Method _check_getTargetState();

  Integer getTargetState();
}
