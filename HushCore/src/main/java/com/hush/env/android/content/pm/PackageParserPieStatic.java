package com.hush.env.android.content.pm;

import android.content.pm.PackageParser;
import java.lang.Void;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.pm.PackageParser")
public interface PackageParserPieStatic {
  @BMethodCheckNotProcess
  Method _check_collectCertificates(PackageParser.Package p, boolean skipVerify);

  Void collectCertificates(PackageParser.Package p, boolean skipVerify);
}
