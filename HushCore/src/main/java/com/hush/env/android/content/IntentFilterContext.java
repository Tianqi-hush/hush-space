package com.hush.env.android.content;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import java.util.List;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.IntentFilter")
public interface IntentFilterContext {
  @BFieldSetNotProcess
  void _set_mActions(final Object value);

  @BFieldCheckNotProcess
  Field _check_mActions();

  @BFieldNotProcess
  List<String> mActions();

  @BFieldSetNotProcess
  void _set_mCategories(final Object value);

  @BFieldCheckNotProcess
  Field _check_mCategories();

  @BFieldNotProcess
  List<String> mCategories();
}
