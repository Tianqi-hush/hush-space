package com.hush.core.system;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.IInterface;
import android.os.Process;

import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.FileNotFoundException;
import java.util.Arrays;

import com.hush.core.IBActivityThread;
import com.hush.entity.AppConfig;
import com.hush.proxy.ProxyManifest;
import com.hush.utils.Diag;

public class ProcessRecord extends Binder {
    public final ApplicationInfo info;
    final public String processName;
    public IBActivityThread bActivityThread;
    public IInterface appThread;
    public int uid;
    public int pid;
    public int buid;
    public int bpid;
    public int callingBUid;
    public int userId;

    public ConditionVariable initLock = new ConditionVariable();

    public ProcessRecord(ApplicationInfo info, String processName) {
        this.info = info;
        this.processName = processName;
    }

    public int getCallingBUid() {
        return callingBUid;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{processName, pid, buid, bpid, uid, pid, userId});
    }

    public String getProviderAuthority() {
        return ProxyManifest.getProxyAuthorities(bpid);
    }

    public AppConfig getClientConfig() {
        AppConfig config = new AppConfig();
        config.packageName = info.packageName;
        config.processName = processName;
        config.bpid = bpid;
        config.buid = buid;
        config.uid = uid;
        config.callingBUid = callingBUid;
        config.userId = userId;
        config.token = this;
        return config;
    }
    
    public void kill() {
        if (pid > 0) {
            // 2.6.52：只杀真正的 guest 进程（cmdline = 宿主包名 + ":pN"）。
            // 进程表里的 pid 可能是过期的（进程早退了，pid 被系统回收给别的进程，
            // 包括宿主自己），直接 killProcess 会把「hush」整个关掉——用户看到的
            // 就是「装完 HMS 软件自己闪退一次，得重新打开」。
            String cmd = cmdline(pid);
            String host = com.hush.HushCore.getHostPkg();
            if (cmd == null || !cmd.startsWith(host + ":")) {
                Diag.log("kill", "跳过 pid=" + pid + " cmd=" + cmd + " proc=" + processName);
                return;
            }
            Diag.log("kill", "结束 pid=" + pid + " cmd=" + cmd + " proc=" + processName);
            try {
                Process.killProcess(pid);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    /** 读 /proc/<pid>/cmdline，失败返回 null。 */
    private static String cmdline(int pid) {
        // 2.6.56：不能用 FileInputStream —— 容器开了 IO 重定向之后，Java 的
        // File API 会被改写到 guest 沙盒，/proc/... 直接读不到（日志里
        // cmd=null 就是这么来的，导致该杀的 HMS 进程一个都没杀）。
        // 走 android.system.Os 的 raw syscall，不经过任何 hook。
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open("/proc/" + pid + "/cmdline",
                    android.system.OsConstants.O_RDONLY, 0);
            byte[] buf = new byte[256];
            int n = android.system.Os.read(fd, buf, 0, buf.length);
            if (n <= 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xff);
                if (c == 0) break;
                sb.append(c);
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (fd != null) android.system.Os.close(fd);
            } catch (Throwable ignored) {
            }
        }
    }
    
    private void handleErrno(ErrnoException e) {
        if (e.errno == OsConstants.ENOENT) {
        } else {
            e.printStackTrace();
        }
    }

    public String getPackageName() {
        return info.packageName;
    }
}
