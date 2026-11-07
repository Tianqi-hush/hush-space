package com.hush.space.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;

import com.hush.HushCore;
import com.hush.app.LauncherActivity;
import com.hush.entity.pm.InstallResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 应用数据来源与常用操作（安装 / 启动 / 卸载 / 快捷方式 / 图标加载）。 */
public final class AppRepo {

    private static final LruCache<String, Drawable> ICON_CACHE = new LruCache<>(300);
    private static final LruCache<String, Resources> RES_CACHE = new LruCache<>(40);

    private AppRepo() {
    }

    // ------------------------------------------------------------------ 已安装到空间中的应用

    public static List<AppEntry> loadCloned(Context context, int userId) {
        List<AppEntry> result = new ArrayList<>();
        List<PackageInfo> packages;
        try {
            packages = HushCore.get().getInstalledPackages(0, userId);
        } catch (Throwable t) {
            return result;
        }
        if (packages == null) return result;

        // 模块包名集合：模块被「打开模块 App」装进当前空间后，会同时出现在
        // 这里。UI 需要把它们和应用区分开，不能让用户以为它们是普通应用。
        Set<String> modulePkgs = new HashSet<>();
        try {
            List<com.hush.entity.pm.InstalledModule> mods =
                    HushCore.get().getInstalledXPModules();
            if (mods != null) {
                for (com.hush.entity.pm.InstalledModule m : mods) {
                    if (m != null && m.packageName != null) {
                        modulePkgs.add(m.packageName);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        String host = context.getPackageName();
        for (PackageInfo info : packages) {
            if (info == null || info.packageName == null) continue;
            if (info.packageName.equals(host)) continue;
            // HMS 的七个 Framework kit、账号 kit、游戏 kit 是容器自己装进去的
            // 运行时组件，列表里只该出现「华为帐号 / 应用市场」这种用户认识的
            // 入口。以前它们被当成普通应用排了一屏，图标还是系统默认的机器人。
            if (com.hush.core.HmsCore.isInternalKit(info.packageName)) continue;
            ApplicationInfo ai = info.applicationInfo;
            if (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

            AppEntry entry = new AppEntry(info.packageName);
            entry.versionName = info.versionName;
            entry.targetSdk = ai != null ? ai.targetSdkVersion : 0;
            entry.apkPath = ai != null ? ai.sourceDir : null;
            entry.iconRes = ai != null ? ai.icon : 0;
            entry.labelRes = ai != null ? ai.labelRes : 0;
            entry.cloned = true;
            entry.module = modulePkgs.contains(info.packageName);
            // 华为 / 荣耀账号与基础服务：现在和 GMS 一样装进空间运行，
            // 这里只用来在应用列表打「HMS」角标，不再影响启动路径。
            entry.systemBridge = com.hush.core.HmsCore.isHuaweiFamily(info.packageName);
            // 模块只出现在「模块空间」里，普通空间一律不显示，避免混排。
            if (entry.module && userId != Prefs.USER_MODULE) continue;
            if (entry.apkPath != null) {
                File f = new File(entry.apkPath);
                if (f.exists()) entry.size = f.length();
            }
            entry.label = resolveLabel(context, entry);
            result.add(entry);
        }
        sort(result);
        return result;
    }

    // ------------------------------------------------------------------ 本机已安装应用

    public static List<AppEntry> loadLocal(Context context) {
        List<AppEntry> result = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolves;
        try {
            resolves = pm.queryIntentActivities(intent, 0);
        } catch (Throwable t) {
            return result;
        }

        String host = context.getPackageName();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : resolves) {
            ApplicationInfo ai = ri.activityInfo != null ? ri.activityInfo.applicationInfo : null;
            if (ai == null || ai.packageName == null) continue;
            if (ai.packageName.equals(host) || ai.packageName.startsWith("com.hush")) continue;
            if (!seen.add(ai.packageName)) continue;

            AppEntry entry = new AppEntry(ai.packageName);
            entry.apkPath = ai.sourceDir;
            entry.iconRes = ai.icon;
            entry.labelRes = ai.labelRes;
            entry.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                entry.versionName = pi.versionName;
                entry.size = new File(ai.sourceDir).length();
                entry.targetSdk = ai.targetSdkVersion;
            } catch (Throwable ignored) {
            }
            entry.label = resolveLabel(context, entry);
            result.add(entry);
        }
        sort(result);
        return result;
    }

    private static void sort(List<AppEntry> list) {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label == null ? "" : a.label,
                        b.label == null ? "" : b.label);
            }
        });
    }

    // ------------------------------------------------------------------ 图标与名称

    public static Drawable loadIcon(Context context, AppEntry entry) {
        if (entry == null) return null;
        String key = entry.packageName + "/" + entry.apkPath;
        Drawable cached = ICON_CACHE.get(key);
        if (cached != null) return cached;

        // 0. 用户自定义图标优先
        Bitmap custom = IconStore.loadIcon(context, entry.packageName);
        if (custom != null) {
            Drawable d = new BitmapDrawable(context.getResources(), custom);
            ICON_CACHE.put(key, d);
            return d;
        }

        Drawable icon = null;
        // 1. 设备上真实安装了该应用（克隆本机应用时最常见）
        try {
            icon = context.getPackageManager().getApplicationIcon(entry.packageName);
        } catch (Throwable ignored) {
        }
        // 2. 直接从 APK 资源读取（仅存在于空间内、设备未安装的应用）
        if (icon == null && entry.apkPath != null) {
            Resources res = apkResources(context, entry.apkPath);
            if (res != null && entry.iconRes != 0) {
                try {
                    icon = res.getDrawable(entry.iconRes, null);
                } catch (Throwable ignored) {
                }
            }
        }
        // 3. 兜底
        if (icon == null) {
            icon = context.getPackageManager().getDefaultActivityIcon();
        }
        ICON_CACHE.put(key, icon);
        return icon;
    }

    private static String resolveLabel(Context context, AppEntry entry) {
        String custom = IconStore.label(context, entry.packageName);
        if (!TextUtils.isEmpty(custom)) return custom;
        if (!TextUtils.isEmpty(entry.label)) return entry.label;
        if (entry.apkPath != null) {
            Resources res = apkResources(context, entry.apkPath);
            if (res != null && entry.labelRes != 0) {
                try {
                    CharSequence cs = res.getText(entry.labelRes);
                    if (!TextUtils.isEmpty(cs)) return cs.toString();
                } catch (Throwable ignored) {
                }
            }
        }
        return entry.packageName;
    }

    private static Resources apkResources(Context context, String apkPath) {
        Resources cached = RES_CACHE.get(apkPath);
        if (cached != null) return cached;
        try {
            AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object cookie = addAssetPath.invoke(am, apkPath);
            if (cookie instanceof Integer && (Integer) cookie == 0) return null;
            Resources res = new Resources(am,
                    context.getResources().getDisplayMetrics(),
                    context.getResources().getConfiguration());
            RES_CACHE.put(apkPath, res);
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自定义图标/名称变化后调用，下一次加载会重新读取。 */
    public static void invalidateIcon(String packageName) {
        if (packageName == null) return;
        List<String> keys = new ArrayList<>();
        for (String k : ICON_CACHE.snapshot().keySet()) {
            if (k.startsWith(packageName + "/")) keys.add(k);
        }
        for (String k : keys) ICON_CACHE.remove(k);
    }

    public static Bitmap toBitmap(Drawable drawable, int size) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    // ------------------------------------------------------------------ 安装

    public static boolean isInstalled(String packageName, int userId) {
        try {
            return HushCore.get().isInstalled(packageName, userId);
        } catch (Throwable t) {
            return false;
        }
    }

    public static InstallResult installApk(File apk, int userId) {
        try {
            return HushCore.get().installPackageAsUser(apk, userId);
        } catch (Throwable t) {
            InstallResult r = new InstallResult(false, String.valueOf(t.getMessage()));
            return r;
        }
    }

    public static InstallResult installFromInstalledApp(String packageName, int userId) {
        try {
            return HushCore.get().installPackageAsUser(packageName, userId);
        } catch (Throwable t) {
            return new InstallResult(false, String.valueOf(t.getMessage()));
        }
    }

    /** 把选中的 APK 复制到私有缓存，返回可安装的文件。 */
    public static File copyToCache(Context context, Uri uri, String prefix) throws Exception {
        File dir = new File(context.getCacheDir(), "import");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cache dir unavailable");
        }
        File out = new File(dir, prefix + "_" + System.currentTimeMillis() + ".apk");
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法读取文件");
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            os.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                os.close();
            } catch (Throwable ignored) {
            }
        }
        if (out.length() <= 0) {
            out.delete();
            throw new IllegalStateException("空文件");
        }
        return out;
    }

    // ------------------------------------------------------------------ 运行管理

    /**
     * HMS Core 没有 LAUNCHER 入口（官方包本来就不带桌面图标），
     * {@code getLaunchIntentForPackage} 必然返回 null。用户点它其实是想看
     * 华为账号，这里直接拉账号中心的 activity-alias。
     */
    public static boolean openHmsAccount(int userId) {
        // 点图标先弹一次版本号：日志里 ver= 那行对不上时，用户至少能直接看到
        // 手机上跑的到底是哪一版，不用再猜。
        toastVersion();
        try {
            Intent i = new Intent();
            // 必须 setPackage：LauncherActivity 是靠 intent.getPackage() 反查
            // 目标空间的，只给 ComponentName 它会拿到 null，直接判「未安装」。
            i.setPackage("com.huawei.hwid");
            i.setComponent(new android.content.ComponentName(
                    "com.huawei.hwid",
                    "com.huawei.hwid20.AccountCenter.CenterActivityAlias"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            HushCore.get().startActivity(i, userId);
            Diag.log("launch", "open HMS account center uid=" + userId);
            return true;
        } catch (Throwable t) {
            Diag.log("launch", "open HMS account center FAILED\n" + Diag.stack(t));
            return false;
        }
    }

    /** 点 HMS 图标时弹一次版本号，日志拿不准的时候用肉眼对。 */
    private static final long[] sToastAt = {0L};

    private static void toastVersion() {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (sToastAt) {
                if (now - sToastAt[0] < 1500L) return;
                sToastAt[0] = now;
            }
            final Context c = HushCore.getContext();
            if (c == null) return;
            final String v = "HushSpace " + com.hush.space.BuildConfig.VERSION_NAME
                    + "(" + com.hush.space.BuildConfig.VERSION_CODE + ")";
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                android.widget.Toast.makeText(c, v,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    public static boolean launch(Context context, String packageName, int userId) {
        boolean ok = false;
        toastVersion();
        // 启动目标前挂上 logcat 抓取，捕获 native 崩溃签名
        try {
            LogcatTap.start(context, 60_000L);
            // 6 秒后把 crash buffer 补写进 diag.log，用户只复制一个文件就够
            LogcatTap.scheduleCrashDump(context, 6000L);
        } catch (Throwable ignored) {
        }
        try {
            ok = HushCore.get().launchApk(packageName, userId);
            Diag.log("launch", "launchApk " + packageName + " -> " + ok);
        } catch (Throwable t) {
            Diag.log("launch", "launchApk " + packageName + " THROW\n" + Diag.stack(t));
        }
        if (!ok) {
            // 退回到直接通过代理启动
            try {
                Intent intent = HushCore.getBPackageManager()
                        .getLaunchIntentForPackage(packageName, userId);
                Diag.log("launch", "fallback intent=" + intent);
                if (intent != null) {
                    HushCore.get().startActivity(intent, userId);
                    ok = true;
                }
            } catch (Throwable t) {
                Diag.log("launch", "fallback THROW\n" + Diag.stack(t));
            }
        }
        return ok;
    }

    public static void uninstall(String packageName, int userId) {
        HushCore.get().uninstallPackageAsUser(packageName, userId);
    }

    public static void clearData(String packageName, int userId) {
        HushCore.get().clearPackage(packageName, userId);
    }

    public static void stop(String packageName, int userId) {
        HushCore.get().stopPackage(packageName, userId);
    }

    public static boolean isRunning(String packageName, int userId) {
        try {
            return HushCore.get().isAppRunning(packageName, userId);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 桌面快捷方式

    public static boolean createShortcut(Context context, AppEntry entry, int userId) {
        try {
            Intent launch = HushCore.getBPackageManager()
                    .getLaunchIntentForPackage(entry.packageName, userId);
            if (launch == null) return false;

            Intent target = new Intent();
            target.setClassName(context, LauncherActivity.class.getName());
            target.putExtra(LauncherActivity.KEY_INTENT, launch);
            target.putExtra(LauncherActivity.KEY_PKG, entry.packageName);
            target.putExtra(LauncherActivity.KEY_USER_ID, userId);
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent shortcut = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME, entry.label);
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT, target);
            shortcut.putExtra("duplicate", false);
            Bitmap icon = toBitmap(loadIcon(context, entry), 192);
            if (icon != null) {
                shortcut.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
            }
            context.sendBroadcast(shortcut);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.CHINA, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.CHINA, "%.1f MB", mb);
        return String.format(Locale.CHINA, "%.2f GB", mb / 1024.0);
    }
}
