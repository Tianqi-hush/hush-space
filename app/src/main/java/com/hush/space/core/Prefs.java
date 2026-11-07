package com.hush.space.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量本地配置：当前空间、主题、环境伪装开关。 */
public final class Prefs {

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    /**
     * 模块专属空间。
     *
     * <p>普通空间从 0 开始按 1、2…递增，这里取一个不会撞上的 99。
     * 模块 APK 本体仍装在 {@code USER_XPOSED=-4}（引擎按那个用户识别模块），
     * 它的设置界面则装进 99，和游戏所在空间彻底分开。
     */
    public static final int USER_MODULE = 99;

    private static final String FILE = "hush_space";
    private static SharedPreferences sp;

    private Prefs() {
    }

    public static void init(Context context) {
        if (sp == null) {
            // attachBaseContext 阶段 getApplicationContext() 可能尚未就绪，直接使用传入的 Context
            Context app = context.getApplicationContext();
            Context target = app != null ? app : context;
            sp = target.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences p() {
        return sp;
    }

    // ---------- 空间 ----------

    public static int userId() {
        return p().getInt("user_id", 0);
    }

    public static void setUserId(int id) {
        // 当前空间必须同步落盘：切完空间立刻杀进程，apply() 的异步写会丢，
        // 下次启动又回到上一个空间，用户会以为「刚配的东西全没了」。
        p().edit().putInt("user_id", id).commit();
    }

    public static String spaceName(Context context, int id) {
        if (id == USER_MODULE) {
            return "模块空间";
        }
        String saved = p().getString("space_name_" + id, null);
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        if (id == 0) {
            return context.getString(com.hush.space.R.string.app_name);
        }
        return context.getString(com.hush.space.R.string.space_default_name, id);
    }

    public static void setSpaceName(int id, String name) {
        p().edit().putString("space_name_" + id, name).commit();
    }

    public static void clearSpaceName(int id) {
        p().edit().remove("space_name_" + id).commit();
    }

    // ---------- 外观 ----------

    public static int theme() {
        return p().getInt("theme", THEME_SYSTEM);
    }

    public static void setTheme(int theme) {
        p().edit().putInt("theme", theme).apply();
    }

    /** 主题预设下标：0..N 对应内置色板；< 0 表示使用自定义种子色。 */
    public static int themePreset() {
        return p().getInt("theme_preset", 0);
    }

    public static void setThemePreset(int preset) {
        p().edit().putInt("theme_preset", preset).apply();
    }

    /** 自定义种子色（ARGB）。 */
    public static int themeSeed() {
        return p().getInt("theme_seed", 0xFF4F46E5);
    }

    public static void setThemeSeed(int color) {
        p().edit().putInt("theme_seed", color).apply();
    }

    /** Android 12+ 动态取色，开启后忽略预设与自定义。 */
    public static boolean dynamicColor() {
        return p().getBoolean("dynamic_color", false);
    }

    public static void setDynamicColor(boolean v) {
        p().edit().putBoolean("dynamic_color", v).apply();
    }

    // ---------- 环境 ----------

    public static boolean hideRoot() {
        return p().getBoolean("hide_root", false);
    }

    public static void setHideRoot(boolean v) {
        p().edit().putBoolean("hide_root", v).apply();
    }

    public static boolean hideXposed() {
        return p().getBoolean("hide_xposed", true);
    }

    public static void setHideXposed(boolean v) {
        p().edit().putBoolean("hide_xposed", v).apply();
    }

    public static boolean daemon() {
        return p().getBoolean("daemon", true);
    }

    public static void setDaemon(boolean v) {
        p().edit().putBoolean("daemon", v).apply();
    }

    /**
     * /proc 伪文件重定向。默认关闭。
     * Unity / IL2CPP 会在启动阶段读 /proc/self/status 和 cmdline，
     * 静态快照会把过期内容喂给它，直接闪退。
     */
    public static boolean fakeProc() {
        return p().getBoolean("fake_proc", false);
    }

    public static void setFakeProc(boolean v) {
        p().edit().putBoolean("fake_proc", v).apply();
    }

    public static boolean permissionAsked() {
        return p().getBoolean("perm_asked", false);
    }

    public static void setPermissionAsked() {
        p().edit().putBoolean("perm_asked", true).apply();
    }

    public static boolean guideSeen() {
        return p().getBoolean("guide_seen", false);
    }

    public static void setGuideSeen() {
        p().edit().putBoolean("guide_seen", true).apply();
    }
}
