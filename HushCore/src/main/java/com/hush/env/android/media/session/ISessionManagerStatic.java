package com.hush.env.android.media.session;

import android.content.Context;
import android.os.IInterface;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.media.session.ISessionManager")
public interface ISessionManagerStatic {
  @BMethodCheckNotProcess
  Method _check_getService(Context context);

  IInterface getService(Context context);
}
