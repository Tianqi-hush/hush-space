package com.hush.core;

import android.content.Context;
import android.os.SystemClock;
import android.system.Os;

import com.hush.app.BActivityThread;
import com.hush.core.env.BEnvironment;
import com.hush.utils.Diag;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 容器内的 {@code su} 兼容层。
 *
 * <p>guest 进程跑在宿主 UID 下，设备上又没有真的 su，模块里任何
 * {@code Runtime.exec("su")} 都会抛 ENOENT。Star 这类模块把它当成
 * 「没有 root」，于是功能开关全部走空分支 —— 表现就是菜单点得动、功能没反应。
 *
 * <p>这里做三件事：
 * <ol>
 *   <li>拦下 {@code Runtime.exec} / {@code ProcessBuilder.start}，识别 su；</li>
 *   <li>{@code su} 无参时返回一个「虚拟 root shell」，模块往 stdin 写的每条命令
 *       都由 {@code /system/bin/sh -c} 真正执行，输出原样回给模块；</li>
 *   <li>命令里的 guest 路径（/data/data/包名、/sdcard 等）改写到容器真实目录，
 *       让这些命令能读写 guest 自己的数据。</li>
 * </ol>
 *
 * <p>身份类命令（id / whoami）返回 root 身份，避免模块拿到 uid=10xxx 后
 * 又把功能关掉。真正的内核权限做不到，但模块 99% 的 su 用法是文件操作和
 * {@code am/pm}，这些在容器里可以等价完成。
 */
public final class RootShim {

    private static volatile boolean sInstalled;
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Map<String, String> PATHS = new LinkedHashMap<>();
    /** 已经是容器真实路径的前缀。重写时先原样吞掉它们，避免二次套娃。 */
    private static final List<String> TARGETS = new ArrayList<>();
    private static String sGuestPkg = "";
    private static String sDataDir = "";
    private static String sExtDir = "";
    private static String sTmpDir = "";

    private RootShim() {
    }

    public static synchronized void install(Context context, String guestPkg) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            buildPaths(context, guestPkg);
        } catch (Throwable t) {
            Diag.log("root", "路径表构建失败 " + t);
        }
        // 上一次留下的诊断文件通常是 0600，第三方文件管理器读不了；
        // 这里补一次权限和公共镜像，用户升级后立刻就能打开。
        refreshDump();
        // 让 guest 进程对同 uid 的调试工具可见（/proc 可读）。
        try {
            VNative.setDumpable(1);
        } catch (Throwable ignored) {
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String[] cmd = normalize(param);
                    if (cmd == null || cmd.length == 0 || cmd[0] == null) {
                        return;
                    }
                    if (!isSu(cmd[0])) {
                        if (isSuProbe(cmd)) {
                            Diag.log("root", "root 探测 " + join(cmd, 6) + " -> 有 root");
                            param.setResult(new StaticProcess("/system/bin/su\n", 0));
                        }
                        return;
                    }
                    param.setResult(dispatch(cmd));
                } catch (Throwable t) {
                    Diag.log("root", "su 兼容层异常 " + t);
                }
            }
        };

        int ok = 0;
        ok += hookMethod(Runtime.class, "exec", new Class[]{String.class}, hook);
        ok += hookMethod(Runtime.class, "exec", new Class[]{String.class, String[].class}, hook);
        ok += hookMethod(Runtime.class, "exec", new Class[]{String[].class, String[].class}, hook);
        ok += hookMethod(Runtime.class, "exec",
                new Class[]{String.class, String[].class, File.class}, hook);
        ok += hookMethod(Runtime.class, "exec",
                new Class[]{String[].class, String[].class, File.class}, hook);
        ok += hookMethod(ProcessBuilder.class, "start", new Class[]{}, hook);
        Diag.log("root", "su 兼容层已安装 pkg=" + guestPkg + " 钩子=" + ok
                + " data=" + sDataDir + " ext=" + sExtDir);
    }

    private static int hookMethod(Class<?> cls, String name, Class<?>[] sig, XC_MethodHook hook) {
        try {
            Method m = cls.getDeclaredMethod(name, sig);
            XposedBridge.hookMethod(m, hook);
            return 1;
        } catch (Throwable t) {
            Diag.log("root", "hook " + cls.getSimpleName() + "#" + name
                    + java.util.Arrays.toString(sig) + " 失败 " + t);
            return 0;
        }
    }

    // ── 参数归一化 ──────────────────────────────────────────────────────

    private static String[] normalize(XC_MethodHook.MethodHookParam param) {
        if (param.thisObject instanceof ProcessBuilder) {
            ProcessBuilder pb = (ProcessBuilder) param.thisObject;
            List<String> list = pb.command();
            return list == null ? null : list.toArray(new String[0]);
        }
        Object a0 = param.args == null || param.args.length == 0 ? null : param.args[0];
        if (a0 instanceof String) {
            return tokenize((String) a0);
        }
        if (a0 instanceof String[]) {
            return (String[]) a0;
        }
        return null;
    }

    /**
     * {@code Runtime.exec(String)} 在 Java 内部就是按空白切词的（不认引号），
     * 这里保持一致，否则 {@code exec("su -c id")} 会被当成一个叫
     * {@code "su -c id"} 的程序，识别不出来。
     */
    private static String[] tokenize(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return new String[]{""};
        }
        if (t.indexOf(' ') < 0 && t.indexOf('\t') < 0) {
            return new String[]{t};
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = t.length();
        while (i < n) {
            while (i < n && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) ++i;
            int start = i;
            while (i < n && t.charAt(i) != ' ' && t.charAt(i) != '\t') ++i;
            if (i > start) {
                out.add(t.substring(start, i));
            }
        }
        return out.toArray(new String[0]);
    }

    private static boolean isSu(String cmd) {
        if (cmd == null) {
            return false;
        }
        String s = cmd.trim();
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        return "su".equals(name);
    }

    /**
     * 常见的「有没有 root」探测：{@code which su}、{@code command -v su}、
     * {@code test -e /system/bin/su}、{@code ls /system/bin/su}。
     *
     * <p>设备没有 su、hideRoot 又把路径指到 su-fake，这些探测必然失败，
     * 模块会在执行功能前就直接判成无 root。只认这几种形态，别的一律放过。
     */
    private static boolean isSuProbe(String[] cmd) {
        if (cmd.length == 0 || cmd[0] == null) {
            return false;
        }
        String prog = cmd[0].trim();
        // 单字符串形态：exec("/system/xbin/which su")
        if (cmd.length == 1 && prog.contains("which su")) {
            return true;
        }
        if (cmd.length < 2) {
            return false;
        }
        String base = prog.substring(prog.lastIndexOf('/') + 1);
        boolean hasSu = false;
        boolean hasSuPath = false;
        for (int i = 1; i < cmd.length; ++i) {
            String a = cmd[i];
            if (a == null) continue;
            if ("su".equals(a)) hasSu = true;
            if (a.startsWith("/system/bin/su") || a.startsWith("/system/xbin/su")
                    || a.startsWith("/sbin/su") || a.startsWith("/su/bin/su")) {
                hasSuPath = true;
            }
        }
        if (("which".equals(base) || "command".equals(base)) && hasSu) {
            return true;
        }
        if (("test".equals(base) || "ls".equals(base) || "stat".equals(base)) && hasSuPath) {
            return true;
        }
        return false;
    }

    private static String join(String[] cmd, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length && i < max; ++i) {
            if (i > 0) sb.append(' ');
            sb.append(cmd[i]);
        }
        return trim(sb.toString(), 120);
    }

    // ── 分发 ────────────────────────────────────────────────────────────

    private static Process dispatch(String[] cmd) throws IOException {
        int n = SEQ.incrementAndGet();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length && i < 8; ++i) {
            if (i > 0) sb.append(' ');
            sb.append(cmd[i]);
        }
        Diag.log("root", "su#" + n + " [" + trim(sb.toString(), 200) + "]");

        String script = extractScript(cmd);
        if (script != null) {
            return runScript(n, script);
        }
        return new VShell(n);
    }

    /** 把 {@code su -c "cmd"} / {@code su 0 cmd} 里的脚本取出来；无脚本返回 null。 */
    private static String extractScript(String[] cmd) {
        for (int i = 1; i < cmd.length; ++i) {
            String a = cmd[i];
            if (a == null) continue;
            if ("-c".equals(a) || "--command".equals(a)) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < cmd.length; ++j) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(cmd[j]);
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            if ("-s".equals(a) || "--shell".equals(a) || "-p".equals(a) || "-u".equals(a)) {
                ++i;
                continue;
            }
            if (a.startsWith("-")) {
                continue;
            }
            // su 0 / su root / su 1000 cmd...
            if (a.matches("\\d+") || "root".equals(a) || "shell".equals(a)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < cmd.length; ++j) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(cmd[j]);
            }
            return sb.toString();
        }
        return null;
    }

    private static Process runScript(int n, String script) throws IOException {
        String s = unquote(script.trim());
        if (isIdentity(s)) {
            Diag.log("root", "su#" + n + " 根身份查询 -> 返回 uid=0");
            return new StaticProcess(identityOutput(s), 0);
        }
        String cmdline = rewrite(s);
        try {
            VNative.setDumpable(1);
        } catch (Throwable ignored) {
        }

        ProcessBuilder pb;
        File direct = execTarget(cmdline);
        if (direct != null) {
            // 关键：payload 用 sh -c 包一层时，它的父进程是 sh，不是游戏进程。
            // Star 的 main 会顺着父进程 / 进程列表找「光遇」，多一层 sh 就找不到。
            File cwd = workDir(direct);
            Diag.log("root", "su#" + n + " 直接 exec " + direct.getAbsolutePath()
                    + " 父进程=" + parentName() + " cwd=" + cwd);
            pb = new ProcessBuilder(direct.getAbsolutePath());
            if (cwd != null) {
                pb.directory(cwd);
            }
        } else {
            Diag.log("root", "su#" + n + " sh -c " + trim(cmdline, 300));
            pb = new ProcessBuilder("/system/bin/sh", "-c", cmdline);
        }
        pb.redirectErrorStream(true);
        try {
            Map<String, String> env = pb.environment();
            // 容器里的 guest 进程继承的是宿主环境，PATH 里没有 toybox 之外的
            // 常用目录；显式补齐，避免脚本里的 input/screencap 直接 127。
            env.put("PATH",
                    "/system/bin:/system/xbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin");
            env.put("HOME", sExtDir);
            env.put("TMPDIR", sTmpDir);
            env.put("TMP", sTmpDir);
            env.put("ANDROID_DATA", "/data");
            env.put("ANDROID_ROOT", "/system");
            String libs = guestLibDir();
            if (!libs.isEmpty()) {
                env.put("LD_LIBRARY_PATH", libs + ":/system/lib64:/vendor/lib64");
            }
            Process p = pb.start();
            return new TapProcess(n, p);
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " 执行失败 " + t);
            return new StaticProcess("", 127);
        }
    }

    /** 纯二进制、无 shell 语法时直接 exec，保证父进程就是当前游戏进程。 */
    private static File execTarget(String cmdline) {
        if (cmdline == null) {
            return null;
        }
        String t = cmdline.trim();
        if (t.isEmpty() || t.indexOf(' ') >= 0 || t.indexOf('\t') >= 0) {
            return null;
        }
        for (int i = 0; i < t.length(); ++i) {
            char c = t.charAt(i);
            if (c == ';' || c == '|' || c == '&' || c == '$' || c == '>' || c == '<'
                    || c == '`' || c == '*' || c == '?' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '(' || c == ')' || c == '~'
                    || c == '"' || c == '\'' || c == '\\') {
                return null;
            }
        }
        File f = new File(t);
        return f.isFile() && looksElf(f) ? f : null;
    }

    /** Hophix 的 main 在 so/ 下，工作目录应该是上一级（patch.zip 所在处）。 */
    private static File workDir(File f) {
        File p = f.getParentFile();
        if (p == null) {
            return null;
        }
        if ("so".equals(p.getName()) && p.getParentFile() != null) {
            return p.getParentFile();
        }
        return p;
    }

    private static String guestLibDir() {
        try {
            return BEnvironment.getAppLibDir(sGuestPkg).getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 容器在 bind 时已经用 {@code Process.setArgV0(guestPkg)} 把名字改成了光遇包名，
     * 但不同 ROM 上可能被截断或根本没生效。这里实测一遍，不对就再改一次，
     * 并把 /proc 里真正看到的内容写进日志 —— payload 认不认这个进程全靠它。
     */
    private static void ensureProcAlias(int n) {
        try {
            String cur = readProc("self/cmdline");
            String want = sGuestPkg;
            if (want == null || want.isEmpty()) {
                return;
            }
            if (!want.equals(cur)) {
                try {
                    Method m = Class.forName("android.os.Process")
                            .getDeclaredMethod("setArgV0", String.class);
                    m.setAccessible(true);
                    m.invoke(null, want);
                } catch (Throwable t) {
                    Diag.log("root", "su#" + n + " setArgV0 失败 " + t);
                }
            }
            Diag.log("root", "su#" + n + " 进程 pid=" + android.os.Process.myPid()
                    + " cmdline=[" + readProc("self/cmdline") + "]"
                    + " comm=[" + readProc("self/comm") + "]"
                    + " uid=" + android.os.Process.myUid());
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " 进程身份检查失败 " + t);
        }
    }

    /**
     * payload 起来 3 秒后抓一次现场。
     *
     * 目的只有一个：区分「脚本真的挂上了游戏的 Lua」和「只是弹了个已启动」。
     * maps 里有 hophix/lua 相关的 so、或者多出一个常驻的 main 进程，说明注入成功；
     * 两样都没有，就是 payload 跑完就退了，功能根本没人执行。
     */
    private static void postRunSnapshot(final int n) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (Throwable ignored) {
            }
            try {
                File dir = dumpDir();
                if (dir == null) {
                    return;
                }
                int pid = android.os.Process.myPid();
                StringBuilder maps = new StringBuilder("==== su#" + n + " ====\n");
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                new FileInputStream("/proc/" + pid + "/maps")))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line.toLowerCase();
                        if (l.contains("hophix") || l.contains("lua") || l.contains("feature")
                                || l.contains("hush") || l.contains("libmain")) {
                            maps.append(line).append('\n');
                        }
                    }
                }
                append(new File(dir, "maps.txt"), maps.toString());

                StringBuilder ps = new StringBuilder("==== su#" + n + " ====\n");
                File[] ds = new File("/proc").listFiles();
                if (ds != null) {
                    for (File d : ds) {
                        String nm = d.getName();
                        if (nm.isEmpty() || !Character.isDigit(nm.charAt(0))) {
                            continue;
                        }
                        String cl = readAll(new File(d, "cmdline")).replace('\0', ' ').trim();
                        if (cl.isEmpty()) {
                            continue;
                        }
                        if (cl.contains("main") || cl.contains("hophix")
                                || cl.contains("sky") || cl.contains(sGuestPkg)) {
                            ps.append(nm).append(": ").append(cl).append('\n');
                        }
                    }
                }
                append(new File(dir, "procs.txt"), ps.toString());
                Diag.log("root", "su#" + n + " 运行后快照 maps/procs 已写入");
            } catch (Throwable t2) {
                Diag.log("root", "su#" + n + " 运行后快照失败 " + t2);
            }
        }, "hush-post-run");
        t.setDaemon(true);
        t.start();
    }

    private static String readAll(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4096];
            int r = in.read(b);
            return r <= 0 ? "" : new String(b, 0, r, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void append(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
        relax(f, false);
    }

    /** 模拟 payload 的视角：子进程能不能看到自己、pidof/ps 能不能看到光遇。 */
    private static void procSnapshot(int n) {
        try {
            int pid = android.os.Process.myPid();
            String cmd = "echo self=$(tr '\\0' ' ' < /proc/" + pid + "/cmdline);"
                    + "echo pidof=$(pidof " + sGuestPkg + ");"
                    + "echo which=$(command -v pidof);"
                    + "echo psn=$(/system/bin/ps -A | wc -l);"
                    + "echo skydir=$(ls -d /data/user/0/" + sGuestPkg + " 2>&1);"
                    + "echo datauser=$(ls /data/user/0 2>&1 | head -5 | tr '\\n' ',');"
                    // main 反复读某个固定 pid 的 cmdline，这里把 sh 能看到的
                    // 全部进程列出来，对照就知道那个 pid 到底是谁。
                    + "for d in /proc/[0-9]*; do "
                    + "echo \"$d:$(tr '\\0' ' ' < $d/cmdline 2>/dev/null)\"; done | head -12;";
            Process p = new ProcessBuilder("/system/bin/sh", "-c", rewrite(cmd))
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[1024];
                int r;
                while ((r = in.read(buf)) > 0 && sb.length() < 2000) {
                    sb.append(new String(buf, 0, r, "UTF-8"));
                }
            }
            p.waitFor();
            Diag.log("root", "su#" + n + " 系统视角 " + trim(sb.toString().replace('\n', ' '), 460));
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " 系统视角检查失败 " + t);
        }
    }

    /** 从 /proc/self/status 里取一行，用来确认 dumpable 真的生效。 */
    private static String statusLine(String key) {
        try (FileInputStream in = new FileInputStream("/proc/self/status")) {
            byte[] b = new byte[8192];
            int r = in.read(b);
            if (r <= 0) {
                return key + "?";
            }
            for (String line : new String(b, 0, r, "UTF-8").split("\n")) {
                if (line.startsWith(key)) {
                    return line.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return key + "?";
    }

    private static String readProc(String rel) {
        try (FileInputStream in = new FileInputStream("/proc/" + rel)) {
            byte[] b = new byte[512];
            int r = in.read(b);
            if (r <= 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r; ++i) {
                int v = b[i] & 0xff;
                sb.append(v == 0 ? ' ' : (char) v);
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String parentName() {
        try {
            String stat = readProc("self/stat");
            int close = stat.lastIndexOf(')');
            if (close < 0) {
                return "?";
            }
            String[] f = stat.substring(close + 2).split(" ");
            String cmd = readProc(f[1] + "/cmdline");
            return f[1] + ":" + (cmd.isEmpty() ? readProc(f[1] + "/comm") : cmd);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    /**
     * 模块真正要执行的东西经常就是它自己下载到 guest 目录里的一个二进制
     * （Star 用 {@code <files>/Hophix/so/main} 藏 payload）。这里只做三件事：
     * 记录文件是否真的存在、补执行位、把文件拷一份到 guest 外部目录，
     * 方便下一次 diag 直接带走分析 —— 不改变模块看到的执行结果。
     */
    private static void prepareTarget(int n, String s) {
        try {
            if (s.isEmpty() || s.indexOf(' ') >= 0 || s.indexOf(';') >= 0
                    || s.indexOf('|') >= 0 || s.indexOf('&') >= 0
                    || s.startsWith("-")) {
                return;
            }
            File f = new File(s);
            String name = f.getName();
            boolean interesting = s.contains("Hophix") || "main".equals(name);
            if (!f.exists()) {
                if (interesting) {
                    File parent = f.getParentFile();
                    Diag.log("root", "su#" + n + " 目标不存在 " + s
                            + " 父目录=" + describeDir(parent));
                }
                return;
            }
            if (!f.canExecute()) {
                boolean ok = f.setExecutable(true, false);
                Diag.log("root", "su#" + n + " 补执行位 " + s + " -> " + ok);
            }
            if (interesting || looksElf(f)) {
                Diag.log("root", "su#" + n + " 目标 " + s + " len=" + f.length()
                        + " exec=" + f.canExecute() + " magic=" + magicOf(f)
                        + (looksElf(f) ? " elf=" + elfArch(f) : " head=" + headOf(f)));
                dumpForDiag(n, f);
                if (looksElf(f)) {
                    dumpStrings(n, f);
                }
                dumpSmallFiles(n, f);
            }
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " 目标检查失败 " + t);
        }
    }

    private static boolean looksElf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            return in.read(b) == 4 && b[0] == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
        } catch (Throwable t) {
            return false;
        }
    }

    private static String magicOf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; ++i) {
                int v = b[i] & 0xff;
                if (v >= 0x20 && v < 0x7f) {
                    sb.append((char) v);
                } else {
                    sb.append('.');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** ELF 头里的 e_type / e_machine，快速判断是脚本还是 arm64 可执行文件。 */
    private static String elfArch(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[20];
            if (in.read(b) < 20) return "?";
            int type = (b[16] & 0xff) | ((b[17] & 0xff) << 8);
            int machine = (b[18] & 0xff) | ((b[19] & 0xff) << 8);
            return "type=" + type + " machine=0x" + Integer.toHexString(machine)
                    + (machine == 0xB7 ? "(aarch64)" : machine == 0x28 ? "(arm)" : "");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String headOf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[160];
            int n = in.read(b);
            if (n <= 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; ++i) {
                int v = b[i] & 0xff;
                if (v == '\n' || v == '\r') break;
                sb.append(v >= 0x20 && v < 0x7f || v > 0x7f ? (char) v : '.');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describeDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return String.valueOf(dir) + "(无)";
        }
        StringBuilder sb = new StringBuilder(dir.getAbsolutePath()).append(" [");
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) {
            sb.append("空");
        } else {
            for (int i = 0; i < fs.length && i < 12; ++i) {
                if (i > 0) sb.append(", ");
                sb.append(fs[i].getName()).append(fs[i].isDirectory() ? "/" : "(" + fs[i].length() + ")");
            }
        }
        return sb.append(']').toString();
    }

    /** 把 payload 拷到 guest 外部目录，随 diag 一起导出；限制 8MB。 */
    private static void dumpForDiag(int n, File f) {
        if (sExtDir == null || sExtDir.isEmpty()) {
            return;
        }
        try {
            File outDir = dumpDir();
            long len = f.length();
            File dst = new File(outDir, "main_" + len + ".bin");
            if (!dst.exists() && len > 0 && len <= 24L * 1024 * 1024) {
                try (FileInputStream in = new FileInputStream(f);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    long total = 0;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        total += r;
                        if (total > 24L * 1024 * 1024) break;
                    }
                }
                relax(dst, false);
                mirror(dst);
                Diag.log("root", "su#" + n + " payload 已转储 " + dst + " len=" + dst.length());
            }
            File ls = new File(outDir, "hophix_dir.txt");
            try (FileOutputStream out = new FileOutputStream(ls)) {
                File dir = f.getParentFile();
                out.write((describeDir(dir) + "\n").getBytes("UTF-8"));
                if (dir != null && dir.getParentFile() != null) {
                    out.write((describeDir(dir.getParentFile()) + "\n").getBytes("UTF-8"));
                }
            }
            relax(ls, false);
            mirror(ls);
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " dump 失败 " + t);
        }
    }

    /**
     * Hophix 目录里的小文件（.assky / json / txt / cfg）往往是 main 的配置，
     * 特别是那个 1 字节的 .assky —— 它可能就是「游戏 pid / 开关」。
     */
    private static void dumpSmallFiles(int n, File target) {
        if (sExtDir == null || sExtDir.isEmpty()) {
            return;
        }
        try {
            File dir = target.getParentFile();
            if (dir == null) {
                return;
            }
            File root = "so".equals(dir.getName()) && dir.getParentFile() != null
                    ? dir.getParentFile() : dir;
            dumpPatchZip(root, n);
            File outDir = dumpDir();
            File dst = new File(outDir, "hophix_files.txt");
            if (dst.exists() && dst.length() > 0) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            File[] files = root.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile() || f.length() <= 0 || f.length() > 65536) {
                        continue;
                    }
                    String name = f.getName().toLowerCase(Locale.US);
                    if (!(name.endsWith(".assky") || name.endsWith(".json")
                            || name.endsWith(".txt") || name.endsWith(".cfg")
                            || name.endsWith(".ini") || name.endsWith(".conf")
                            || name.endsWith(".dat") || name.endsWith(".pid"))) {
                        continue;
                    }
                    sb.append("==== ").append(f.getAbsolutePath())
                            .append(" len=").append(f.length()).append(" ====\n");
                    byte[] b = new byte[(int) f.length()];
                    try (FileInputStream in = new FileInputStream(f)) {
                        int r = in.read(b);
                        if (r > 0) {
                            String text = new String(b, 0, r, "UTF-8");
                            boolean printable = true;
                            for (int i = 0; i < text.length() && i < 200; ++i) {
                                char c = text.charAt(i);
                                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                                    printable = false;
                                    break;
                                }
                            }
                            if (printable) {
                                sb.append(text).append('\n');
                            } else {
                                for (int i = 0; i < r && i < 512; ++i) {
                                    sb.append(String.format("%02x", b[i] & 0xff));
                                    if ((i & 15) == 15) sb.append('\n');
                                }
                                sb.append('\n');
                            }
                        }
                    }
                }
            }
            try (FileOutputStream out = new FileOutputStream(dst)) {
                out.write(sb.toString().getBytes("UTF-8"));
            }
            relax(dst, false);
            mirror(dst);
            if (sb.length() > 0) {
                Diag.log("root", "su#" + n + " Hophix 配置已转储 " + dst
                        + " len=" + dst.length());
            }
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " Hophix 配置转储失败 " + t);
        }
    }

    /**
     * Star 的脚本层就放在 {@code Hophix/patch.zip} 里（下载后由 main 解包执行）。
     * 「抵制盗版」这类提示、以及各功能真正的开关，都在这些 lua 脚本里。
     * 之前只 dump 小于 64KB 的配置，恰好把 4.6MB 的 patch.zip 漏掉了。
     *
     * <p>这里把原包复制一份，再把里面的 lua / json / txt / cfg 解到 {@code patch/}，
     * 总大小封顶，避免诊断包膨胀。
     */
    private static void dumpPatchZip(File root, int n) {
        try {
            File zip = new File(root, "patch.zip");
            if (!zip.isFile() || zip.length() <= 0 || zip.length() > 24L * 1024 * 1024) {
                return;
            }
            File outDir = dumpDir();
            if (outDir == null) {
                return;
            }
            File dst = new File(outDir, "patch.zip");
            if (!dst.exists() || dst.length() != zip.length()) {
                try (FileInputStream in = new FileInputStream(zip);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[256 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                }
                relax(dst, false);
                mirror(dst);
                Diag.log("root", "su#" + n + " patch.zip 已转储 len=" + dst.length());
            }
            File dir = new File(outDir, "patch");
            if (dir.isDirectory() && dir.list() != null && dir.list().length > 0) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            relax(dir, true);
            int count = 0;
            long total = 0;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements() && count < 400 && total < 12L * 1024 * 1024) {
                    java.util.zip.ZipEntry ze = en.nextElement();
                    if (ze.isDirectory() || ze.getSize() <= 0 || ze.getSize() > 2L * 1024 * 1024) {
                        continue;
                    }
                    String name = ze.getName();
                    String lower = name.toLowerCase(Locale.US);
                    if (!(lower.endsWith(".lua") || lower.endsWith(".json")
                            || lower.endsWith(".txt") || lower.endsWith(".cfg")
                            || lower.endsWith(".ini") || lower.endsWith(".conf"))) {
                        continue;
                    }
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (base.isEmpty() || base.contains("..")) {
                        continue;
                    }
                    File f = new File(dir, base);
                    try (InputStream in = zf.getInputStream(ze);
                         FileOutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        long sz = 0;
                        while ((r = in.read(buf)) > 0) {
                            out.write(buf, 0, r);
                            sz += r;
                        }
                        total += sz;
                        count++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            relax(dir, true);
            Diag.log("root", "su#" + n + " patch 脚本已解出 " + count + " 个，共 "
                    + total + " 字节 -> " + dir);
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " patch.zip 转储失败 " + t);
        }
    }

    private static final String[] KEYS = {
            "/proc", "cmdline", "pidof", "pgrep", "netease", "ptrace", "process_vm",
            "inject", "/dev/input", "screencap", "uinput", "hophix", "getuid", "geteuid",
            "whoami", "ps -", "su -c", "selinux", "magisk", "package", "process",
            "lua", "attach", "/data/data", "/system/bin", "shell", "root"
    };

    /**
     * 把 payload 里有信息量的字符串抠出来（不是整个 15MB 二进制）。
     *
     * <p>main 是 native 程序，容器钩不到它的 open/read，只能从它的字符串里
     * 看它到底按什么找光遇进程（pidof / ps / /proc/pid/cmdline / uid …）。
     * 另外顺着「没找到光遇进程」这条 GBK 文案前后各扫一段，通常就是它的
     * 判定逻辑附近的常量。
     */
    private static void dumpStrings(int n, File f) {
        if (sExtDir == null || sExtDir.isEmpty()) {
            return;
        }
        long len = f.length();
        if (len <= 4096 || len > 64L * 1024 * 1024) {
            return;
        }
        try {
            File outDir = dumpDir();
            File dst = new File(outDir, "main_strings.txt");
            if (dst.exists() && dst.length() > 0) {
                return;
            }
            StringBuilder sb = new StringBuilder(1 << 19);
            StringBuilder run = new StringBuilder();
            long pos = 0;
            long runStart = 0;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0 && sb.length() < 760000) {
                    for (int i = 0; i < r; ++i) {
                        int v = buf[i] & 0xff;
                        if (v >= 0x20 && v < 0x7f) {
                            if (run.length() == 0) {
                                runStart = pos + i;
                            }
                            if (run.length() < 240) {
                                run.append((char) v);
                            }
                        } else {
                            flushRun(sb, run, runStart);
                        }
                    }
                    pos += r;
                }
                flushRun(sb, run, runStart);
            }
            try (FileOutputStream out = new FileOutputStream(dst)) {
                out.write(sb.toString().getBytes("UTF-8"));
            }
            relax(dst, false);
            mirror(dst);
            Diag.log("root", "su#" + n + " payload 字符串已转储 " + dst
                    + " lines=" + countLines(sb));

            // 「光遇」的 GBK 编码；命中说明这段文案在 .rodata 里，附近常量最有用
            byte[] gbk = {(byte) 0xB9, (byte) 0xE2, (byte) 0xD3, (byte) 0xF6};
            long hit = indexOf(f, gbk);
            if (hit >= 0) {
                File near = new File(outDir, "main_strings_near.txt");
                if (!near.exists()) {
                    dumpWindow(f, Math.max(0, hit - 24576), 49152, near);
                    Diag.log("root", "su#" + n + " GBK 文案 @" + hit
                            + " 附近字符串 -> " + near + " len=" + near.length());
                }
            }
        } catch (Throwable t) {
            Diag.log("root", "su#" + n + " 字符串转储失败 " + t);
        }
    }

    private static int countLines(StringBuilder sb) {
        int c = 1;
        for (int i = 0; i < sb.length(); ++i) {
            if (sb.charAt(i) == '\n') {
                ++c;
            }
        }
        return c;
    }

    private static void flushRun(StringBuilder out, StringBuilder run, long off) {
        int n = run.length();
        if (n >= 4) {
            String s = run.toString();
            if (interesting(s)) {
                out.append(off).append('\t').append(s).append('\n');
            }
        }
        run.setLength(0);
    }

    private static boolean interesting(String s) {
        String t = s.toLowerCase(Locale.US);
        for (String k : KEYS) {
            if (t.contains(k)) {
                return true;
            }
        }
        // 绝对路径单独留一份，能看出它要访问哪些设备/目录
        return s.startsWith("/") && s.length() > 4;
    }

    private static long indexOf(File f, byte[] pat) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            long base = 0;
            int carry = 0;
            int r;
            while ((r = in.read(buf, carry, buf.length - carry)) > 0) {
                int total = carry + r;
                for (int i = 0; i + pat.length <= total; ++i) {
                    boolean ok = true;
                    for (int j = 0; j < pat.length; ++j) {
                        if (buf[i + j] != pat[j]) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        return base + i;
                    }
                }
                carry = Math.min(pat.length - 1, total);
                System.arraycopy(buf, total - carry, buf, 0, carry);
                base += total - carry;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void dumpWindow(File f, long off, int len, File dst) {
        try (FileInputStream in = new FileInputStream(f);
             FileOutputStream out = new FileOutputStream(dst)) {
            in.skip(off);
            byte[] buf = new byte[len];
            int r = in.read(buf);
            if (r <= 0) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            StringBuilder run = new StringBuilder();
            for (int i = 0; i < r; ++i) {
                int v = buf[i] & 0xff;
                if (v >= 0x20 && v < 0x7f) {
                    if (run.length() < 200) {
                        run.append((char) v);
                    }
                } else {
                    if (run.length() >= 3) {
                        sb.append(run).append('\n');
                    }
                    run.setLength(0);
                }
                if (sb.length() > 200000) {
                    break;
                }
            }
            out.write(sb.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
        relax(dst, false);
        mirror(dst);
    }

    private static boolean isIdentity(String s) {
        String t = s.replace("\"", "").replace("'", "").trim();
        return "id".equals(t) || "/system/bin/id".equals(t) || "/system/xbin/id".equals(t)
                || "id -u".equals(t) || "id -g".equals(t) || "id -un".equals(t)
                || "whoami".equals(t) || "/system/bin/whoami".equals(t);
    }

    private static String identityOutput(String s) {
        String t = s.replace("\"", "").replace("'", "").trim();
        if ("id -u".equals(t)) {
            return "0\n";
        }
        if ("id -g".equals(t)) {
            return "0\n";
        }
        if ("id -un".equals(t) || "whoami".equals(t) || "/system/bin/whoami".equals(t)) {
            return "root\n";
        }
        return "uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\n";
    }

    // ── 路径重写 ────────────────────────────────────────────────────────

    private static void buildPaths(Context context, String pkg) {
        sGuestPkg = pkg == null ? "" : pkg;
        int userId = 0;
        try {
            userId = BActivityThread.getUserId();
        } catch (Throwable ignored) {
        }
        File data = BEnvironment.getDataDir(sGuestPkg, userId);
        File ext = BEnvironment.getExternalDataDir(sGuestPkg);
        File extRoot = BEnvironment.getExternalStorageDirectory();
        File tmp = new File(context.getCacheDir(), "rsh");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();

        sDataDir = data.getAbsolutePath();
        sExtDir = ext.getAbsolutePath();
        sTmpDir = tmp.getAbsolutePath();

        // 长的放前面，避免 /sdcard 先被替换掉
        put("/data/user/" + userId + "/" + sGuestPkg, sDataDir);
        put("/data/data/" + sGuestPkg, sDataDir);
        put("/data/media/0/Android/data/" + sGuestPkg, sExtDir);
        put("/storage/emulated/0/Android/data/" + sGuestPkg, sExtDir);
        put("/sdcard/Android/data/" + sGuestPkg, sExtDir);
        put("/storage/emulated/" + userId + "/Android/data/" + sGuestPkg, sExtDir);
        put("/data/media/0/Android/obb/" + sGuestPkg,
                BEnvironment.getExternalObbDir(sGuestPkg).getAbsolutePath());
        put("/storage/emulated/0", extRoot.getAbsolutePath());
        put("/storage/emulated/" + userId, extRoot.getAbsolutePath());
        put("/sdcard", extRoot.getAbsolutePath());
        put("/data/local/tmp", sTmpDir);
        put("/data/local/bin", sTmpDir);

        // 这些是「改写后的结果」。模块经常直接用 getFilesDir() 拼路径，
        // 拿到的本来就是容器路径；如果重写不看这个，就会在
        // /data/user/0/com.hush.space/files 后面再套一层同样的前缀。
        TARGETS.clear();
        TARGETS.add(sDataDir);
        TARGETS.add(sExtDir);
        TARGETS.add(tmp.getAbsolutePath());
        TARGETS.sort((a, b) -> b.length() - a.length());
    }

    private static void put(String from, String to) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
            return;
        }
        PATHS.put(from, to);
    }

    // ── 诊断产物的可读性 ────────────────────────────────────────────────
    //
    // 容器里写出来的文件默认是 0600（app 的 umask 0077），第三方文件管理器
    // 打开 hush_dump 下的 txt 时会直接报「私有权限，无法读取」。
    // 这里统一放宽成 0666/0777，并在真实公共目录留一份镜像 —— 那一份
    // 不依赖 app 私有路径，任何文件管理器都能直接打开。

    /** 真实外部存储上的镜像根目录，绕过容器对 Environment 的改写。 */
    private static final String[] MIRROR_ROOTS = {
            "/storage/emulated/0/Download/HushSpace",
            "/sdcard/Download/HushSpace",
    };

    /** 手工改权限。Os.chmod 走 syscall，不受 File API 重定向影响。 */
    private static void relax(File f, boolean dir) {
        if (f == null) {
            return;
        }
        int mode = dir ? 0777 : 0666;
        try {
            Os.chmod(f.getAbsolutePath(), mode);
            return;
        } catch (Throwable ignored) {
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            if (dir) {
                //noinspection ResultOfMethodCallIgnored
                f.setExecutable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Star 的脚本根目录：payload 里硬编码 {@code /storage/emulated/0/YingXing/Star/<版本>/}。
     *
     * <p>真机上这级目录由模块自己创建；容器里如果它没建出来，脚本层就没有落点，
     * 表现是「注入成功、功能全无」，有的版本还会直接给一条防盗版提示。
     * 这里先把根建好，至少让模块的写入路径是通的。
     */
    private static void ensureStarDirs() {
        try {
            if (sExtDir == null || sExtDir.isEmpty()) {
                return;
            }
            File sd = new File(sExtDir).getParentFile();
            if (sd == null) {
                return;
            }
            File yx = new File(sd, "YingXing");
            File star = new File(yx, "Star");
            boolean a = yx.isDirectory() || yx.mkdirs();
            boolean b = star.isDirectory() || star.mkdirs();
            if (a) {
                relax(yx, true);
            }
            if (b) {
                relax(star, true);
            }
            // payload 里写死的是 /sdcard/YingXing/Star/<游戏版本>/，
            // 脚本按版本分目录存放。版本目录不存在时它连落盘都失败，
            // 表现就是「功能已启动」但脚本一行没跑。这里按当前版本建好。
            String ver = "0.16.7";
            try {
                android.content.pm.PackageInfo pi =
                        com.hush.HushCore.getContext().getPackageManager()
                                .getPackageInfo("com.netease.sky", 0);
                if (pi != null && pi.versionName != null && pi.versionName.matches("[0-9.]+")) {
                    ver = pi.versionName;
                }
            } catch (Throwable ignored) {
            }
            File verDir = new File(star, ver);
            boolean c = verDir.isDirectory() || verDir.mkdirs();
            if (c) {
                relax(verDir, true);
            }
            Diag.log("root", "YingXing 目录 " + (b ? "就绪" : "创建失败") + " " + star
                    + " 版本目录=" + verDir + " ok=" + c);
        } catch (Throwable t) {
            Diag.log("root", "YingXing 目录创建失败 " + t);
        }
    }

    private static void dumpStarData() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(8000L);
            } catch (Throwable ignored) {
            }
            try {
                File sd = new File(sExtDir).getParentFile();
                if (sd == null) {
                    return;
                }
                StringBuilder sb = new StringBuilder("sdcard=")
                        .append(sd.getAbsolutePath()).append('\n');
                File yx = new File(sd, "YingXing");
                sb.append("YingXing exists=").append(yx.exists())
                        .append(" dir=").append(yx.isDirectory()).append('\n');
                walkDir(yx, sb, 0, 3);
                File out = new File(dumpDir(), "yingxing.txt");
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(sb.toString().getBytes("UTF-8"));
                }
                relax(out, false);
                Diag.log("root", "Star 数据目录 " + (yx.exists() ? "存在" : "不存在")
                        + " -> " + out);
            } catch (Throwable t2) {
                Diag.log("root", "Star 数据目录扫描失败 " + t2);
            }
        }, "hush-star-data");
        t.setDaemon(true);
        t.start();
    }

    private static void walkDir(File dir, StringBuilder sb, int depth, int max) {
        if (dir == null || depth > max) {
            return;
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : fs) {
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append(f.isDirectory() ? "[D] " : "[F] ").append(f.getName());
            if (!f.isDirectory()) {
                sb.append(" (").append(f.length()).append(')');
            }
            sb.append('\n');
            if (f.isDirectory()) {
                walkDir(f, sb, depth + 1, max);
            }
        }
    }

    /** hush_dump 目录：确保存在并放宽到 0777。 */
    private static File dumpDir() {
        if (sExtDir == null || sExtDir.isEmpty()) {
            return null;
        }
        File outDir = new File(sExtDir, "hush_dump");
        if (!outDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        }
        relax(outDir, true);
        return outDir;
    }

    /**
     * 给探针层用的诊断文件出口：目录已建好、权限已放开。
     * 返回 null 表示外部目录还没就绪，调用方直接跳过写文件。
     */
    public static File dumpFile(String name) {
        File dir = dumpDir();
        if (dir == null) {
            return null;
        }
        File f = new File(dir, name);
        relax(f, false);
        return f;
    }

    /** 把诊断文件同步一份到真实外部存储，MT 这类工具可直接打开。 */
    private static void mirror(File src) {
        if (src == null || !src.isFile()) {
            return;
        }
        for (String root : MIRROR_ROOTS) {
            File dir = new File(root, "hush_dump");
            File dst = new File(dir, src.getName());
            try {
                if (dst.isFile() && dst.length() == src.length()) {
                    return;
                }
                // 优先走 native shell：guest 进程里 Java File API 可能被容器
                // 的 IO 重定向改写到沙盒，真正的 /sdcard 反而写不到。
                if (shellCopy(src, dst)) {
                    return;
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    continue;
                }
                relax(dir, true);
                File tmp = new File(dir, src.getName() + ".tmp");
                try (FileInputStream in = new FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    continue;
                }
                relax(dst, false);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 用 native sh + cp 复制，避开容器对 Java IO 的重定向。 */
    private static boolean shellCopy(File src, File dst) {
        String cmd = "mkdir -p " + shq(dst.getParent()) + " && cp -f "
                + shq(src.getAbsolutePath()) + " " + shq(dst.getAbsolutePath())
                + " && chmod 0666 " + shq(dst.getAbsolutePath());
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            // 必须把输出读干净，否则子进程写满管道会卡在 waitFor
            InputStream in = p.getInputStream();
            byte[] buf = new byte[512];
            long deadline = SystemClock.uptimeMillis() + 4000;
            boolean done = false;
            while (SystemClock.uptimeMillis() < deadline) {
                if (in.available() > 0) {
                    //noinspection ResultOfMethodCallIgnored
                    in.read(buf);
                    continue;
                }
                try {
                    p.exitValue();
                    done = true;
                    break;
                } catch (IllegalThreadStateException alive) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return done && p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String shq(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 已存在的诊断产物：升级后一次性补权限 + 补镜像，不用重新触发。 */
    private static void refreshDump() {
        try {
            File dir = dumpDir();
            if (dir == null) {
                return;
            }
            File[] fs = dir.listFiles();
            if (fs == null) {
                return;
            }
            int n = 0;
            for (File f : fs) {
                if (!f.isFile()) {
                    continue;
                }
                relax(f, false);
                mirror(f);
                ++n;
            }
            if (n > 0) {
                Diag.log("root", "诊断产物已放宽权限并镜像 " + n + " 个 -> "
                        + MIRROR_ROOTS[0] + "/hush_dump");
            }
        } catch (Throwable t) {
            Diag.log("root", "诊断产物处理失败 " + t);
        }
    }

    /**
     * 单次左到右扫描的路径改写。
     *
     * <p>旧实现是 {@code String.replace} 逐条套，两个坑：
     * <ul>
     *   <li>模块传进来的已经是容器路径（getFilesDir() 拼的），
     *       里面又含有 {@code /data/user/0/com.netease.sky}，于是被套第二层，
     *       变成 {@code .../files/data/user/0/com.hush.space/files/data/user/0/...}；</li>
     *   <li>{@code /sdcard} 会先于长键命中，把后面的 Android/data 规则废掉。</li>
     * </ul>
     * 现在先按最长匹配吞掉「已是容器路径」的片段，再匹配 guest 路径键，
     * 命中后整段复制，绝不会回头再扫已经写出去的字符。
     */
    private static String rewrite(String s) {
        if (s == null || s.isEmpty() || PATHS.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 64);
        int i = 0;
        int n = s.length();
        while (i < n) {
            String target = matchLongest(TARGETS, s, i);
            if (target != null) {
                out.append(target);
                i += target.length();
                continue;
            }
            String key = matchLongest(PATHS.keySet(), s, i);
            if (key != null) {
                out.append(PATHS.get(key));
                i += key.length();
                continue;
            }
            out.append(s.charAt(i++));
        }
        return out.toString();
    }

    /** 在 i 处找最长匹配，且要求匹配结束后是路径边界（/ 或结尾）。 */
    private static String matchLongest(Iterable<String> candidates, String s, int i) {
        String best = null;
        for (String k : candidates) {
            if (k == null || k.isEmpty() || !s.startsWith(k, i)) {
                continue;
            }
            int end = i + k.length();
            if (end < s.length() && !isBoundary(s.charAt(end))) {
                continue;
            }
            if (best == null || k.length() > best.length()) {
                best = k;
            }
        }
        return best;
    }

    private static boolean isBoundary(char c) {
        return c == '/' || c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '"' || c == '\'' || c == ';' || c == ')' || c == '>';
    }

    private static String trim(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** 去掉行首的 su 包装：{@code su -c "x"} → {@code x}。 */
    private static String stripNestedSu(String line) {
        String t = line.trim();
        int sp = t.indexOf(' ');
        String head = sp < 0 ? t : t.substring(0, sp);
        String base = head.substring(head.lastIndexOf('/') + 1);
        if (!"su".equals(base)) {
            return t;
        }
        String rest = sp < 0 ? "" : t.substring(sp + 1).trim();
        if (rest.isEmpty()) {
            return "";
        }
        if (rest.startsWith("-c ") || rest.startsWith("-c\t")) {
            rest = rest.substring(2).trim();
        } else if (rest.startsWith("0 ") || rest.startsWith("root ")) {
            rest = rest.substring(rest.indexOf(' ') + 1).trim();
        }
        if (rest.length() >= 2) {
            char a = rest.charAt(0);
            char b = rest.charAt(rest.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                rest = rest.substring(1, rest.length() - 1);
            }
        }
        return rest;
    }

    // ── 虚拟 root shell ─────────────────────────────────────────────────

    /**
     * 无参 {@code su} 返回的假进程：模块往 stdin 写命令，这里逐行执行，
     * 输出进 stdout。/system/bin/sh 在容器里按宿主 UID 跑，读写 guest 数据
     * 目录没有问题（路径已经改写）。
     */
    private static final class VShell extends Process {

        private final Object lock = new Object();
        private final ByteQueue out = new ByteQueue();
        private final LineSink stdin = new LineSink(this);
        private final int id;

        private volatile boolean finished;
        private volatile int code;
        private volatile Process active;

        VShell(int id) {
            this.id = id;
        }

        void execLine(String line) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) {
                return;
            }
            // 模块拿到的是「root shell」，但下面真正执行的是普通 sh：
            // 行内再写 su 会找不到二进制，这里直接拆掉这层。
            t = stripNestedSu(t);
            if ("exit".equals(t) || "logout".equals(t)) {
                finish(0);
                return;
            }
            if (t.startsWith("exit ")) {
                int c = 0;
                try {
                    c = Integer.parseInt(t.substring(5).trim());
                } catch (Throwable ignored) {
                }
                finish(c);
                return;
            }
            try {
                if (isIdentity(t)) {
                    out.write(identityOutput(t).getBytes("UTF-8"));
                    Diag.log("root", "shell#" + id + " " + trim(t, 120) + " -> root");
                    return;
                }
                String cmdline = rewrite(t);
                Diag.log("root", "shell#" + id + " $ " + trim(cmdline, 300));
                Process p = new ProcessBuilder("/system/bin/sh", "-c", cmdline)
                        .redirectErrorStream(true)
                        .start();
                active = p;
                InputStream in = p.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                int rc = p.waitFor();
                active = null;
                Diag.log("root", "shell#" + id + " 退出码=" + rc);
            } catch (Throwable e) {
                Diag.log("root", "shell#" + id + " 执行失败 " + e);
                try {
                    out.write(("" + e.getMessage() + "\n").getBytes("UTF-8"));
                } catch (Throwable ignored) {
                }
            }
        }

        void finish(int c) {
            synchronized (lock) {
                if (finished) {
                    return;
                }
                code = c;
                finished = true;
                out.eof();
                lock.notifyAll();
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return out;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            synchronized (lock) {
                while (!finished) {
                    try {
                        lock.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // 模块经常写完命令就 waitFor，不写 exit：空闲一段时间就收尾，
                    // 否则这里会一直挂着，表现成「点了卡住」。
                    if (!finished && active == null && stdin.idleMs() > 700) {
                        finish(0);
                    }
                }
                return code;
            }
        }

        @Override
        public int exitValue() {
            synchronized (lock) {
                if (!finished) {
                    throw new IllegalThreadStateException("process hasn't exited");
                }
                return code;
            }
        }

        @Override
        public void destroy() {
            Process p = active;
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
            finish(0);
        }
    }

    /** 逐行切分 stdin，交给 VShell 执行。 */
    private static final class LineSink extends OutputStream {
        private final VShell shell;
        private final StringBuilder line = new StringBuilder();
        private volatile long last = SystemClock.uptimeMillis();

        LineSink(VShell shell) {
            this.shell = shell;
        }

        long idleMs() {
            return SystemClock.uptimeMillis() - last;
        }

        @Override
        public void write(int b) {
            last = SystemClock.uptimeMillis();
            char c = (char) (b & 0xff);
            if (c == '\n') {
                String s = line.toString();
                line.setLength(0);
                shell.execLine(s);
            } else if (c != '\r') {
                line.append(c);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; ++i) {
                write(b[off + i]);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            String s = line.toString();
            line.setLength(0);
            if (s.length() > 0) {
                shell.execLine(s);
            }
            shell.finish(0);
        }
    }

    /** 可增长、读端会阻塞等待的字节队列。 */
    private static final class ByteQueue extends InputStream {
        private byte[] buf = new byte[4096];
        private int r;
        private int w;
        private boolean eof;

        synchronized void write(byte[] b, int off, int len) {
            ensure(len);
            System.arraycopy(b, off, buf, w, len);
            w += len;
            notifyAll();
        }

        synchronized void write(byte[] b) {
            write(b, 0, b.length);
        }

        synchronized void write(int b) {
            ensure(1);
            buf[w++] = (byte) b;
            notifyAll();
        }

        synchronized void eof() {
            eof = true;
            notifyAll();
        }

        private void ensure(int extra) {
            if (w + extra <= buf.length) {
                return;
            }
            int size = Math.max(buf.length * 2, w + extra);
            // 上限 4MB，防止模块不读 stdout 时把内存吃光
            if (size > 4 * 1024 * 1024) {
                size = 4 * 1024 * 1024;
            }
            byte[] nb = new byte[size];
            int len = Math.min(w, size);
            System.arraycopy(buf, r, nb, 0, len - r);
            w = len - r;
            r = 0;
            buf = nb;
        }

        @Override
        public synchronized int read() {
            while (r >= w && !eof) {
                try {
                    wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (r >= w) {
                return -1;
            }
            return buf[r++] & 0xff;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            while (r >= w && !eof) {
                try {
                    wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (r >= w) {
                return -1;
            }
            int n = Math.min(len, w - r);
            System.arraycopy(buf, r, b, off, n);
            r += n;
            return n;
        }

        @Override
        public synchronized int available() {
            return w - r;
        }
    }

    /**
     * 把 su 脚本的真实子进程包一层：输出照样给模块读，同时抄一份进 diag。
     *
     * <p>之前只 {@code start()} 不看结果，模块的 payload 是「静默失败」还是
     * 「跑起来但没权限」完全区分不出来。现在至少能看到前几行输出和退出码。
     */
    private static final class TapProcess extends Process {
        private final int id;
        private final Process real;
        private final ByteQueue out = new ByteQueue();
        private final StringBuilder text = new StringBuilder();
        private volatile int code = -1;

        TapProcess(int id, Process real) {
            this.id = id;
            this.real = real;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump();
                }
            }, "hush-su-" + id);
            t.setDaemon(true);
            t.start();
        }

        private void pump() {
            int lines = 0;
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            try (InputStream in = real.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    for (int i = 0; i < n; ++i) {
                        byte b = buf[i];
                        if (b == '\n') {
                            byte[] raw = line.toByteArray();
                            String s = decodeLine(line);
                            line.reset();
                            out.write(raw);
                            out.write('\n');
                            if (lines < 12 && s.length() > 0) {
                                Diag.log("root", "su#" + id + " 输出> " + trim(s, 200));
                                ++lines;
                            }
                            if (text.length() < 2000) text.append(s).append('\n');
                        } else if (b != '\r') {
                            // 没有换行的长行（进度条、一次性大块输出）不能一直憋着，
                            // 超过 4KB 就先原样放行，避免读不到实时输出。
                            if (line.size() >= 4096) {
                                out.write(line.toByteArray());
                                line.reset();
                            }
                            line.write(b);
                        }
                    }
                }
                String tail = decodeLine(line);
                if (tail.length() > 0) {
                    out.write(line.toByteArray());
                    if (lines < 12) {
                        Diag.log("root", "su#" + id + " 输出> " + trim(tail, 200));
                    }
                    if (text.length() < 2000) text.append(tail);
                }
            } catch (Throwable t) {
                Diag.log("root", "su#" + id + " 读取输出失败 " + t);
            } finally {
                int rc = -1;
                try {
                    rc = real.waitFor();
                } catch (Throwable ignored) {
                }
                code = rc;
                out.eof();
                Diag.log("root", "su#" + id + " 退出码=" + rc
                        + (text.length() == 0 ? ""
                        : " 输出=" + trim(text.toString().replace('\n', ' '), 900)));
            }
        }

        /** main 的输出是 UTF-8；按字节收齐再解码，避免中文变成乱码。 */
        private String decodeLine(java.io.ByteArrayOutputStream b) {
            try {
                return new String(b.toByteArray(), "UTF-8").replace("\r", "");
            } catch (Throwable t) {
                return "";
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return real.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return out;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            return real.waitFor();
        }

        @Override
        public int exitValue() {
            return real.exitValue();
        }

        @Override
        public void destroy() {
            try {
                real.destroy();
            } catch (Throwable ignored) {
            }
            out.eof();
        }
    }

    /** 固定输出、固定退出码的假进程（用于 id / whoami 这类查询）。 */
    private static final class StaticProcess extends Process {
        private final byte[] data;
        private final int code;

        StaticProcess(String text, int code) {
            this.data = text == null ? new byte[0] : text.getBytes();
            this.code = code;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return code;
        }

        @Override
        public int exitValue() {
            return code;
        }

        @Override
        public void destroy() {
        }
    }
}
