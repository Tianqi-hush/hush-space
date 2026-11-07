package com.hush.activation;

import android.Meta.IRemoteManager;
import android.content.Context;
import com.hush.HushCore;
import com.hush.core.env.BEnvironment;
import org.lsposed.lsparanoid.Obfuscate;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SdkBridge — AIDL stub implementation.
 * Thin Java wrapper over SdkCore. No logic here.
 */
@Obfuscate
public final class SdkBridge extends IRemoteManager.Stub {

    public static final File JUNIT_JAR = new File(BEnvironment.getCacheDir(), "junit.apk");
    public static final File EMPTY_JAR = new File(BEnvironment.getCacheDir(), "empty.apk");

    public static volatile boolean sEnableDaemonService = true;
    /**
     * 默认关闭。hideRoot 会把 /system/app、/system/bin/su 等一批只读路径
     * 塞进 IO 规则表，addRedirect 时会对这些路径做 mkdirs，
     * 在不可写分区上既拿不到目录又拖慢启动，收益远小于风险。
     */
    public static volatile boolean sHideRoot            = false;
    public static volatile boolean sHideXposed          = true;
    /**
     * /proc 伪文件重定向开关。
     * 默认关闭：cmdline / status 静态快照会让 Unity、IL2CPP 等原生引擎
     * 读到过期内容（如 TracerPid、进程名），表现为启动即闪退。
     * 只有在明确需要对抗 /proc 检测的普通 App 上才打开。
     */
    public static volatile boolean sEnableFakeProc      = false;

    private static volatile SdkBridge sInstance;
    private static final ExecutorService sExec = Executors.newSingleThreadExecutor();

    public static SdkBridge getInstance() {
        if (sInstance == null) synchronized (SdkBridge.class) {
            if (sInstance == null) sInstance = new SdkBridge();
        }
        return sInstance;
    }

    @Override public void activateSdk(final String k) { SdkCore.activateSdk(k); }
    @Override public boolean getActivatedSdk()          { return SdkCore.gateCheck(); }
    @Override public String  getServerMessage()         { return SdkCore.getMsg(); }
    @Override public boolean getNetwork()               { return SdkCore.gateCheck(); }
}
