package com.hush.env.android.content.pm;

import android.content.pm.PackageParser;
import java.io.File;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.pm.PackageParser")
public interface PackageParserTiramisuContext {
  @BMethodCheckNotProcess
  Method _check_parsePackage(File File0, int flags);

  PackageParser.Package parsePackage(File File0, int flags);
}
