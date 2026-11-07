package com.hush.env.android.content;

import android.accounts.Account;
import java.lang.String;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.content.SyncInfo")
public interface SyncInfoStatic {
  @BConstructorNotProcess
  SyncInfo _new(int int0, Account Account1, String String2, long long3);
}
