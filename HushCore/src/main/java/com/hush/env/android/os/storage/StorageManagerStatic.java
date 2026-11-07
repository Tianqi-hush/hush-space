package com.hush.env.android.os.storage;

import android.os.storage.StorageVolume;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.os.storage.StorageManager")
public interface StorageManagerStatic {
  @BMethodCheckNotProcess
  Method _check_getVolumeList(int int0, int int1);

  StorageVolume[] getVolumeList(int int0, int int1);
}
