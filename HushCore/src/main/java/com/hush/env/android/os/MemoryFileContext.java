package com.hush.env.android.os;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.os.MemoryFile")
public interface MemoryFileContext {
  @BMethodCheckNotProcess
  Method _check_getFileDescriptor();

  FileDescriptor getFileDescriptor();
}
