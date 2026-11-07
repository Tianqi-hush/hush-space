package com.hush.entity;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 空间级配置。
 *
 * <p>字段为 {@code null} 表示「继承全局」，非 null 表示该空间单独覆盖。
 * 全局配置本身用同一份结构，只是所有字段都有值。
 *
 * <p>2.6.0 新增：此前「隐藏 Root / 隐藏 Xposed / 守护服务」只有全局开关，
 * 区域/语言/时区则完全没有。现在每个空间可以单独指定，未指定的回落全局。
 */
public class SpaceSettings {

    public Boolean hideRoot;
    public Boolean hideXposed;
    public Boolean daemon;

    /** 语言代码，如 zh、zh-TW、en、ja。null = 跟随系统 / 继承全局。 */
    public String language;
    /** 区域代码，如 CN、HK、TW、US。null = 跟随系统 / 继承全局。 */
    public String region;
    /** 时区 ID，如 Asia/Shanghai。null = 跟随系统 / 继承全局。 */
    public String timezone;

    /**
     * 作用范围标记：用户是否显式把这个空间的三项环境开关「切成了仅本空间」。
     *
     * <p>2.6.10 新增。之前作用范围完全靠「字段是否非 null」反推，于是
     * 用户在「全部跟随系统」的状态下点「仅 XX 空间」，写进去的三个值
     * 恰好都是 null，文件被判为空直接删掉，开关立刻跳回「全部空间」。
     * 有了这个标记，即使用户还没选具体值，选择本身也能被记住。
     *
     * <p>不参与 merge：它只描述「作用域」，不描述「值」。
     */
    public Boolean envScoped;
    /** 同上，对应区域 / 语言 / 时区这一组。 */
    public Boolean areaScoped;

    public SpaceSettings() {
    }

    public SpaceSettings(SpaceSettings o) {
        if (o == null) return;
        this.hideRoot = o.hideRoot;
        this.hideXposed = o.hideXposed;
        this.daemon = o.daemon;
        this.language = o.language;
        this.region = o.region;
        this.timezone = o.timezone;
        this.envScoped = o.envScoped;
        this.areaScoped = o.areaScoped;
    }

    public boolean isEmpty() {
        return hideRoot == null && hideXposed == null && daemon == null
                && language == null && region == null && timezone == null
                && envScoped == null && areaScoped == null;
    }

    /** 用 override 覆盖 base 中为 null 的字段。 */
    public static SpaceSettings merge(SpaceSettings base, SpaceSettings override) {
        SpaceSettings out = new SpaceSettings(base);
        if (override == null) return out;
        if (override.hideRoot != null) out.hideRoot = override.hideRoot;
        if (override.hideXposed != null) out.hideXposed = override.hideXposed;
        if (override.daemon != null) out.daemon = override.daemon;
        if (override.language != null) out.language = override.language;
        if (override.region != null) out.region = override.region;
        if (override.timezone != null) out.timezone = override.timezone;
        return out;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            if (hideRoot != null) o.put("hideRoot", hideRoot.booleanValue());
            if (hideXposed != null) o.put("hideXposed", hideXposed.booleanValue());
            if (daemon != null) o.put("daemon", daemon.booleanValue());
            if (language != null) o.put("language", language);
            if (region != null) o.put("region", region);
            if (timezone != null) o.put("timezone", timezone);
            if (envScoped != null) o.put("envScoped", envScoped.booleanValue());
            if (areaScoped != null) o.put("areaScoped", areaScoped.booleanValue());
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static SpaceSettings fromJson(String text) {
        SpaceSettings s = new SpaceSettings();
        if (text == null || text.isEmpty()) return s;
        try {
            JSONObject o = new JSONObject(text);
            if (o.has("hideRoot") && !o.isNull("hideRoot")) {
                s.hideRoot = o.optBoolean("hideRoot");
            }
            if (o.has("hideXposed") && !o.isNull("hideXposed")) {
                s.hideXposed = o.optBoolean("hideXposed");
            }
            if (o.has("daemon") && !o.isNull("daemon")) {
                s.daemon = o.optBoolean("daemon");
            }
            s.language = optString(o, "language");
            s.region = optString(o, "region");
            s.timezone = optString(o, "timezone");
            if (o.has("envScoped") && !o.isNull("envScoped")) {
                s.envScoped = o.optBoolean("envScoped");
            }
            if (o.has("areaScoped") && !o.isNull("areaScoped")) {
                s.areaScoped = o.optBoolean("areaScoped");
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static String optString(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return (v == null || v.isEmpty()) ? null : v;
    }
}
