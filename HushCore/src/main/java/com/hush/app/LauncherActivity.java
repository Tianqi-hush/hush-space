package com.hush.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import androidx.annotation.Nullable;

import com.hush.HushCore;
import com.hush.utils.Slog;

import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class LauncherActivity extends Activity {
    
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";

    public static void launch(Intent intent, int userId) {
        Intent splash = new Intent();
        splash.setClass(HushCore.getContext(), LauncherActivity.class);
        splash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        splash.putExtra(KEY_INTENT, intent);
        splash.putExtra(KEY_PKG, intent.getPackage());
        splash.putExtra(KEY_USER_ID, userId);
        HushCore.getContext().startActivity(splash);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            com.hush.utils.Diag.init(getApplicationContext());
            com.hush.utils.Diag.log("launcher", "onCreate intent=" + getIntent());
        } catch (Throwable ignored) {
        }

        // Do not call setContentView. This keeps the Activity UI-less.

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        Intent launchIntent = intent.getParcelableExtra(KEY_INTENT);
        String packageName = intent.getStringExtra(KEY_PKG);
        int userId = intent.getIntExtra(KEY_USER_ID, 0);

        try {
            com.hush.utils.Diag.log("launcher", "pkg=" + packageName + " userId=" + userId
                    + " launchIntent=" + launchIntent);
            PackageInfo packageInfo = HushCore.getBPackageManager().getPackageInfo(packageName, 0, userId);

            if (packageInfo == null) {
                Slog.e(TAG, packageName + " not installed!");
                com.hush.utils.Diag.log("launcher", "NOT INSTALLED " + packageName);
                finish();
                return;
            }

            // Directly launch the app
            new Thread(() -> {
                try {
                    // 点的是 HMS Core / 账号 / 应用市场这类依赖华为服务的包时，
                    // 先把 kit 补装跑完再拉起。七个基础框架 kit（runtime /
                    // kitstore / ui / common / manager / privacy / webapp）是
                    // 2.6.24 才引入的，解压 + 安装要十几秒；如果让用户先点开
                    // 账号页、后台再慢慢装，跳板页的类加载不到，界面照样一秒
                    // 退回桌面。这里同步等一下，宁可第一次多等几秒。
                    if (com.hush.core.HmsCore.isHmsPackage(packageName)
                            || "com.huawei.appmarket".equals(packageName)) {
                        boolean ready = com.hush.core.HmsCore.ensureKits(userId);
                        com.hush.utils.Diag.log("launcher", "hms kits ready=" + ready);
                    }
                    HushCore.getBActivityManager().startActivity(launchIntent, userId);
                    com.hush.utils.Diag.log("launcher", "startActivity dispatched");
                } catch (Throwable t) {
                    com.hush.utils.Diag.log("launcher", "startActivity FAILED\n" + com.hush.utils.Diag.stack(t));
                }
            }).start();
        } catch (Throwable t) {
            com.hush.utils.Diag.log("launcher", "onCreate FAILED\n" + com.hush.utils.Diag.stack(t));
        }

        // Instantly finish this activity so it never hangs on the screen
        finish();
    }
}
