package com.hush.fake;

import com.hush.jnihook.ReflectCore;

/**
 * Created by @officialakshit01 on 3/7/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class FakeCore {
    public static void init() {
        ReflectCore.set(android.app.ActivityThread.class);
    }
}
