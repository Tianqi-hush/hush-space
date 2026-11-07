package com.hush.core.env;

import com.hush.entity.SpaceSettings;
import com.hush.utils.Diag;
import com.hush.utils.FileUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;

/**
 * 空间配置的落盘与合并。
 *
 * <p>2.6.4 重写持久化：<b>原子写 + 备份 + 回读校验</b>。
 *
 * <p>2.6.6 再补三件事，专门对付「配好了重新打开又全是默认值」：
 *
 * <ol>
 *   <li><b>双目录镜像</b>：主目录是 {@link BEnvironment#getSystemDir()}，
 *       另在内部 {@code files/system} 留一份。任何一边读不出来就回退另一边，
 *       读取成功后顺手把缺失的一边补回去。以前数据根一旦从外部切到内部
 *       （或反过来），配置文件就「凭空消失」，现在不会了。</li>
 *   <li><b>坏 JSON 必须报错，不能当成空配置</b>：旧 {@code fromJson} 把解析
 *       异常吞掉，返回一个全 null 的对象，界面看起来就是「什么都没配」，
 *       而且永远不会回退 {@code .bak}。现在先严格校验，失败就返回 null。</li>
 *   <li><b>写入串行化</b>：设置页连点几下会并发调用写同一份 {@code .tmp}，
 *       互相截断后 rename 出去的就是半个文件。现在整段写流程加锁。</li>
 * </ol>
 */
public final class SpaceSettingsStore {

    public static final String GLOBAL_FILE = "space-global.json";
    private static final String SPACE_PREFIX = "space-";
    private static final String SPACE_SUFFIX = ".json";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAK_SUFFIX = ".bak";

    private static final Object LOCK = new Object();

    private SpaceSettingsStore() {
    }

    private static File systemDir() {
        File dir = BEnvironment.getSystemDir();
        FileUtils.mkdirs(dir);
        return dir;
    }

    /** 内部镜像目录：数据根在外部时作为兜底，在内部时与主目录相同。 */
    private static File mirrorDir() {
        try {
            File dir = new File(BEnvironment.getVBoxRoot(), "system");
            FileUtils.mkdirs(dir);
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean sameDir(File a, File b) {
        if (a == null || b == null) return true;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Throwable t) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    public static File globalFile() {
        return new File(systemDir(), GLOBAL_FILE);
    }

    public static File spaceFile(int userId) {
        return new File(systemDir(), SPACE_PREFIX + userId + SPACE_SUFFIX);
    }

    // ------------------------------------------------------------------ 全局

    public static SpaceSettings getGlobal() {
        SpaceSettings s = readAny(globalFile());
        if (s == null) {
            s = new SpaceSettings();
            s.hideRoot = Boolean.FALSE;
            s.hideXposed = Boolean.TRUE;
            s.daemon = Boolean.TRUE;
        }
        return s;
    }

    public static boolean setGlobal(SpaceSettings s) {
        String json = s == null ? "{}" : s.toJson().toString();
        boolean ok = writeEverywhere(globalFile(), json);
        Diag.log("cfg", "保存全局配置 " + (ok ? "成功" : "失败") + " " + json);
        return ok;
    }

    // ------------------------------------------------------------------ 单空间

    /** 单空间覆盖项；没配置过返回 null。 */
    public static SpaceSettings getSpace(int userId) {
        SpaceSettings s = readAny(spaceFile(userId));
        if (s == null || s.isEmpty()) return null;
        return s;
    }

    public static boolean setSpace(int userId, SpaceSettings s) {
        if (s == null || s.isEmpty()) {
            clearSpace(userId);
            return true;
        }
        String json = s.toJson().toString();
        boolean ok = writeEverywhere(spaceFile(userId), json);
        Diag.log("cfg", "保存空间配置 uid=" + userId + " " + (ok ? "成功" : "失败") + " " + json);
        return ok;
    }

    public static void clearSpace(int userId) {
        try {
            File f = spaceFile(userId);
            deleteWithBackup(f);
            File m = mirrorDir();
            if (!sameDir(m, f.getParentFile())) deleteWithBackup(new File(m, f.getName()));
        } catch (Throwable ignored) {
        }
        Diag.log("cfg", "清除空间配置 uid=" + userId);
    }

    private static void deleteWithBackup(File f) {
        try {
            //noinspection ResultOfMethodCallIgnored
            if (f.exists()) f.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(f.getParentFile(), f.getName() + BAK_SUFFIX).delete();
        } catch (Throwable ignored) {
        }
    }

    /** 合并后的最终值：空间覆盖 > 全局 > 代码默认。 */
    public static SpaceSettings resolve(int userId) {
        SpaceSettings merged = SpaceSettings.merge(getGlobal(), getSpace(userId));
        if (merged.hideRoot == null) merged.hideRoot = Boolean.FALSE;
        if (merged.hideXposed == null) merged.hideXposed = Boolean.TRUE;
        if (merged.daemon == null) merged.daemon = Boolean.TRUE;
        return merged;
    }

    /**
     * 老版本只有 SharedPreferences 里的三个开关（hide_root / hide_xposed / daemon）。
     * 只在配置文件完全不存在时迁一次，绝不覆盖已有配置。
     */
    public static void seedFromLegacyIfNeeded() {
        try {
            if (globalFile().exists() || new File(systemDir(), GLOBAL_FILE + BAK_SUFFIX).exists()) return;
            android.content.Context c = com.hush.HushCore.getContext();
            if (c == null) return;
            android.content.SharedPreferences legacy =
                    c.getSharedPreferences("hush_space", android.content.Context.MODE_PRIVATE);
            // 没写过就不迁，避免无意义地生成一份默认配置
            if (!legacy.contains("hide_root")
                    && !legacy.contains("hide_xposed")
                    && !legacy.contains("daemon")) {
                return;
            }
            SpaceSettings s = new SpaceSettings();
            s.hideRoot = legacy.getBoolean("hide_root", false);
            s.hideXposed = legacy.getBoolean("hide_xposed", true);
            s.daemon = legacy.getBoolean("daemon", true);
            setGlobal(s);
            Diag.log("cfg", "已从旧偏好迁移全局环境设置 hideRoot=" + s.hideRoot
                    + " hideXposed=" + s.hideXposed + " daemon=" + s.daemon);
        } catch (Throwable t) {
            Diag.log("cfg", "迁移旧偏好失败 " + t);
        }
    }

    // ------------------------------------------------------------------ 镜像同步

    /**
     * 启动时把主目录和内部镜像对齐：谁更新用谁，缺哪边补哪边。
     * 这样即使用户上一次是在「外部不可用」的窗口期里改的配置，这次也能看到。
     */
    public static void syncMirrors() {
        try {
            File a = systemDir();
            File b = mirrorDir();
            if (sameDir(a, b) || b == null) return;
            int n = 0;
            n += syncDir(a, b);
            n += syncDir(b, a);
            if (n > 0) Diag.log("cfg", "配置镜像同步 " + n + " 个文件");
        } catch (Throwable t) {
            Diag.log("cfg", "配置镜像同步失败 " + t);
        }
    }

    /** 把 from 里比 to 新的配置文件复制过去，返回复制数量。 */
    private static int syncDir(File from, File to) {
        File[] kids = from.listFiles();
        if (kids == null) return 0;
        FileUtils.mkdirs(to);
        int n = 0;
        for (File f : kids) {
            String name = f.getName();
            if (!f.isFile()) continue;
            if (!name.endsWith(SPACE_SUFFIX) || name.endsWith(BAK_SUFFIX) || name.endsWith(TMP_SUFFIX)) {
                continue;
            }
            File dst = new File(to, name);
            if (dst.exists() && dst.lastModified() >= f.lastModified()) continue;
            if (copyFile(f, dst)) n++;
        }
        return n;
    }

    private static boolean copyFile(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        File tmp = new File(dst.getParentFile(), dst.getName() + TMP_SUFFIX);
        try {
            File parent = dst.getParentFile();
            if (parent != null) FileUtils.mkdirs(parent);
            in = new FileInputStream(src);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            try {
                FileDescriptor fd = out.getFD();
                if (fd != null && fd.valid()) fd.sync();
            } catch (Throwable ignored) {
            }
            out.close();
            out = null;
            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(dst);
            long t = src.lastModified();
            if (t > 0) {
                //noinspection ResultOfMethodCallIgnored
                dst.setLastModified(t);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
            if (out != null) try {
                out.close();
            } catch (Throwable ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            if (tmp.exists()) tmp.delete();
        }
    }

    // ------------------------------------------------------------------ 读写

    /** 主文件 → 主备份 → 镜像 → 镜像备份；读到哪份就把其它份补齐。 */
    private static SpaceSettings readAny(File f) {
        SpaceSettings s = readWithBackup(f);
        if (s != null) return s;
        File mirror = mirrorDir();
        if (mirror != null && !sameDir(mirror, f.getParentFile())) {
            File mf = new File(mirror, f.getName());
            s = readWithBackup(mf);
            if (s != null) {
                Diag.log("cfg", "主配置缺失，已从镜像恢复 " + f.getName());
                // 镜像里有内容，补回主目录，避免下次又读不到
                writeAtomic(f, s.toJson().toString());
                return s;
            }
        }
        return null;
    }

    /** 主文件读不出来（不存在 / 空 / 坏 JSON）时自动回退到 .bak。 */
    private static SpaceSettings readWithBackup(File f) {
        SpaceSettings s = readFile(f);
        if (s != null) return s;
        File parent = f.getParentFile();
        if (parent == null) return null;
        File bak = new File(parent, f.getName() + BAK_SUFFIX);
        s = readFile(bak);
        if (s != null) {
            Diag.log("cfg", "主配置损坏，已回退备份 " + bak.getName());
        }
        return s;
    }

    private static SpaceSettings readFile(File f) {
        if (f == null || !f.exists() || f.length() <= 0) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty() || !text.startsWith("{")) return null;
            // 严格校验：半截 JSON 必须当成「读失败」，好让上层回退 .bak，
            // 而不是解析出一个全 null 的对象冒充「用户没配过」。
            new JSONObject(text);
            return SpaceSettings.fromJson(text);
        } catch (Throwable t) {
            Diag.log("cfg", "读配置失败 " + f.getName() + " " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 主目录必写，镜像尽力而为；主目录成功即返回 true。 */
    private static boolean writeEverywhere(File f, String json) {
        boolean ok = writeAtomic(f, json);
        File mirror = mirrorDir();
        if (mirror != null && !sameDir(mirror, f.getParentFile())) {
            File mf = new File(mirror, f.getName());
            if (writeAtomic(mf, json)) {
                if (!ok) Diag.log("cfg", "主目录写入失败，已写入镜像 " + mf.getAbsolutePath());
            }
        }
        return ok;
    }

    /** 最近一次配置落盘时间；没有文件返回 0。设置页用来显示「已保存于 …」。 */
    public static long lastSavedAt() {
        try {
            File f = globalFile();
            return f.exists() ? f.lastModified() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 配置文件的真实状态，设置页直接展示，避免用户再猜。 */
    public static String describeGlobal() {
        try {
            File f = globalFile();
            if (!f.exists()) {
                File mirror = mirrorDir();
                File mf = mirror == null ? null : new File(mirror, f.getName());
                if (mf != null && mf.exists()) {
                    return "镜像 · " + mf.getAbsolutePath() + "\n" + readText(mf);
                }
                return "尚未生成\n" + f.getAbsolutePath();
            }
            return f.getAbsolutePath() + "\n" + readText(f);
        } catch (Throwable t) {
            return "读取失败：" + t;
        }
    }

    private static String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "<读取失败>";
        }
    }

    /**
     * 原子写：tmp → fsync → 备份旧文件 → rename 覆盖 → 回读校验。
     *
     * @return 真正落盘并且内容一致才返回 true
     */
    private static boolean writeAtomic(File f, String json) {
        synchronized (LOCK) {
            File dir = f.getParentFile();
            if (dir == null) return false;
            FileUtils.mkdirs(dir);
            File tmp = new File(dir, f.getName() + TMP_SUFFIX);
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(tmp);
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    FileDescriptor fd = out.getFD();
                    if (fd != null && fd.valid()) fd.sync();
                } catch (Throwable ignored) {
                }
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
                out = null;

                // 旧文件先留一份备份；备份也失败不影响主流程
                if (f.exists() && f.length() > 0) {
                    File bak = new File(dir, f.getName() + BAK_SUFFIX);
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        if (bak.exists()) bak.delete();
                        //noinspection ResultOfMethodCallIgnored
                        f.renameTo(bak);
                    } catch (Throwable ignored) {
                    }
                }
                if (!tmp.renameTo(f)) {
                    // rename 失败（极少数文件系统）就退回复制
                    FileInputStream in = new FileInputStream(tmp);
                    out = new FileOutputStream(f);
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                    try {
                        FileDescriptor fd = out.getFD();
                        if (fd != null && fd.valid()) fd.sync();
                    } catch (Throwable ignored) {
                    }
                    in.close();
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                    out = null;
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }

                // 回读校验：写进去什么，读出来必须一模一样
                SpaceSettings check = readFile(f);
                if (check == null) return false;
                SpaceSettings want = SpaceSettings.fromJson(json);
                return check.toJson().toString().equals(want.toJson().toString());
            } catch (Throwable t) {
                Diag.log("cfg", "写配置失败 " + f.getName() + " " + t);
                return false;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                if (tmp.exists()) tmp.delete();
            }
        }
    }
}
