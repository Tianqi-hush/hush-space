package com.hush.env.android.os;

import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.os.Message")
public interface MessageStatic {
  @BMethodCheckNotProcess
  Method _check_updateCheckRecycle(int int0);

  Void updateCheckRecycle(int int0);
}
