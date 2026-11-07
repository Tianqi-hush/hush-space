package com.hush.space;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.hush.HushCore;
import com.hush.app.configuration.AppLifecycleCallback;
import com.hush.core.HostApp;
import com.hush.space.core.Diag;
import com.hush.space.core.LocaleKit;
import com.hush.space.core.SpoofManager;
import com.hush.space.core.Prefs;

/**
 * 宿主 Application：把 HushCore 虚拟运行环境挂到当前进程上。
 * attachBaseContext 负责初始化引擎上下文，onCreate 负责启动引擎服务。
 */
public class SpaceApplication extends Application {

    private static final String TAG = "SpaceApplication";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Prefs.init(base);
        Diag.init(base);
        try {
            HushCore.get().doAttachBaseContext(base, new HostApp(base));
            Diag.log("engine", "attachBaseContext ok");
        } catch (Throwable t) {
            Log.e(TAG, "attach engine failed", t);
            Diag.log("engine", "attachBaseContext FAILED\n" + Diag.stack(t));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme();
        seedGlobalSettings();
        try {
            com.hush.entity.SpaceSettings global = HushCore.get().getGlobalSpaceSettings();
            HushCore.setHideRoot(Boolean.TRUE.equals(global.hideRoot));
            HushCore.setHideXposed(!Boolean.FALSE.equals(global.hideXposed));
            HushCore.setEnableDaemonService(!Boolean.FALSE.equals(global.daemon));
            // 默认关闭 /proc 静态快照；Unity / IL2CPP 引擎对它非常敏感
            HushCore.setFakeProc(Prefs.fakeProc());
            Diag.log("config", "hideRoot=" + global.hideRoot
                    + " hideXposed=" + global.hideXposed
                    + " daemon=" + global.daemon
                    + " fakeProc=" + Prefs.fakeProc());
        } catch (Throwable t) {
            Log.e(TAG, "apply config failed", t);
            Diag.log("config", "apply FAILED\n" + Diag.stack(t));
        }
        try {
            HushCore.get().doCreate();
            Diag.log("engine", "doCreate ok");
        } catch (Throwable t) {
            Log.e(TAG, "create engine failed", t);
            Diag.log("engine", "doCreate FAILED\n" + Diag.stack(t));
        }
        registerSpoofCallback();
        // 2.6.57：上次装 HMS 如果被系统硬杀在中途，这里把「死在哪一步」和
        // 现场日志自动落到 Download/HushSpace/export/，免得用户还要手动导出。
        try {
            String proc = com.hush.utils.Diag.processName();
            if (proc == null || getPackageName().equals(proc)) {
                com.hush.core.HmsCore.reportLastInstall();
            }
        } catch (Throwable ignored) {
        }
        ensureHmsKitsAsync();
        // 2.6.39：宿主自己的联网取证。手机到底有没有网，先由这一份说了算；
        // 容器里那份（netprobe: guest:…）再不通，就是容器的问题。
        try {
            com.hush.utils.NetProbe.probe(this, "host");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 后台给已经装了 HMS Core 的空间补装账号 kit。
     *
     * <p>2.6.11 及以前只装了 {@code com.huawei.hwid}，账号界面所在的
     * {@code com.huawei.hms.account} 靠 HMS 自己用 PackageInstaller 现场装，
     * 容器里没有安装权限，结果就是「登录页一直加载」。升级到 2.6.12 后
     * 必须在用户点开华为账号之前补上，否则仍会复现。
     *
     * <p>放后台线程：9.5MB 的包解析 + 复制要一两秒，不能卡启动。
     */
    private void ensureHmsKitsAsync() {
        try {
            // 只有宿主主进程需要补装。SpaceApplication 在每个 com.hush.space:pN
            // 进程里都会 onCreate，以前 8 个进程各跑一遍：既重复解析 9.5MB 的包，
            // 又让「kit 缺失就重启 HMS」这条逻辑在任意时刻被触发，正好撞上
            // 用户点开华为账号的窗口，界面一秒就没了。
            // 进程名读不到（"?"）时保持旧行为，宁可多跑一次也别漏装。
            String proc = com.hush.utils.Diag.processName();
            if (proc != null && proc.length() > 0 && !"?".equals(proc)
                    && !getPackageName().equals(proc)) {
                return;
            }
            Thread t = new Thread(() -> {
                try {
                    // 让启动先跑完：9.5MB 的解压 + 安装会抢 IO，
                    // 用户这时候往往正等着进游戏。
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    for (com.hush.core.system.user.BUserInfo u : HushCore.get().getUsers()) {
                        if (u == null) continue;
                        try {
                            if (!HushCore.get().isInstallHms(u.id)) continue;
                            boolean ok = HushCore.get().ensureHmsKits(u.id);
                            Diag.log("hms", "启动补装账号 kit uid=" + u.id + " -> " + ok);
                        } catch (Throwable e) {
                            Diag.log("hms", "补装账号 kit 失败 uid=" + u.id + " " + e);
                        }
                    }
                } catch (Throwable e) {
                    Diag.log("hms", "启动补装账号 kit 失败 " + e);
                }
            }, "hms-kit");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 老版本只有 hush_space.xml 里的三个开关，没有 space-global.json。
     * 只在配置文件完全不存在时迁一次，绝不覆盖用户已经保存过的配置。
     */
    private void seedGlobalSettings() {
        try {
            // 宿主主进程不走 BEnvironment.load()（那是 guest 进程的路径），
            // 所以这里要自己触发一次数据根确定 + 镜像对齐，
            // 否则「外部目录这次挂载慢了点」就会让设置页读到另一份空配置。
            com.hush.core.env.SpaceSettingsStore.syncMirrors();
            com.hush.core.env.SpaceSettingsStore.seedFromLegacyIfNeeded();
        } catch (Throwable t) {
            Diag.log("config", "迁移全局环境设置失败 " + t);
        }
    }

    /** 在每个虚拟进程创建目标 Application 之前套用设备伪装、区域语言与时区。 */
    private void registerSpoofCallback() {
        try {
            HushCore.get().addAppLifecycleCallback(new AppLifecycleCallback() {
                @Override
                public void beforeCreateApplication(String packageName, String processName,
                                                    Context context, int userId) {
                    Diag.log("guest", "beforeCreateApplication pkg=" + packageName
                            + " proc=" + processName + " userId=" + userId);
                    try {
                        com.hush.entity.SpaceSettings s =
                                HushCore.get().resolveSpaceSettings(userId);
                        // 环境开关按空间生效：每个 guest 进程独立设置，互不影响。
                        HushCore.setHideRoot(Boolean.TRUE.equals(s.hideRoot));
                        HushCore.setHideXposed(!Boolean.FALSE.equals(s.hideXposed));
                        LocaleKit.apply(context, s);
                        Diag.log("guest", "空间配置 userId=" + userId
                                + " hideRoot=" + s.hideRoot
                                + " hideXposed=" + s.hideXposed
                                + " lang=" + s.language + " region=" + s.region
                                + " tz=" + s.timezone);
                    } catch (Throwable t) {
                        Diag.log("guest", "空间配置套用失败\n" + Diag.stack(t));
                    }
                    try {
                        SpoofManager.apply(context, userId);
                    } catch (Throwable t) {
                        Diag.log("guest", "spoof FAILED\n" + Diag.stack(t));
                    }
                }

                @Override
                public void beforeApplicationOnCreate(String packageName, String processName,
                                                      android.app.Application app, int userId) {
                    // makeApplication 之后、Application.onCreate 之前再套一次。
                    // beforeCreateApplication 阶段拿到的是 packageContext，
                    // 改它的 Resources 不一定命中 LoadedApk 里那份；等
                    // Application 出来后再刷一遍，App 自己 getResources()
                    // 才会真正拿到目标语言/时区。
                    try {
                        com.hush.entity.SpaceSettings s =
                                HushCore.get().resolveSpaceSettings(userId);
                        LocaleKit.apply(app, s);
                        Diag.log("guest", "语言资源二次套用 userId=" + userId
                                + " lang=" + s.language + " region=" + s.region);
                    } catch (Throwable t) {
                        Diag.log("guest", "语言资源二次套用失败 " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "register spoof callback failed", t);
            Diag.log("guest", "register callback FAILED\n" + Diag.stack(t));
        }
    }

    private void applyTheme() {
        int mode;
        switch (Prefs.theme()) {
            case Prefs.THEME_LIGHT:
                mode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case Prefs.THEME_DARK:
                mode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
