package com.hush.env.android.providers;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.provider.Settings$NameValueCache")
public interface SettingsNameValueCacheOreoContext {
  @BFieldSetNotProcess
  void _set_mProviderHolder(final Object value);

  @BFieldCheckNotProcess
  Field _check_mProviderHolder();

  @BFieldNotProcess
  Object mProviderHolder();
}
