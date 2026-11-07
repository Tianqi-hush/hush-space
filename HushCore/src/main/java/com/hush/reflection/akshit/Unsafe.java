package com.hush.reflection.akshit;
import java.lang.reflect.Field;
public interface Unsafe {
    Object getObject(Object obj, long offset);
    long objectFieldOffset(Field field);
    int getInt(long address);
    long getLong(Object obj, long offset);
    long getLong(Class<?> clazz, long offset);
    void putLong(Object obj, long offset, long value);
    void putObject(Object obj, long offset, Object value);
}
