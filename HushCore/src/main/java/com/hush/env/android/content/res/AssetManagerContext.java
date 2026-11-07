package com.hush.env.android.content.res;

import android.content.res.Configuration;
import android.util.DisplayMetrics;
import java.lang.Integer;
import java.lang.String;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.content.res.AssetManager")
public interface AssetManagerContext {
  @BMethodCheckNotProcess
  Method _check_addAssetPath(String String0);

  Integer addAssetPath(String String0);

  @BMethodCheckNotProcess
  Method _check_getConfiguration();

  Configuration getConfiguration();

  @BMethodCheckNotProcess
  Method _check_getDisplayMetrics();

  DisplayMetrics getDisplayMetrics();
}
