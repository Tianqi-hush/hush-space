package com.hush.env.android.os;

import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.os.Process")
public interface ProcessStatic {
  @BMethodCheckNotProcess
  Method _check_setArgV0(String String0);

  Void setArgV0(String String0);
}
