package com.hush.space.core;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import com.hush.entity.SpaceSettings;

import java.util.Locale;
import java.util.TimeZone;

/**
 * 把空间级的区域/语言/时区套到当前进程。
 *
 * <p>容器里每个 guest 应用独占一个进程，所以这里直接改进程级的
 * {@link Locale#setDefault}、{@link TimeZone#setDefault} 和 Resources 的
 * Configuration 是安全的，不会影响宿主自己的界面。
 */
public final class LocaleKit {

    private LocaleKit() {
    }

    public static void apply(Context context, SpaceSettings s) {
        if (s == null) return;
        try {
            Locale locale = buildLocale(s);
            if (locale != null) {
                Locale.setDefault(locale);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Locale.setDefault(Locale.Category.DISPLAY, locale);
                    Locale.setDefault(Locale.Category.FORMAT, locale);
                    LocaleList.setDefault(new LocaleList(locale));
                }
                updateResources(context, locale);
            }
            if (s.timezone != null && !s.timezone.isEmpty()) {
                TimeZone.setDefault(TimeZone.getTimeZone(s.timezone));
            }
        } catch (Throwable ignored) {
        }
    }

    public static Locale buildLocale(SpaceSettings s) {
        if (s == null) return null;
        String lang = s.language;
        String region = s.region;
        if ((lang == null || lang.isEmpty()) && (region == null || region.isEmpty())) {
            return null;
        }
        try {
            Locale.Builder b = new Locale.Builder();
            if (lang != null && !lang.isEmpty()) {
                b.setLanguageTag(lang);
            } else {
                // 只选了区域：沿用系统语言，只替换国家。
                b.setLanguage(Locale.getDefault().getLanguage());
            }
            if (region != null && !region.isEmpty()) {
                b.setRegion(region);
            }
            return b.build();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void updateResources(Context context, Locale locale) {
        if (context == null) return;
        try {
            Configuration cfg = new Configuration(context.getResources().getConfiguration());
            cfg.setLocale(locale);
            cfg.setLayoutDirection(locale);
            context.getResources().updateConfiguration(cfg, context.getResources().getDisplayMetrics());
        } catch (Throwable ignored) {
        }
        try {
            Context host = com.hush.HushCore.getContext();
            if (host != null && host != context) {
                Configuration cfg = new Configuration(host.getResources().getConfiguration());
                cfg.setLocale(locale);
                cfg.setLayoutDirection(locale);
                host.getResources().updateConfiguration(cfg, host.getResources().getDisplayMetrics());
            }
        } catch (Throwable ignored) {
        }
    }
}
