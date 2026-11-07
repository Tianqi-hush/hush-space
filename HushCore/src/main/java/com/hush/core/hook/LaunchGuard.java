package com.hush.core.hook;

import com.hush.core.env.BEnvironment;
import com.hush.utils.Diag;
import com.hush.utils.FileUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 启动守卫：记录每个「空间 + 包名」上一次启动有没有走到 {@code bound} 阶段。
 *
 * <p>加固壳的崩溃发生在 {@code Application.attachBaseContext} 的 native 代码里，
 * Java 层 try/catch 拦不住，进程直接死。唯一能做的就是把"这次没走完"记下来，
 * 下一次换个更保守/更兼容的组合再试。
 *
 * <p>用法：{@link #begin} 在 makeApplication 之前调用（先把本次标成"可能崩"），
 * {@link #ok} 在 handleBindApplication 跑完后调用（改成"正常"）。
 * 进程若中途被杀，标记就停在 fail 状态，下次 begin 读到的就是连续失败次数。
 */
public final class LaunchGuard {

    /** 连续失败上限，防止计数无限增长。 */
    private static final int MAX_FAILS = 9;

    private LaunchGuard() {
    }

    private static File dir() {
        File d = new File(BEnvironment.getSystemDir(), "launch");
        FileUtils.mkdirs(d);
        return d;
    }

    private static File tag(String pkg, int uid) {
        return new File(dir(), uid + "-" + pkg + ".tag");
    }

    /**
     * @return 上一次连续失败次数；0 表示上次正常启动过（或第一次装）。
     */
    public static int begin(String pkg, int uid) {
        if (pkg == null || pkg.isEmpty()) return 0;
        int fails = 0;
        try {
            File f = tag(pkg, uid);
            String s = read(f);
            if (s != null && s.startsWith("fail:")) {
                fails = parseInt(s.substring(5));
            }
            // 先按"会崩"记，跑完再改成 ok。中途被杀就自然留在 fail。
            write(f, "fail:" + Math.min(fails + 1, MAX_FAILS));
            if (fails > 0) {
                Diag.log("guard", "上次启动未完成 pkg=" + pkg + " uid=" + uid
                        + " 连续失败=" + fails);
            }
        } catch (Throwable t) {
            Diag.log("guard", "记录启动状态失败 " + t);
        }
        return fails;
    }

    /** 启动流程全部跑完，清零。 */
    public static void ok(String pkg, int uid) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            write(tag(pkg, uid), "ok");
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String read(File f) {
        if (f == null || !f.exists() || f.length() <= 0) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[128];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void write(File f, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
