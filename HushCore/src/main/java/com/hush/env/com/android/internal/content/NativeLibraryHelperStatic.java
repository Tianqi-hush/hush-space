package com.hush.env.com.android.internal.content;

import java.io.File;
import java.lang.Integer;
import java.lang.String;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;
import com.hush.reflection.annotation.BParamClassName;

@BClassNameNotProcess("com.android.internal.content.NativeLibraryHelper")
public interface NativeLibraryHelperStatic {
  @BMethodCheckNotProcess
  Method _check_copyNativeBinaries(NativeLibraryHelper.Handle Handle0, File File1, String String2);

  Integer copyNativeBinaries(NativeLibraryHelper.Handle Handle0, File File1, String String2);

  @BMethodCheckNotProcess
  Method _check_findSupportedAbi(NativeLibraryHelper.Handle Handle0,
      @BParamClassName("[Ljava.lang.String;") String[] strings);

  Integer findSupportedAbi(NativeLibraryHelper.Handle Handle0,
      @BParamClassName("[Ljava.lang.String;") String[] strings);
}
