package com.hush.proxy.record;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;

import com.hush.utils.compat.BundleCompat;

/**
 * Created by @jagdish_vip on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProxyActivityRecord {

    /**
     * guest 进程里 {@code startActivity} 时记下的「真正调用方」虚拟包名。
     *
     * <p>写进 target intent，等目标 Activity 在容器里创建时再取出来覆盖
     * {@code ActivityClientRecord.launchedFromPackage}。
     */
    public static final String EXTRA_CALLER_PKG = "_G_|_caller_pkg_";

    public int mUserId;
    public ActivityInfo mActivityInfo;
    public Intent mTarget;
    public IBinder mActivityRecord;

    public ProxyActivityRecord(int userId, ActivityInfo activityInfo, Intent target, IBinder activityRecord) {
        mUserId = userId;
        mActivityInfo = activityInfo;
        mTarget = target;
        mActivityRecord = activityRecord;
    }

    public static void saveStub(Intent shadow, Intent target, ActivityInfo activityInfo, IBinder activityRecord, int userId) {
        shadow.putExtra("_G_|_user_id_", userId);
        shadow.putExtra("_G_|_activity_info_", activityInfo);
        shadow.putExtra("_G_|_target_", target);
        BundleCompat.putBinder(shadow, "_G_|_activity_record_v_", activityRecord);
    }

    public static ProxyActivityRecord create(Intent intent) {
        int userId = intent.getIntExtra("_G_|_user_id_", 0);
        ActivityInfo activityInfo = intent.getParcelableExtra("_G_|_activity_info_");
        Intent target = intent.getParcelableExtra("_G_|_target_");
        IBinder activityRecord = BundleCompat.getBinder(intent, "_G_|_activity_record_v_");
        return new ProxyActivityRecord(userId, activityInfo, target, activityRecord);
    }
}
