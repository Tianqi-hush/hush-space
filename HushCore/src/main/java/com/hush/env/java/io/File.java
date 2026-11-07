package com.hush.env.java.io;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticField;

@BClassName("java.io.File")
public interface File {
    @BStaticField
    Object fs();
}
