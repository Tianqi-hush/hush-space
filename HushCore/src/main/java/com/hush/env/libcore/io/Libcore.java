package com.hush.env.libcore.io;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticField;

@BClassName("libcore.io.Libcore")
public interface Libcore {
    @BStaticField
    Object os();
}
