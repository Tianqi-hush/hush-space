package com.hush.env.android.dalvik.system;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("dalvik.system.DexFile")
public interface DexFileStatic {
  @BFieldSetNotProcess
  void _set_mCookie(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCookie();

  @BFieldNotProcess
  Object mCookie();
}
