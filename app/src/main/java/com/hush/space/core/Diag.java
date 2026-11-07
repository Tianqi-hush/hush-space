package com.hush.space.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 业务侧诊断日志入口。真正的实现放在引擎层 com.hush.utils.Diag，
 * 这样宿主进程与 guest 进程写的是同一个文件、同一套时间戳。
 */
public final class Diag {

    private Diag() {
    }

    public static void init(Context context) {
        try {
            com.hush.utils.Diag.init(context);
            com.hush.utils.Diag.log("boot", "app=" + context.getPackageName());
        } catch (Throwable ignored) {
        }
    }

    public static void log(String tag, String msg) {
        com.hush.utils.Diag.log(tag, msg);
    }

    public static String stack(Throwable t) {
        return com.hush.utils.Diag.stack(t);
    }

    public static String currentProcessName() {
        return com.hush.utils.Diag.processName();
    }

    public static File file(Context context) {
        File f = com.hush.utils.Diag.file();
        return f != null ? f : com.hush.utils.Diag.resolve(context);
    }

    /**
     * 追加一段原文到诊断文件。走独立的 FileOutputStream，不经过引擎层那个
     * 常驻 fd —— 崩溃摘要是在 guest 进程死掉之后由宿主进程补写的。
     */
    public static void appendRaw(Context context, String text) {
        if (text == null || text.isEmpty()) return;
        File f = file(context);
        if (f == null) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, true);
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 关键事件：不含逐条文件访问轨迹，只留里程碑、崩溃现场和退出调用点。 */
    private static final String[] KEY_HINTS = {
            "SIGNAL", "BT #", "UW #", "INS @", "JNI ", "ABORT", "ASSERT",
            "SHUTDOWN", "bind: ", "guest: ", "hb: ", "config: ", "boot: ",
            "io: ", "exit: ",
            // native 侧新增的几层网：ART 入口参数替换、libc 字符串兜底、页内空指针放行
            "ART-", "STR-", "IO-HOOK", "SEGV-null",
            // 主线程栈采样：崩溃前最后一帧 Java 现场
            "stack: ",
            // so 加载结果：壳启动时 dlopen 的每一个库
            "SO ",
    };

    /**
     * 只挑关键行。
     *
     * 之所以单独做一份：导出的文本走系统分享，接收端往往有长度上限，
     * 上次那份日志正好在 makeApplication 的十几条 IO stat 之后被截断，
     * 把唯一能定性的 SIGNAL / BT 全截掉了。关键事件放在最前面，
     * 就算后面全丢也不影响判断。
     */
    public static String readKey(Context context) {
        File f = file(context);
        if (f == null || !f.exists()) return "";
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            java.util.ArrayList<String> hits = new java.util.ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                // 逐条文件访问轨迹是 "IIO open ..."，不和时间戳行混淆
                if (line.startsWith("IO ")) continue;
                for (String k : KEY_HINTS) {
                    if (line.contains(k)) {
                        hits.add(line);
                        break;
                    }
                }
            }
            r.close();
            int from = Math.max(0, hits.size() - 120);
            StringBuilder sb = new StringBuilder();
            if (from > 0) sb.append("(关键事件共 ").append(hits.size())
                    .append(" 行，仅列最后 120 行)\n");
            for (int i = from; i < hits.size(); i++) sb.append(hits.get(i)).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    public static String readAll(Context context) {
        File f = file(context);
        if (f == null || !f.exists()) return "";
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
            r.close();
            // 只保留尾部，避免整份历史日志把最新一次运行挤出消息长度。
            // 关键行已经单独抽了一份放最前面，这里只做交叉核对的尾部。
            int from = Math.max(0, lines.size() - 400);
            StringBuilder sb = new StringBuilder();
            if (from > 0) {
                sb.append("(已省略前 ").append(from).append(" 行历史日志)\n");
            }
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }
}
