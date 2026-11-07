package com.hush.fake.service;

import java.util.HashMap;
import java.util.Map;

public class SystemPropertyHook {

    /**
     * 宿主注入的自定义属性覆盖表（设备伪装）。
     * key 为属性名，value 为期望值；为空时不做任何覆盖。
     */
    public static final Map<String, String> CUSTOM = new HashMap<>();

    public static String interceptProperty(String name, String value) {
        if (name == null) return value;

        // 宿主侧配置的设备伪装优先
        String custom = CUSTOM.get(name);
        if (custom != null) {
            return custom;
        }

        // Spoof properties that could reveal a virtual/sandboxed environment
        switch (name) {
            case "ro.build.uid":
                return "10000"; // Typical user ID
            case "ro.dalvik.vm.native.bridge":
                return "0";
            case "sys.oem_unlock_allowed":
                return "0";
            case "ro.secure":
                return "1";
            case "ro.debuggable":
                return "0";
        }
        
        // Block custom Hush/sandbox properties
        if (name.startsWith("hush.") || name.startsWith("vbox.")) {
            return "";
        }

        // Disable Vivo VSPA and MEMC to prevent Android 16 SELinux permission denials and frame drops
        if (name.startsWith("persist.vivo.ltw.enabled") || name.equals("ro.vivo.memc.support")) {
            return "0"; // or "false" based on property parsing, "0" works for both boolean/int
        }

        return value;
    }
}
