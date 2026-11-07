package com.hush.env.java.lang;

import java.lang.Object;
import java.lang.ThreadGroup;
import java.lang.reflect.Field;
import java.util.List;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("java.lang.ThreadGroup")
public interface ThreadGroupContext {
  @BFieldSetNotProcess
  void _set_groups(final Object value);

  @BFieldCheckNotProcess
  Field _check_groups();

  @BFieldNotProcess
  List<ThreadGroup> groups();

  @BFieldSetNotProcess
  void _set_parent(final Object value);

  @BFieldCheckNotProcess
  Field _check_parent();

  @BFieldNotProcess
  ThreadGroup parent();
}
