package com.hush.env.android.content;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.IClipboard$Stub")
public interface IClipboardStubStatic {
  @BMethodCheckNotProcess
  Method _check_asInterface(IBinder iBinder);

  IInterface asInterface(IBinder iBinder);
}
