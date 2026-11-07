package com.hush.env.android.dalvik.system;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("dalvik.system.DexFile")
public interface DexFileContext {
  @BMethodCheckNotProcess
  Method _check_openDexFileNative(String sourceName, String outputName, int flags);

  Object openDexFileNative(String sourceName, String outputName, int flags);

  @BMethodCheckNotProcess
  Method _check_openDexFile(String sourceName, String outputName, int flags);

  Object openDexFile(String sourceName, String outputName, int flags);

  @BMethodCheckNotProcess
  Method _check_loadDex(String sourceName, String outputName, int flags);

  Object loadDex(String sourceName, String outputName, int flags);
}
