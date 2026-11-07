package com.hush.env.android.app;

import java.lang.Object;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.LoadedApk$ReceiverDispatcher$InnerReceiver")
public interface LoadedApkReceiverDispatcherInnerReceiverContext {
  @BFieldSetNotProcess
  void _set_mDispatcher(final Object value);

  @BFieldCheckNotProcess
  Field _check_mDispatcher();

  @BFieldNotProcess
  WeakReference<?> mDispatcher();
}
