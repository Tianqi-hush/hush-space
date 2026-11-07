package com.hush.env.android.app.servertransaction;

import java.lang.Object;
import java.lang.reflect.Field;
import java.util.List;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.servertransaction.ActivityResultItem")
public interface ActivityResultItemContext {
  @BFieldSetNotProcess
  void _set_mResultInfoList(final Object value);

  @BFieldCheckNotProcess
  Field _check_mResultInfoList();

  @BFieldNotProcess
  List mResultInfoList();
}
