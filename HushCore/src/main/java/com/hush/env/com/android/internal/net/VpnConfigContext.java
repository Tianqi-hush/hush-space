package com.hush.env.com.android.internal.net;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import java.util.List;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("com.android.internal.net.VpnConfig")
public interface VpnConfigContext {
  @BFieldSetNotProcess
  void _set_user(final Object value);

  @BFieldCheckNotProcess
  Field _check_user();

  @BFieldNotProcess
  String user();

  @BFieldSetNotProcess
  void _set_disallowedApplications(final Object value);

  @BFieldCheckNotProcess
  Field _check_disallowedApplications();

  @BFieldNotProcess
  List<String> disallowedApplications();

  @BFieldSetNotProcess
  void _set_allowedApplications(final Object value);

  @BFieldCheckNotProcess
  Field _check_allowedApplications();

  @BFieldNotProcess
  List<String> allowedApplications();
}
