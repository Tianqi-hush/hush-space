package com.hush.space.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 自定义图标与自定义名称的本地存储。
 *
 * <p>设计要点：
 * <ul>
 *   <li>图标按包名落盘为 {@code files/icons/<pkg>.png}，固定 512×512，避免每次从 APK 解析。</li>
 *   <li>名称存在独立的 SharedPreferences，键为 {@code label_<pkg>}，空串表示恢复默认。</li>
 *   <li>两者都只影响宿主 UI 的展示，不修改 APK 内的资源，因此对引擎完全无侵入。</li>
 * </ul>
 */
public final class IconStore {

    private static final String DIR = "icons";
    private static final String SP = "hush_icon_store";
    private static final String KEY_LABEL = "label_";

    private IconStore() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static File dir(Context context) {
        File base;
        try {
            // 跟着引擎的数据根走：能外部就外部（Android/data/<pkg>/files/space/icons），
            // 外部不可用时自动回退内部 files/。
            base = com.hush.core.env.BEnvironment.getDataRoot();
        } catch (Throwable t) {
            base = context.getApplicationContext().getFilesDir();
        }
        File d = new File(base, DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File iconFile(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) return null;
        return new File(dir(context), packageName + ".png");
    }

    public static boolean hasIcon(Context context, String packageName) {
        File f = iconFile(context, packageName);
        return f != null && f.isFile() && f.length() > 0;
    }

    /** 保存自定义图标，失败返回 false。 */
    public static boolean saveIcon(Context context, String packageName, Bitmap bitmap) {
        if (bitmap == null || TextUtils.isEmpty(packageName)) return false;
        File target = iconFile(context, packageName);
        if (target == null) return false;
        OutputStream os = null;
        try {
            os = new FileOutputStream(target);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static Bitmap loadIcon(Context context, String packageName) {
        File f = iconFile(context, packageName);
        if (f == null || !f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clearIcon(Context context, String packageName) {
        File f = iconFile(context, packageName);
        if (f != null && f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 返回自定义名称，未设置时返回 null。 */
    public static String label(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) return null;
        String v = sp(context).getString(KEY_LABEL + packageName, null);
        return TextUtils.isEmpty(v) ? null : v;
    }

    public static void setLabel(Context context, String packageName, String label) {
        if (TextUtils.isEmpty(packageName)) return;
        if (TextUtils.isEmpty(label)) {
            sp(context).edit().remove(KEY_LABEL + packageName).apply();
        } else {
            sp(context).edit().putString(KEY_LABEL + packageName, label.trim()).apply();
        }
    }

    /** 清空某个包的全部自定义项（卸载时调用）。 */
    public static void clear(Context context, String packageName) {
        clearIcon(context, packageName);
        setLabel(context, packageName, null);
        AppRepo.invalidateIcon(packageName);
    }
}
