package com.hush.core.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hush.HushCore;
import com.hush.core.IBActivityThread;
import com.hush.core.env.BEnvironment;
import com.hush.core.system.notification.BNotificationManagerService;
import com.hush.core.system.pm.BPackageManagerService;
import com.hush.core.system.user.BUserHandle;
import com.hush.entity.AppConfig;
import com.hush.fake.hook.ClassInvocationStub;
import com.hush.proxy.ProxyManifest;
import com.hush.utils.FileUtils;
import com.hush.utils.PermissionUtils;
import com.hush.utils.Slog;
import com.hush.utils.compat.ApplicationThreadCompat;
import com.hush.utils.compat.BuildCompat;
import com.hush.utils.compat.BundleCompat;
import com.hush.utils.provider.ProviderCall;
import com.hush.core.system.api.MetaActivationManager;

/**
 * Created by @officialakshit01 on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class BProcessManagerService implements ISystemService {
    public static final String TAG = "BProcessManager";

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid,
            int callingPid) {
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null)
            return null;
        ProcessRecord app;
        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);

            if (bProcess == null) {
                bProcess = new HashMap<>();
            }
            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    if (app.initLock != null) {
                        app.initLock.block();
                    }
                    if (app.bActivityThread != null) {
                        return app;
                    }
                }
                bpid = getUsingBPidL();
                Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
            }
            if (bpid == -1) {
                throw new RuntimeException("No processes available");
            }
            app = new ProcessRecord(info, processName);
            app.uid = Process.myUid();
            app.bpid = bpid;
            app.buid = BPackageManagerService.get().getAppId(packageName);
            app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
            app.userId = userId;

            bProcess.put(processName, app);
            mPidsSelfLocked.add(app);

            mProcessMap.put(buid, bProcess);
            if (!initAppProcessL(app)) {
                // init process fail
                bProcess.remove(processName);
                mPidsSelfLocked.remove(app);
                app = null;
            } else {
                app.pid = getPid(HushCore.getContext(), ProxyManifest.getProcessName(app.bpid));
            }
        }
        return app;
    }

    // 20240801 add request permission add start 0
    private void requestPermissionIfNeed(ProcessRecord app) {
        if (PermissionUtils.isCheckPermissionRequired(app.info)) {
            String[] permissions = BPackageManagerService.get().getDangerousPermissions(app.info.packageName);
            new Thread(() -> {
                if (!PermissionUtils.checkPermissions(permissions)) {
                    ConditionVariable permissionLock = new ConditionVariable();
                    startRequestPermission(permissions, permissionLock);
                    permissionLock.block();
                }
            }).start();
        }
    }

    private void startRequestPermission(String[] permissions, final ConditionVariable permissionLock) {
        if (permissions == null || permissions.length == 0) {
            if (permissionLock != null) {
                permissionLock.open();
            }
            return;
        }
        if (HushCore.getContext() == null || permissionLock == null) {
            return;
        }
        PermissionUtils.startRequestPermissions(HushCore.getContext(), permissions, new PermissionUtils.CallBack() {
            @Override
            public boolean onResult(int requestCode, String[] permissions, int[] grantResults) {
                try {
                    return PermissionUtils.isRequestGranted(grantResults);
                } finally {
                    permissionLock.open();
                }
            }
        });
    }

    // 20240801 add request permission add end 0

    private int getUsingBPidL() {
        ActivityManager manager = (ActivityManager) HushCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
            int i = parseBPid(runningAppProcess.processName);
            usingPs.add(i);
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (usingPs.contains(i)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            ProcessRecord app;
            synchronized (mProcessLock) {
                app = findProcessByPid(callingPid);
            }
            if (app == null) {
                String stubProcessName = getProcessName(HushCore.getContext(), callingPid);
                int bpid = parseBPid(stubProcessName);
                startProcessLocked(packageName, processName, userId, bpid, callingPid);
            }
        }
    }

    private int parseBPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else {
            prefix = HushCore.getHostPkg() + ":p";
        }
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return -1;
    }

    // 这里初始化了userinfo
    private boolean initAppProcessL(ProcessRecord record) {
        Log.d(TAG, "initProcess: " + record.processName);
        requestPermissionIfNeed(record);
        AppConfig appConfig = record.getClientConfig();
        Bundle bundle = new Bundle();
        bundle.putParcelable(AppConfig.KEY, appConfig);
        // 🔥 CRASH FIX: Line 209
        Bundle result;
        try {
            result = ProviderCall.callSafely(record.getProviderAuthority(), "_Black_|_init_process_", null,
                    bundle);
        } catch (Exception e) {
            Log.e(TAG, "Provider error: " + e.getMessage());
            result = new Bundle();
        }
        IBinder appThread = BundleCompat.getBinder(result, "_Black_|_client_");
        if (appThread == null || !appThread.isBinderAlive()) {
            return false;
        }
        attachClientL(record, appThread);
        createProc(record);
        return true;
    }

    private void attachClientL(final ProcessRecord app, final IBinder appThread) {
        IBActivityThread activityThread = IBActivityThread.Stub.asInterface(appThread);
        if (activityThread == null) {
            app.kill();
            return;
        }
        try {
            appThread.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.d(TAG, "App Died: " + app.processName);
                    appThread.unlinkToDeath(this, 0);
                    onProcessDie(app);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.bActivityThread = activityThread;
        try {
            app.appThread = ApplicationThreadCompat.asInterface(activityThread.getActivityThread());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.initLock.open();
    }

    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            record.kill();
            Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
            if (process != null) {
                process.remove(record.processName);
                if (process.isEmpty()) {
                    mProcessMap.remove(record.buid);
                }
            }
            mPidsSelfLocked.remove(record);
            removeProc(record);
            BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
        }
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int appId = BPackageManagerService.get().getAppId(packageName);
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null)
                return null;
            return processRecordMap.get(processName);
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) {
                List<ProcessRecord> tmp = new ArrayList<>(mPidsSelfLocked);
                int appId = BPackageManagerService.get().getAppId(packageName);
                for (ProcessRecord processRecord : mPidsSelfLocked) {
                    int appId1 = BUserHandle.getAppId(processRecord.buid);
                    if (appId == appId1) {
                        mProcessMap.remove(processRecord.buid);
                        tmp.remove(processRecord);
                        processRecord.kill();
                    }
                }
                mPidsSelfLocked.clear();
                mPidsSelfLocked.addAll(tmp);
            }
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return;
            for (ProcessRecord value : process.values()) {
                if (value == null) continue;
                // 2.6.49：绝不踢宿主自己的进程（安装 HMS 时会批量 kill guest 进程）
                if (value.pid == Process.myPid()) continue;
                if (value.processName != null && value.processName.startsWith("com.hush.space")) continue;
                value.kill();
                mPidsSelfLocked.remove(value);
            }
            mProcessMap.remove(buid);
        }
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return new ArrayList<>();
            return new ArrayList<>(process.values());
        }
    }

    /**
     * 同一空间（userId）内所有活着的 guest 进程，不区分包名。
     *
     * <p>给 {@code ActivityManager.getRunningAppProcesses()} 用：容器里一个空间可以装多个
     * App，辅助类 App 靠这个接口判断「目标游戏在不在跑」，只回调用方自己等于永远判未启动。
     */
    public List<ProcessRecord> getProcessesAsUser(int userId) {
        List<ProcessRecord> out = new ArrayList<>();
        synchronized (mProcessLock) {
            for (Map.Entry<Integer, Map<String, ProcessRecord>> entry : mProcessMap.entrySet()) {
                Integer buid = entry.getKey();
                if (buid == null || BUserHandle.getUserId(buid) != userId) {
                    continue;
                }
                Map<String, ProcessRecord> process = entry.getValue();
                if (process != null) {
                    out.addAll(process.values());
                }
            }
        }
        return out;
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        synchronized (mProcessLock) {
            ProcessRecord callingProcess = BProcessManagerService.get().findProcessByPid(pid);
            if (callingProcess == null) {
                return BPackageManagerService.get().getAppId(packageName);
            }
            return BUserHandle.getAppId(callingProcess.buid);
        }
    }

    public int getUserIdByCallingPid(int callingPid) {
        synchronized (mProcessLock) {
            ProcessRecord callingProcess = BProcessManagerService.get().findProcessByPid(callingPid);
            if (callingProcess == null) {
                return 0;
            }
            return callingProcess.userId;
        }
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private static String getProcessName(Context context, int pid) {
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                if (runningAppProcess.processName.equals(processName)) {
                    return runningAppProcess.pid;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return -1;
    }

    private static void createProc(ProcessRecord record) {
        File cmdline = new File(BEnvironment.getProcDir(record.bpid), "cmdline");
        try {
            FileUtils.writeToFile(record.processName.getBytes(), cmdline);
        } catch (IOException ignored) {
        }
    }

    private static void removeProc(ProcessRecord record) {
        FileUtils.deleteDir(BEnvironment.getProcDir(record.bpid));
    }

    public List<String> getRunningPackages() {
        List<String> list = new ArrayList<>();
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord record : mPidsSelfLocked) {
                if (record != null && record.getPackageName() != null && !list.contains(record.getPackageName())) {
                    list.add(record.getPackageName());
                }
            }
        }
        return list;
    }

    @Override
    public void systemReady() {
        FileUtils.deleteDir(BEnvironment.getProcDir());
    }

    /**
     * 清理孤儿虚拟进程。
     *
     * <p>宿主进程被系统回收后，{@code :pN} 里的 guest 进程不一定跟着死 —— 它的
     * Activity 还在栈上时能自己活着，而宿主重启后 {@link #mPidsSelfLocked} 是空的，
     * 完全认不出它。这类进程会一直攥着 guest 的 WebView 数据目录锁
     * （Chromium 的 {@code AwDataDirLock}），等用户再点启动，容器新拉起的进程在
     * 初始化 WebView 时就会抛
     * {@code Using WebView from more than one process at once} 而秒崩。
     *
     * <p>所以每次启动 App 之前对一遍账：活着的 {@code :pN} 进程里，凡是不在
     * 进程表里的，一律结束掉。进程表里有的（真的在跑的 guest）不动。
     *
     * @return 结束掉的进程数
     */
    public int reapOrphanProcesses() {
        int killed = 0;
        try {
            String prefix = HushCore.getHostPkg() + ":p";
            int self = Process.myPid();

            // 进程表里的名字：:p0 / :p1 …，与实际活着的进程按名字对账，
            // 不依赖 ProcessRecord.pid 是否解析成功。
            Set<String> known = new HashSet<>();
            synchronized (mPidsSelfLocked) {
                for (ProcessRecord record : mPidsSelfLocked) {
                    if (record != null && record.bpid >= 0) {
                        known.add(prefix + record.bpid);
                    }
                }
            }

            Map<Integer, String> alive = new HashMap<>();

            // 主通道：Android 8 起该接口只返回调用方自己的进程，正好覆盖多开进程。
            try {
                ActivityManager am = (ActivityManager) HushCore.getContext()
                        .getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> list =
                        am == null ? null : am.getRunningAppProcesses();
                if (list != null) {
                    for (ActivityManager.RunningAppProcessInfo info : list) {
                        if (info == null || info.pid == self || info.processName == null) {
                            continue;
                        }
                        if (info.processName.startsWith(prefix)) {
                            alive.put(info.pid, info.processName);
                        }
                    }
                }
            } catch (Throwable t) {
                Slog.w(TAG, "reap: getRunningAppProcesses failed " + t);
            }

            // 兜底：部分 ROM 上接口只回主进程，直接读 /proc 找同 UID 的 :pN。
            try {
                File[] files = new File("/proc").listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                            continue;
                        }
                        int pid;
                        try {
                            pid = Integer.parseInt(name);
                        } catch (NumberFormatException e) {
                            continue;
                        }
                        if (pid == self || alive.containsKey(pid)) {
                            continue;
                        }
                        String cmd = readCmdline(pid);
                        if (cmd != null && cmd.startsWith(prefix)) {
                            alive.put(pid, cmd);
                        }
                    }
                }
            } catch (Throwable t) {
                Slog.w(TAG, "reap: proc scan failed " + t);
            }

            for (Map.Entry<Integer, String> entry : alive.entrySet()) {
                if (known.contains(entry.getValue())) {
                    continue;
                }
                Slog.d(TAG, "reap orphan " + entry.getValue() + " pid=" + entry.getKey());
                try {
                    Process.killProcess(entry.getKey());
                    killed++;
                } catch (Throwable t) {
                    Slog.w(TAG, "reap kill " + entry.getKey() + " failed " + t);
                }
            }
            if (killed > 0) {
                // 留一点时间让内核收走 fd —— WebView 的 data 目录锁就挂在 fd 上。
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "reap failed " + t);
        }
        return killed;
    }

    private static String readCmdline(int pid) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid + "/cmdline"));
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                sb.append(c == 0 ? ' ' : (char) c);
            }
            String cmd = sb.toString().trim();
            int sp = cmd.indexOf(' ');
            return sp < 0 ? cmd : cmd.substring(0, sp);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

}
