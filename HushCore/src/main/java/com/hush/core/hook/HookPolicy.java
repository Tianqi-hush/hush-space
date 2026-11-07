package com.hush.core.hook;

import android.content.pm.ApplicationInfo;

import java.io.File;
import java.util.Locale;

/**
 * 决定「要不要给这个 guest 装 ART 层 hook」。
 *
 * <p>规则是 <b>白名单 + native 库特征</b>，两者命中任意一个就装：
 *
 * <ul>
 *   <li><b>白名单</b>：明确验证过、必须靠 ART hook 才能跑的包（含全部渠道包后缀）。</li>
 *   <li><b>加固库特征</b>：{@code nativeLibraryDir} 里出现某个壳的入口 so。
 *       这比"类名像壳"可靠得多——类名可以混淆，但壳要靠 {@code System.loadLibrary}
 *       按固定名字 dlopen 自己的 so，改不了。</li>
 * </ul>
 *
 * <p>2.6.8 修的就是这里：旧实现只做 {@code pkg.equals("com.netease.sky")} 精确匹配，
 * 而应用市场下载的光遇包名是 {@code com.netease.sky.huawei} / {@code .vivo} / {@code .oppo}
 * …… 一个都命中不了。结果这些包明明带着 {@code libunisec.so}，却被判成"普通 App"，
 * 零 hook 裸跑；易盾壳在 {@code attachBaseContext} 里做 JNI ID 校验，直接 native 崩溃。
 * 日志里表现为「所有应用都停在 before makeApplication，紧接着一条加密 crash 块」。
 */
public final class HookPolicy {

    private HookPolicy() {
    }

    /**
     * 需要 ART 层兼容的包。**精确包名。**
     *
     * 光遇：易盾 StubApp + libunisec.so，且 ART 开了 JNI ID 间接化，
     * 壳把 jmethodID 当指针解引用会崩，必须靠 EncodeGenericId 直通。
     */
    private static final String[] ALLOW = {
            "com.netease.sky",
    };

    /**
     * 按包名前缀匹配，处理各应用市场的渠道包。
     * {@code com.netease.sky.huawei}、{@code com.netease.sky.vivo}、{@code com.netease.sky.mi} …
     */
    private static final String[] ALLOW_PREFIX = {
            "com.netease.sky.",
    };

    /**
     * 加固壳的入口 so（小写，去 {@code lib} 前缀与 {@code .so} 后缀比较）。
     *
     * <p>壳必须先 {@code System.loadLibrary("xxx")} 才能解密自己的 dex，
     * 所以这个文件名是硬编码在壳的 StubApplication 里的，不会随业务代码变化。
     */
    private static final String[] SHELL_LIBS = {
            // 网易易盾
            "unisec", "androidmainruns", "nesec", "kwscmm", "kwsgmain",
            // 360 加固
            "jiagu", "jiagu_art", "jiagu_x86", "protectclass",
            // 腾讯乐固
            "shell", "shella", "shellx", "tosprotection", "tosprotectionx",
            // 梆梆
            "dexhelper", "secexe", "secmain", "seccore", "securities",
            // 爱加密
            "execmain", "ijiami", "iapp", "ijm", "ijiami_data",
            // 阿里聚安全
            "mobisec", "zuma", "aliprotect",
            // 娜迦
            "chaosvmp", "ddog", "nagain",
            // 百度
            "baiduprotect",
            // 顶象 / 其他商业壳
            "x3g", "edog",
    };

    /** 结论：true 表示这个 guest 需要 ART 层 hook。 */
    public static boolean needsShellHooks(ApplicationInfo ai) {
        return detect(ai) != null;
    }

    /** 返回判定理由，null 表示不需要。日志直接打这个，省得以后又靠猜。 */
    public static String detect(ApplicationInfo ai) {
        if (ai == null) return null;
        String pkg = ai.packageName;
        if (pkg == null) return null;

        for (String p : ALLOW) {
            if (p.equals(pkg)) return "allow=pkg:" + pkg;
        }
        for (String p : ALLOW_PREFIX) {
            if (pkg.startsWith(p)) return "allow=prefix:" + p;
        }

        String hit = shellLibOf(ai.nativeLibraryDir);
        if (hit != null) return "shell-lib=" + hit;
        return null;
    }

    /** 扫 native 库目录，返回命中的 so 文件名；没有返回 null。 */
    public static String shellLibOf(String nativeLibraryDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.isEmpty()) return null;
        try {
            File dir = new File(nativeLibraryDir);
            String[] kids = dir.list();
            if (kids == null || kids.length == 0) return null;
            for (String name : kids) {
                if (isShellLib(name)) return name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isShellLib(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".so")) return false;
        if (n.startsWith("lib")) n = n.substring(3);
        n = n.substring(0, n.length() - 3);
        for (String s : SHELL_LIBS) {
            if (n.equals(s)) return true;
        }
        // libjiagu_art.so / libshellx-2.10.so 这类带版本或架构后缀的
        for (String s : SHELL_LIBS) {
            if (n.startsWith(s + "_") || n.startsWith(s + "-")) return true;
        }
        return false;
    }
}
