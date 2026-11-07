package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;

/**
 * Created by @officialakshit01 on 3/7/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class ActivityThread {
    public H mH = null;
    public AppBindData mBoundApplication;
    public Application mInitialApplication;
    public Instrumentation mInstrumentation;
    //
    // 这三个 Map 在 AOSP 的 ActivityThread 构造函数里是当场 new 出来的：
    //
    //     mActivities = new ArrayMap<>();
    //     mPackages   = new ArrayMap<>();
    //     mProviderMap = new ArrayMap<>();
    //
    // 这里只声明没初始化，于是壳（libunisec）在启动时读 mProviderMap 做
    // installContentProviders 那一步拿到的是 null：
    //
    //   GetObjectClass(null) -> null          （ART 抛 NPE 并返回 null）
    //   壳不检查异常 -> GetMethodID(env, null, "values", "()Ljava/util/Collection;")
    //   -> ART 在 null class 上走到 strlen(0xfffff9) -> SIGSEGV
    //
    // 1.6.17 的日志里 JNI-HIST#7 就是这一条（cls=0x0），崩溃栈的
    // #07 libHushCore.so+0x1d244 是我的 detour 在转发，证实参数确实是 null。
    // 补上初始化，壳遍历一个空 Map，这一步就正常跳过。
    //
    public Map<String, WeakReference<?>> mPackages = new ArrayMap<>();
    public Map<IBinder, ActivityClientRecord> mActivities = new ArrayMap<>();
    public ArrayMap<ProviderKey, Object> mProviderMap = new ArrayMap<>();

    static class H extends Handler {
    }

    public static ActivityThread currentActivityThread() {
        throw new RuntimeException();
    }

    public String getProcessName() {
        throw new RuntimeException();
    }

    public Handler getHandler() {
        throw new RuntimeException();
    }

    public ContentProviderHolder installProvider(Context context, ContentProviderHolder holder, ProviderInfo info,
            boolean noisy, boolean noReleaseNeeded, boolean stable) {
        throw new RuntimeException();
    }

    static final class AppBindData {
    }

    public static final class ActivityClientRecord {
        public Activity activity;
        public IBinder token;
        public ActivityInfo activityInfo;
        public Intent intent;
    }

    public static final class ProviderKey {
        public final String authority;
        public final int userId;

        public ProviderKey(String authority, int userId) {
            this.authority = authority;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof ProviderKey) {
                final ProviderKey other = (ProviderKey) o;
                return Objects.equals(authority, other.authority) && userId == other.userId;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return ((authority != null) ? authority.hashCode() : 0) ^ userId;
        }
    }
}
