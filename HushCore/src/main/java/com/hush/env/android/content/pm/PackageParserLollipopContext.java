package com.hush.env.android.content.pm;

import android.content.pm.PackageParser;
import java.io.File;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.pm.PackageParser")
public interface PackageParserLollipopContext {
  @BMethodCheckNotProcess
  Method _check_collectCertificates(PackageParser.Package p, int flags);

  Void collectCertificates(PackageParser.Package p, int flags);

  @BMethodCheckNotProcess
  Method _check_parsePackage(File File0, int flags);

  PackageParser.Package parsePackage(File File0, int flags);
}
