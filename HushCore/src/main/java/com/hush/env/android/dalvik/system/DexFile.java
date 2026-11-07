package com.hush.env.android.dalvik.system;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BMethod;
import com.hush.reflection.annotation.BStaticField;

@BClassName("dalvik.system.DexFile")
public interface DexFile {

    @BMethod
    Object openDexFileNative(String sourceName, String outputName, int flags);

    @BMethod
    Object openDexFile(String sourceName, String outputName, int flags);

    @BMethod
    Object loadDex(String sourceName, String outputName, int flags);

    @BStaticField
    Object mCookie();
}
