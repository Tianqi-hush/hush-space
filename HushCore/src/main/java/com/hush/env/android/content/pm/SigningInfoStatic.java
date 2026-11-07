package com.hush.env.android.content.pm;

import android.content.pm.PackageParser;
import android.content.pm.SigningInfo;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.content.pm.SigningInfo")
public interface SigningInfoStatic {
  @BConstructorNotProcess
  SigningInfo _new(PackageParser.SigningDetails SigningDetails0);
}
