package com.hush.core.env;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import com.hush.utils.compat.BuildCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Locale;

import com.hush.HushCore;
import com.hush.utils.FileUtils;
import com.hush.app.BActivityThread;
import com.hush.core.system.api.MetaActivationManager;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class BEnvironment {
   
  //  private static final File sExternalVirtualRoot = HushCore.getContext().getExternalFilesDir("EliteCore");
    private static final File sVBoxRoot = HushCore.getContext().getFilesDir();
  //  private static final File sVBoxRoot = new File(HushCore.getContext().getCacheDir().getParent(), "EliteCore");

    /**
     * 外部存储沙盒根。
     *
     * 宿主 targetSdk=28，安装后仍走 legacy 存储模式（不受分区存储限制），
     * 所以 /storage/emulated/0/SdCard 是可写的 —— 日志里 open 拿到 fd=110
     * 已经证实这一点。这里保持路径不变，避免已有 guest 的外部数据搬家。
     *
     * 真正的问题是：这个根目录下的 Android/data/<pkg>、Download、.data 等
     * 子目录从来没被创建过（getExternalDataDir 只有定义、没有 mkdirs，
     * RemoveUserExecutor 里那行创建还被注释掉了）。加固壳启动后第一件事
     * 就是去这些位置读配置，一 open 就 ENOENT，随后走错误分支，
     * 返回码被当作指针使用 —— 这才是 0xfffff9 的来路。
     * 目录创建统一交给 ensureExternalDirs()。
     */
    private static File sExternalVBoxRoot = null;

    public static File getExternalVBoxRoot() {
        File root = sExternalVBoxRoot;
        if (root != null) return root;
        root = pickExternalRoot();
        sExternalVBoxRoot = root;
        com.hush.utils.Diag.log("env", "外部存储根=" + root.getAbsolutePath()
                + " 可写=" + probeWritable(root));
        return root;
    }

    /**
     * 选一个「真的能写」的外部存储根。
     *
     * <p>原实现直接拿 {@link Environment#getExternalStorageDirectory()}，
     * 再在它下面建 SdCard 目录。Android 11+ 上即使宿主 targetSdk 很低，
     * 在共享存储根目录建目录也会被 FUSE 直接拒绝 —— mkdirs 静默返回 false，
     * 于是 /storage/emulated/0/SdCard 从来就没被创建出来。guest 的
     * getExternalFilesDir() 拿到这个不存在的路径，写文件全部 ENOENT，
     * 模块（zll 的 skymod_boot.log、libfeature.so 解压）第一步就断了。
     *
     * <p>所以先探测真实根目录能不能建目录：能就保持老路径（老数据不搬家），
     * 不能就退到宿主自己的外部私有目录 —— 它一定可写，而且仍在
     * /storage/emulated/0 下，FileProvider、媒体库之类的行为不变。
     */
    /**
     * 宿主 App 的私有外部目录，<b>按宿主包名硬拼</b>。
     *
     * <p>为什么不用 {@code getContext().getExternalFilesDir(null)}：
     * guest 进程里 packageName 被伪装成目标应用，这个 API 会返回
     * {@code Android/data/<目标包名>/files}，于是宿主写在
     * {@code Android/data/com.hush.space/files} 下的配置，guest 一个都读不到。
     * 表现就是「设置里配好了，进应用完全不生效」，而且两边的日志各写各的。
     *
     * <p>按 {@code HushCore.getHostPkg()} 拼路径，宿主和所有 guest 进程
     * 看到的都是同一份文件。
     */
    private static File hostExternalFiles() {
        try {
            File real = Environment.getExternalStorageDirectory();
            if (real != null && HushCore.getHostPkg() != null) {
                return new File(real, "Android/data/" + HushCore.getHostPkg() + "/files");
            }
        } catch (Throwable ignored) {
        }
        try {
            Context c = HushCore.getContext();
            if (c != null) return c.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File pickExternalRoot() {
        // 1) 老版本可能已经把 guest 存档放在共享存储 /sdcard/SdCard（或拆分后的
        //    SdCard_u0/SdCard_u1…）。只要其中任何一个有内容就继续沿用，避免
        //    升级后看起来像「存档全丢了」。用户不给存储权限时这里读不到，自然跳过。
        try {
            File real = Environment.getExternalStorageDirectory();
            if (real != null) {
                File[] cands = real.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File d, String name) {
                        return name.equals("SdCard") || name.startsWith("SdCard_u");
                    }
                });
                if (cands != null) {
                    for (File c : cands) {
                        if (!c.isDirectory()) continue;
                        String[] kids = c.list();
                        if (kids != null && kids.length > 0) return real;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) 默认走 App 私有外部目录：不需要 READ/WRITE_EXTERNAL_STORAGE，
        //    也不需要 MANAGE_EXTERNAL_STORAGE，系统按包名授权。
        try {
            File ext = hostExternalFiles();
            if (ext != null) {
                File root = new File(ext, "vbox");
                if (probeWritable(root)) {
                    return root;
                }
            }
        } catch (Throwable ignored) {
        }
        File fallback = new File(sVBoxRoot, "external");
        FileUtils.mkdirs(fallback);
        return fallback;
    }

    /** 真正建一次子目录 + 写一个探针文件，只有两步都成功才算可写。 */
    private static boolean probeWritable(File dir) {
        try {
            File probeDir = new File(dir, ".hush_probe");
            if (!probeDir.exists() && !probeDir.mkdirs()) {
                return false;
            }
            File probe = new File(probeDir, "w");
            boolean ok = probe.exists() || probe.createNewFile();
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            //noinspection ResultOfMethodCallIgnored
            probeDir.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

	public static void load() {
		FileUtils.mkdirs(sVBoxRoot);
        // 先把数据根定下来，再迁移老版本留在内部 files/ 里的配置。
        migrateToDataRoot();
        // 2.6.1 及更早是全局共用一个 SdCard，这里并进空间 0（同分区 rename，零拷贝）。
        migrateSharedSdCard(getExternalVBoxRoot());
        FileUtils.mkdirs(getExternalStorageDirectory());
		FileUtils.mkdirs(getSystemDir());
		FileUtils.mkdirs(getCacheDir());
		FileUtils.mkdirs(getProcDir());
        // 内部 files/system 留一份镜像：万一外部目录某次挂载失败，
        // 设置仍然读得回来，不会变成「一片默认值」。
        SpaceSettingsStore.syncMirrors();
	}
    
    public static File getVBoxRoot() {
		return sVBoxRoot;
	}

    // ---------------------------------------------------------------- 数据根
    //
    // 2.6.1 起把「纯数据」从内部 /data/user/0/<pkg>/files 搬到 App 私有外部目录
    // /storage/emulated/0/Android/data/<pkg>/files/space。这个位置：
    //   * 不需要 READ/WRITE_EXTERNAL_STORAGE，也不需要 MANAGE_EXTERNAL_STORAGE，
    //     系统按包名直接授予读写权限；
    //   * 卸载 App 时随包一起清掉，不会留垃圾；
    //   * 仍然在 /storage/emulated/0 下，ADB、系统文件管理器都能直接看。
    //
    // 注意：只有配置、图标这类「数据」能搬。base.apk、dex、*.so 必须留在内部
    // 存储 —— /storage/emulated 是 noexec 的 FUSE，从那里 dlopen 会直接
    // Permission denied，模块一个都跑不起来。所以 getVBoxRoot() 不动。

    private static volatile File sDataRoot = null;

    /**
     * 上一次真正用过的数据根，记在内部 files/.dataroot。
     *
     * <p>这是 2.6.6 修「配置好了重开全没」的关键。旧实现每次启动都现场探测外部目录：
     * 冷启动时 /storage/emulated 可能还没准备好（或用户没解锁、外置存储正在挂载），
     * 探针失败就静默回退内部 files/。于是同一天里两次启动可能落在两个完全不同的根：
     * 第一次写内部，第二次读外部 —— 用户看到的就是「刚配好的东西全没了」，
     * 而且 space-global.json、device_profile_*.json、user.conf 一起消失。
     *
     * <p>现在把选中的根记下来，下次启动优先复用；只有它真的不可写了才重新选，
     * 换根时先把旧根的数据并过来，绝不留下两份互不相干的数据。
     */
    private static final String ROOT_MARKER = ".dataroot";

    /** 纯数据根：优先 App 私有外部目录，不可用时回退内部 files/。 */
    public static File getDataRoot() {
        File cached = sDataRoot;
        if (cached != null) return cached;
        synchronized (BEnvironment.class) {
            if (sDataRoot != null) return sDataRoot;
            File external = null;
            try {
                File ext = hostExternalFiles();
                if (ext != null) {
                    File dir = new File(ext, "space");
                    if (ensureWritableDir(dir)) external = dir;
                }
            } catch (Throwable t) {
                com.hush.utils.Diag.log("env", "外部数据根不可用: " + t);
            }

            File marked = readRootMarker();
            if (marked != null && (!marked.isDirectory() || !ensureWritableDir(marked))) {
                com.hush.utils.Diag.log("env", "上次的数据根已不可用: " + marked.getAbsolutePath());
                marked = null;
            }

            File picked;
            if (external != null) {
                if (marked != null && !marked.equals(external)) {
                    int n = mergeTree(marked, external);
                    com.hush.utils.Diag.log("env", "数据根从 " + marked.getAbsolutePath()
                            + " 并回外部 " + external.getAbsolutePath() + "，合并 " + n + " 个文件");
                } else if (marked == null) {
                    // 2.6.6 之前没有 .dataroot 标记。外部目录的 .migrated 可能早就写过了，
                    // 但当时主进程其实把配置落在了内部 files/ —— 那批文件再也没人搬。
                    // 首次升级上来先并一次，避免「升级完设置全空」。
                    int n = mergeInternalInto(external);
                    if (n > 0) {
                        com.hush.utils.Diag.log("env", "首次并档：内部 files/ -> "
                                + external.getAbsolutePath() + "，" + n + " 个文件");
                    }
                }
                picked = external;
            } else if (marked != null) {
                picked = marked;
            } else {
                picked = sVBoxRoot;
            }

            sDataRoot = picked;
            writeRootMarker(picked);
            com.hush.utils.Diag.log("env", "数据根=" + picked.getAbsolutePath()
                    + " 外部=" + (!picked.equals(sVBoxRoot)));
            return picked;
        }
    }

    private static File readRootMarker() {
        FileInputStream in = null;
        try {
            File f = new File(sVBoxRoot, ROOT_MARKER);
            if (!f.exists() || f.length() <= 0) return null;
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            if (n <= 0) return null;
            String path = new String(buf, 0, n, "UTF-8").trim();
            return path.isEmpty() ? null : new File(path);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeRootMarker(File root) {
        FileOutputStream out = null;
        try {
            File f = new File(sVBoxRoot, ROOT_MARKER);
            out = new FileOutputStream(f);
            out.write(root.getAbsolutePath().getBytes("UTF-8"));
            out.flush();
            try {
                android.system.Os.fsync(out.getFD());
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            com.hush.utils.Diag.log("env", "写数据根标记失败 " + t);
        } finally {
            if (out != null) try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把旧根并进新根：目标缺失就复制，目标更旧就用源覆盖。
     * 用于「上次落在内部、这次外部可用了」这种换根场景，两边都不丢数据。
     */
    private static int mergeTree(File src, File dst) {
        if (src == null || !src.isDirectory()) return 0;
        File[] children = src.listFiles();
        if (children == null) return 0;
        FileUtils.mkdirs(dst);
        int n = 0;
        for (File c : children) {
            if (c.getName().startsWith(".")) continue;
            File target = new File(dst, c.getName());
            if (c.isDirectory()) {
                n += mergeTree(c, target);
            } else if (!target.exists() || c.lastModified() > target.lastModified()) {
                if (copyFileNewer(c, target)) n++;
            }
        }
        return n;
    }

    /**
     * 把内部 files/ 里的老配置并到指定数据根。
     * 只并 {@code system/}、{@code icons/} 和根下的 {@code device_profile_*.json}，
     * 不去碰 cache/proc/data 这些体积大又不该搬的目录。
     */
    private static int mergeInternalInto(File dst) {
        if (dst == null || dst.equals(sVBoxRoot)) return 0;
        int n = 0;
        n += mergeTree(new File(sVBoxRoot, "system"), new File(dst, "system"));
        n += mergeTree(new File(sVBoxRoot, "icons"), new File(dst, "icons"));
        File sys = new File(dst, "system");
        FileUtils.mkdirs(sys);
        File[] profiles = sVBoxRoot.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.startsWith("device_profile_") && name.endsWith(".json");
            }
        });
        if (profiles != null) {
            for (File f : profiles) {
                File target = new File(sys, f.getName());
                if (!target.exists() || f.lastModified() > target.lastModified()) {
                    if (copyFileNewer(f, target)) n++;
                }
            }
        }
        return n;
    }

    private static boolean copyFileNewer(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File parent = dst.getParentFile();
            if (parent != null) FileUtils.mkdirs(parent);
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            try {
                out.getFD().sync();
            } catch (Throwable ignored) {
            }
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
        }
    }

    /** 数据是否真的落在了外部目录（设置页用来给用户一个明确答复）。 */
    public static boolean isDataRootExternal() {
        return !getDataRoot().equals(sVBoxRoot);
    }

    /** 建目录 + 写探针，避免 fuse 挂载异常时「mkdirs 返回 true 但写不进去」。 */
    private static boolean ensureWritableDir(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".w");
            if (!probe.exists() && !probe.createNewFile()) return false;
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 一次性迁移：内部 files/{system,icons,device_profile_*.json} → 外部数据根。
     *
     * <p>只复制、不删除源文件。用户如果哪天把 App 搬回内部（外部存储拔掉、
     * 或系统禁止外部私有目录），旧数据还在，不会丢档。迁移完成写
     * {@code .migrated} 标记，之后每次启动只做一次 mkdirs，不再扫目录。
     */
    private static void migrateToDataRoot() {
        File root = getDataRoot();
        if (root.equals(sVBoxRoot)) return;
        File mark = new File(root, ".migrated");
        if (mark.exists()) return;
        int copied = 0;
        try {
            copied += copyTree(new File(sVBoxRoot, "system"), new File(root, "system"));
            copied += copyTree(new File(sVBoxRoot, "icons"), new File(root, "icons"));
            // SpoofManager 老版本把设备档案直接写在 files/ 根下
            File[] profiles = sVBoxRoot.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return name.startsWith("device_profile_") && name.endsWith(".json");
                }
            });
            if (profiles != null) {
                File sys = new File(root, "system");
                FileUtils.mkdirs(sys);
                for (File f : profiles) {
                    copied += copyFile(f, new File(sys, f.getName()));
                }
            }
            //noinspection ResultOfMethodCallIgnored
            mark.createNewFile();
            com.hush.utils.Diag.log("env", "数据迁移完成，复制 " + copied + " 个文件 -> "
                    + root.getAbsolutePath());
        } catch (Throwable t) {
            com.hush.utils.Diag.log("env", "数据迁移失败: " + t);
        }
    }

    /** 递归复制，目标已存在则跳过（外部目录优先，不被旧数据覆盖）。 */
    private static int copyTree(File src, File dst) {
        if (src == null || !src.isDirectory()) return 0;
        FileUtils.mkdirs(dst);
        int n = 0;
        File[] children = src.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            File target = new File(dst, c.getName());
            if (c.isDirectory()) {
                n += copyTree(c, target);
            } else {
                n += copyFile(c, target);
            }
        }
        return n;
    }

    private static int copyFile(File src, File dst) {
        if (dst.exists()) return 0;
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File parent = dst.getParentFile();
            if (parent != null) FileUtils.mkdirs(parent);
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            return 1;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
            if (out != null) try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }
    
    public static ArrayList<String> getAllDex(String str) {
		File appDir = getAppDir(str);
		ArrayList<String> arrayList = new ArrayList<>();
		File[] listFiles = appDir.listFiles(new FilenameFilter() {
	        @Override
		    public boolean accept(File dir, String name) {
			   return name.endsWith(".apk");
		    }
	    });
		if (listFiles != null) {
			for (File file : listFiles) {
				if (BuildCompat.isUpsideDownCake()) {
					try {
						file.setReadOnly();
					} catch (Throwable th) {
						th.printStackTrace();
					}
				}
				arrayList.add(file.getAbsolutePath());
			}
		}
		return arrayList;
	}

    public static File getSystemDir() {
        // 配置类数据，跟着外部数据根走（免存储权限）。
        return new File(getDataRoot(), "system");
    }

    public static File getProcDir() {
        return new File(sVBoxRoot, "proc");
    }

    public static File getCacheDir() {
        return new File(sVBoxRoot, "cache");
    }
    
    public static File getUserDir(int userId) {
        return new File(sVBoxRoot, String.format(Locale.CHINA, "data/user/%d", userId));
    }

    public static File getDeDataDir(String packageName, int userId) {
        return new File(sVBoxRoot, String.format(Locale.CHINA, "data/user_de/%d/%s", userId, packageName));
    }
    
    public static File getDataDir(String packageName, int userId) {
        return new File(sVBoxRoot, String.format(Locale.CHINA, "data/user/%d/%s", userId, packageName));
    }
    
    public static File getAppDir(String packageName) {
        return new File(sVBoxRoot, "data/app/" + packageName);
    }

    public static File getBaseApkDir(String packageName) {
        return new File(sVBoxRoot, "data/app/" + packageName + "/base.apk");
    }

    public static File getUserInfoConf() {
        return new File(getSystemDir(), "user.conf");
    }

    public static File getAccountsConf() {
        return new File(getSystemDir(), "accounts.conf");
    }

    public static File getUidConf() {
        return new File(getSystemDir(), "uid.conf");
    }

    public static File getSharedUserConf() {
        return new File(getSystemDir(), "shared-user.conf");
    }

    public static File getXPModuleConf() {
        return new File(getSystemDir(), "xposed-module.conf");
    }

    public static File getFakeLocationConf() {
        return new File(getSystemDir(), "fake-location.conf");
    }
    
    public static File getFakeDeviceConf() {
        return new File(getSystemDir(), "fake-device.conf");
    }
    
    public static File getPackageConf(String packageName) {
        return new File(getAppDir(packageName), "package.conf");
    }
    
    /**
     * 当前空间的外部存储根。
     *
     * <p>2.6.2 起按空间拆开：{@code vbox/SdCard_u0}、{@code vbox/SdCard_u1}……，
     * 不再所有空间共用同一个 {@code SdCard}。否则两个空间装了同一个包时，
     * {@code SdCard/Android/data/<pkg>} 会互相覆盖，存档、缓存、下载全混在一起。
     */
    public static File getExternalStorageDirectory() {
        return getExternalStorageDirectory(currentUserId());
    }

    /** 指定空间的外部存储根（宿主 UI 展示、跨空间复制时显式传入）。 */
    public static File getExternalStorageDirectory(int userId) {
        if (userId < 0) userId = 0;
        return new File(getExternalVBoxRoot(), "SdCard_u" + userId);
    }

    /**
     * 当前进程属于哪个空间。
     *
     * <p>宿主进程没有 AppConfig，{@link BActivityThread#getUserId()} 固定返回 0；
     * guest 进程返回真实空间号。模块空间是 99，同样走这条路径。
     */
    private static int currentUserId() {
        try {
            return BActivityThread.getUserId();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 把 2.6.1 及更早的共享 {@code SdCard} 并进空间 0，避免升级后存档看不见。 */
    private static void migrateSharedSdCard(File extRoot) {
        try {
            File legacy = new File(extRoot, "SdCard");
            if (!legacy.isDirectory()) return;
            File target = new File(extRoot, "SdCard_u0");
            if (!target.exists()) {
                // 同一分区，rename 是原子的，也不会多占一份空间。
                if (legacy.renameTo(target)) {
                    com.hush.utils.Diag.log("env", "共享 SdCard 已并入 SdCard_u0");
                    return;
                }
            }
            int n = copyTree(legacy, target);
            com.hush.utils.Diag.log("env", "共享 SdCard 已复制 " + n + " 个文件到 SdCard_u0");
        } catch (Throwable t) {
            com.hush.utils.Diag.log("env", "SdCard 拆分失败: " + t);
        }
    }

    public static File getExternalDataDir(String packageName) {
        return getExternalDataDir(packageName, currentUserId());
    }

    public static File getExternalDataDir(String packageName, int userId) {
        return new File(getExternalStorageDirectory(userId),
                String.format(Locale.CHINA, "Android/data/%s", packageName));
    }

    public static File getExternalObbDir(String packageName) {
        return getExternalObbDir(packageName, currentUserId());
    }

    public static File getExternalObbDir(String packageName, int userId) {
        return new File(getExternalStorageDirectory(userId),
                String.format(Locale.CHINA, "Android/obb/%s/", packageName));
    }

    /**
     * 给 guest 铺好外部存储目录。
     *
     * 之前只有 getExternalDataDir() 的定义，没有任何地方真正 mkdirs：
     * RemoveUserExecutor 里那行创建还被注释掉了。结果 guest 以为自己的
     * Android/data/<pkg> 存在，实际一 open 就 ENOENT —— 加固壳的配置读写
     * 全部落空。绑定进程时补一次，幂等。
     */
    public static void ensureExternalDirs(String packageName) {
        try {
            File data = getExternalDataDir(packageName);
            FileUtils.mkdirs(data);
            FileUtils.mkdirs(new File(data, "files"));
            FileUtils.mkdirs(new File(data, "cache"));
            FileUtils.mkdirs(getExternalObbDir(packageName));
            com.hush.utils.Diag.log("env", "guest 外部目录 " + data.getAbsolutePath()
                    + " files=" + new File(data, "files").exists());
        } catch (Throwable t) {
            com.hush.utils.Diag.log("env", "guest 外部目录创建失败 " + t);
        }
        try {
            // 壳会直接往 SD 卡根写调试日志（.data/ntUniSDK/base/debug_log 之类），
            // 也会探测 Download / gameservice / gsp 这些通用目录。少一个就会走
            // 错误分支，所以一并铺好。
            File sd = getExternalStorageDirectory();
            String[] common = {".data", ".data/ntUniSDK", ".data/ntUniSDK/base",
                    "Download", "gameservice", "gsp", "Android", "Android/data", "Android/obb"};
            for (String d : common) {
                FileUtils.mkdirs(new File(sd, d));
            }
        } catch (Throwable ignored) {
        }
    }
    
    public static File getProcDir(int pid) {
        File file = new File(getProcDir(), String.format(Locale.CHINA, "%d", pid));
        FileUtils.mkdirs(file);
        return file;
    }

    public static File getExternalDataFilesDir(String packageName) {
        return new File(getExternalDataDir(packageName), "files");
    }

    public static File getDataFilesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "files");
    }

    public static File getExternalDataCacheDir(String packageName) {
        return new File(getExternalDataDir(packageName), "cache");
    }

    public static File getDataCacheDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "cache");
    }

    public static File getDataLibDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "lib");
    }

    public static File getDataDatabasesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "databases");
    }

    public static File getAppRootDir() {
        return getAppDir("");
    }

    public static File getAppLibDir(String packageName) {
        return new File(getAppDir(packageName), "lib");
    }
    
    public static File getXSharedPreferences(String packageName, String prefFileName) {
		return new File(BEnvironment.getDataDir(packageName, BActivityThread.getUserId()),"shared_prefs/" + prefFileName + ".xml");
	}
    
    
}
