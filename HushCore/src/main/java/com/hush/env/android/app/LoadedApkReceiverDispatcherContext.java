package com.hush.env.android.app;

import android.content.BroadcastReceiver;
import android.content.IIntentReceiver;
import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.LoadedApk$ReceiverDispatcher")
public interface LoadedApkReceiverDispatcherContext {
  @BMethodCheckNotProcess
  Method _check_getIIntentReceiver();

  IInterface getIIntentReceiver();

  @BFieldSetNotProcess
  void _set_mIIntentReceiver(final Object value);

  @BFieldCheckNotProcess
  Field _check_mIIntentReceiver();

  @BFieldNotProcess
  IIntentReceiver mIIntentReceiver();

  @BFieldSetNotProcess
  void _set_mReceiver(final Object value);

  @BFieldCheckNotProcess
  Field _check_mReceiver();

  @BFieldNotProcess
  BroadcastReceiver mReceiver();
}
