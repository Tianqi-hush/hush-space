package com.hush.space.core;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.hush.HushCore;
import com.hush.fake.service.SystemPropertyHook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 设备伪装：为每个空间保存一份设备档案，并在虚拟进程创建 Application 之前应用。
 * <p>
 * 生效路径有两条：
 * 1. Java 层 —— 通过 Unsafe 直接改写 {@link Build} 的静态字段；
 * 2. Native 层 —— 填充 {@link SystemPropertyHook#CUSTOM}，引擎已 hook 了
 *    __system_property_get，可覆盖 ro.* / persist.* 等属性读取。
 */
public final class SpoofManager {

    private static final String TAG = "SpoofManager";
    private static final String PREFIX = "device_profile_";

    /** 伪 userId，表示「全部空间的默认机型」。 */
    public static final int GLOBAL = -1000;

    private SpoofManager() {
    }

    // ------------------------------------------------------------------ 存取

    private static File profileFile(Context context, int userId) {
        File dir = null;
        try {
            // 设备档案属于纯配置，统一落在数据根的 system/ 下。
            // 数据根默认是 App 私有外部目录，不需要任何存储权限。
            dir = com.hush.core.env.BEnvironment.getSystemDir();
        } catch (Throwable ignored) {
        }
        if (dir == null) {
            dir = new File(context.getFilesDir(), "system");
        }
        String name = userId == GLOBAL ? PREFIX + "global.json" : PREFIX + userId + ".json";
        return new File(dir, name);
    }

    /**
     * 读取某空间的机型配置。
     *
     * <p>2.6.0 起：空间自己没有配置文件时，回落到全局默认
     * {@code device_profile_global.json}；两者都没有才返回空配置。
     */
    public static DeviceProfile load(Context context, int userId) {
        DeviceProfile own = readProfile(profileFile(context, userId));
        // 文件存在就代表这个空间做过显式选择（哪怕选的是“关闭”），不再继承全局。
        if (own != null) return own;
        if (userId != GLOBAL) {
            DeviceProfile global = readProfile(profileFile(context, GLOBAL));
            if (global != null) return global;
        }
        return new DeviceProfile();
    }

    /** 主文件 → .bak → 内部镜像，任意一份能解析就用。 */
    private static DeviceProfile readProfile(File f) {
        DeviceProfile p = read(f);
        if (p != null) return p;
        File parent = f.getParentFile();
        if (parent != null) {
            p = read(new File(parent, f.getName() + ".bak"));
            if (p != null) {
                Log.w(TAG, "profile 主文件损坏，已回退备份 " + f.getName());
                return p;
            }
        }
        File mirror = mirrorDir();
        if (mirror != null && (parent == null
                || !mirror.getAbsolutePath().equals(parent.getAbsolutePath()))) {
            p = read(new File(mirror, f.getName()));
            if (p != null) {
                Log.w(TAG, "profile 已从镜像恢复 " + f.getName());
                save(f, p.toJson());
                return p;
            }
        }
        return null;
    }

    private static File mirrorDir() {
        try {
            File dir = new File(com.hush.core.env.BEnvironment.getVBoxRoot(), "system");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 严格读取：文件不存在返回 null，内容坏掉也返回 null。
     *
     * <p>旧实现解析异常时返回空 {@link DeviceProfile}，界面就显示「什么都没配」，
     * 且永远不会去读 .bak —— 用户保存后进程被杀、文件写了一半时正是这个表现。
     */
    private static DeviceProfile read(File f) {
        if (f == null || !f.exists() || f.length() <= 0) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int read = in.read(buf);
            if (read <= 0) return null;
            String text = new String(buf, 0, read, "UTF-8").trim();
            if (!text.startsWith("{")) return null;
            new org.json.JSONObject(text);
            return DeviceProfile.fromJson(text);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void save(Context context, int userId, DeviceProfile profile) {
        File f = profileFile(context, userId);
        save(f, profile.toJson());
        File mirror = mirrorDir();
        if (mirror != null && !mirror.getAbsolutePath().equals(f.getParentFile().getAbsolutePath())) {
            save(new File(mirror, f.getName()), profile.toJson());
        }
    }

    /** 原子保存：tmp → fsync → 旧文件转 .bak → rename 覆盖。 */
    private static void save(File f, String json) {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, f.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(json.getBytes("UTF-8"));
            out.flush();
            try {
                out.getFD().sync();
            } catch (Throwable ignored) {
            }
            out.close();
            out = null;
            if (f.exists() && f.length() > 0) {
                File bak = new File(parent, f.getName() + ".bak");
                //noinspection ResultOfMethodCallIgnored
                bak.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(bak);
            }
            if (!tmp.renameTo(f)) {
                out = new FileOutputStream(f);
                out.write(json.getBytes("UTF-8"));
                out.flush();
                out.close();
                out = null;
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.e(TAG, "save profile failed", t);
        } finally {
            if (out != null) try {
                out.close();
            } catch (Throwable ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            if (tmp.exists()) tmp.delete();
        }
    }

    /** 给设置页显示「当前到底存了什么」，避免用户以为没保存。 */
    public static String summary(Context context, int userId) {
        DeviceProfile p = load(context, userId);
        return describe(p, "未设置");
    }

    /**
     * 只看这个空间自己的档案，不回落全局。
     * 设置页在「仅此空间」模式下用它，否则会把继承来的全局机型显示成
     * 「这个空间已单独设置」，用户会以为保存错了地方。
     */
    public static String summaryOwn(Context context, int userId) {
        DeviceProfile p = readProfile(profileFile(context, userId));
        return p == null ? "未设置（跟随全部空间）" : describe(p, "未设置");
    }

    private static String describe(DeviceProfile p, String empty) {
        if (p == null || p.isEmpty()) return empty;
        String brand = p.brand == null ? "" : p.brand.trim();
        String model = p.model == null ? "" : p.model.trim();
        String name = (brand + " " + model).trim();
        if (name.isEmpty()) name = "已设置";
        return p.enabled ? name : name + "（已停用）";
    }

    // ------------------------------------------------------------------ 应用

    /** 在虚拟进程（目标 App 的 Application 创建前）调用。 */
    public static void apply(Context context, int userId) {
        try {
            DeviceProfile profile = load(context, userId);
            if (!profile.enabled || profile.isEmpty()) {
                SystemPropertyHook.CUSTOM.clear();
                return;
            }
            applyBuildFields(profile);
            applyProperties(profile);
            Log.i(TAG, "device profile applied for user " + userId);
        } catch (Throwable t) {
            Log.e(TAG, "apply profile failed", t);
        }
    }

    private static void applyBuildFields(DeviceProfile p) {
        setStatic("BRAND", p.brand);
        setStatic("MODEL", p.model);
        setStatic("MANUFACTURER", p.manufacturer);
        setStatic("DEVICE", p.device);
        setStatic("PRODUCT", p.model);
        setStatic("FINGERPRINT", p.fingerprint);
        setStatic("SERIAL", p.serial);
    }

    private static void applyProperties(DeviceProfile p) {
        Map<String, String> map = new HashMap<>();
        put(map, "ro.product.brand", p.brand);
        put(map, "ro.product.model", p.model);
        put(map, "ro.product.manufacturer", p.manufacturer);
        put(map, "ro.product.device", p.device);
        put(map, "ro.product.name", p.model);
        put(map, "ro.serialno", p.serial);
        put(map, "ro.boot.serialno", p.serial);
        put(map, "ro.build.fingerprint", p.fingerprint);

        // 华为 / 荣耀专有属性。
        //
        // HMS Core、华为账号、荣耀账号、以及一堆加固壳都会读这些值来判断
        // 「这台机器是不是华为/荣耀」。只改 Build.MANUFACTURER 不够：
        // SystemProperties.get("ro.build.version.emui") 为空时会直接走
        // 非华为分支，账号登录页就停在白屏。
        String vendor = ((p.brand == null ? "" : p.brand) + " "
                + (p.manufacturer == null ? "" : p.manufacturer)
                + " " + (p.model == null ? "" : p.model)).toLowerCase(Locale.US);
        if (vendor.contains("huawei")) {
            put(map, "ro.build.version.emui", "EmotionUI_14.0.0");
            put(map, "ro.build.hw_emui_api_level", "31");
            put(map, "ro.product.hw", "true");
            put(map, "ro.build.huawei", "true");
            put(map, "ro.build.characteristics", "default");
        } else if (vendor.contains("honor")) {
            // 荣耀 2022 之后独立成 MagicOS，但底层仍保留 emui 字段给 HMS 用。
            put(map, "ro.build.version.magic", "8.0.0");
            put(map, "ro.build.version.emui", "EmotionUI_14.0.0");
            put(map, "ro.build.hw_emui_api_level", "31");
            put(map, "ro.product.hw", "true");
            put(map, "ro.build.honor", "true");
        }

        SystemPropertyHook.CUSTOM.clear();
        SystemPropertyHook.CUSTOM.putAll(map);
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    /** 通过 sun.misc.Unsafe 改写 Build 的 static final 字符串字段。 */
    private static void setStatic(String fieldName, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            Field field = Build.class.getDeclaredField(fieldName);
            field.setAccessible(true);

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);

            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);

            Object base = staticFieldBase.invoke(unsafe, field);
            long offset = (Long) staticFieldOffset.invoke(unsafe, field);
            putObject.invoke(unsafe, base, offset, value);
        } catch (Throwable t) {
            // Unsafe 不可用时退回普通反射
            try {
                Field field = Build.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(null, value);
            } catch (Throwable ignored) {
            }
        }
    }
}
