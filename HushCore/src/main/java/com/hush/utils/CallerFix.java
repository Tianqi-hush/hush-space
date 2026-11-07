package com.hush.utils;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.hush.HushCore;

/**
 * 把「真正的调用方包名」写进系统启动事务，让 guest 里的
 * {@code Activity.getCallingPackage()} 返回 guest 包名而不是宿主包名。
 *
 * <p>容器里所有 guest 共用宿主进程身份，系统在启动 Activity 时只会把
 * launchedFromPackage 填成 {@code com.hush.space}。HMS Core 的 ForegroundBus
 * 会拿 {@code activity.getCallingPackage()} 跟请求头里的 cpPackageName 比对，
 * 不一致就直接返回 907135000（账号页弹「无法获取数据，请稍后再试」）。
 *
 * <p>历史实现只写 {@code Activity.mLaunchedFromPackage}，但在 Android 17 的
 * ROM 上这个字段已经不存在（反射抛 NoSuchFieldException），而且 Android 12+
 * 的 LaunchActivityItem 在 record 建立之前就执行了，改 record 也来不及。
 *
 * <p>所以这里改成三路兜底，全部失败也不影响启动：
 * <ol>
 *   <li>直接写 {@code LaunchActivityItem.mLaunchedFromPackage}（存在就写）；</li>
 *   <li>扫描事务 / ActivityInfo / Activity 里值为宿主包名的 String 字段，
 *       不依赖具体字段名 —— ROM 改名、换到 ActivityInfo 都能命中；</li>
 *   <li>onCreate 之前再写一次 Activity，并回读
 *       {@code getCallingPackage()} 确认是否生效（写日志供下次定位）。</li>
 * </ol>
 */
public final class CallerFix {

    private CallerFix() {
    }

    /** LaunchActivityItem 上可能承载调用方的字段名。 */
    private static final String[] ITEM_FIELDS = {
            "mLaunchedFromPackage", "launchedFromPackage",
    };

    /** Activity / ActivityInfo 上可能承载调用方的字段名。 */
    private static final String[] ACTIVITY_FIELDS = {
            "mLaunchedFromPackage", "mCallingPackage", "launchedFromPackage",
    };

    public static String callerOf(Intent intent) {
        if (intent == null) return null;
        try {
            String caller = intent.getStringExtra(
                    com.hush.proxy.record.ProxyActivityRecord.EXTRA_CALLER_PKG);
            return caller == null || caller.isEmpty() ? null : caller;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取 Activity 的 token。{@code getActivityToken()} 是 @hide，编译期看不到，
     * 运行期通过反射拿；拿不到再退到 {@code mToken} 字段。
     */
    public static android.os.IBinder tokenOf(android.app.Activity activity) {
        if (activity == null) return null;
        try {
            java.lang.reflect.Method m = android.app.Activity.class.getMethod("getActivityToken");
            Object v = m.invoke(activity);
            if (v instanceof android.os.IBinder) return (android.os.IBinder) v;
        } catch (Throwable ignored) {
        }
        for (Class<?> c = activity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("mToken");
                f.setAccessible(true);
                Object v = f.get(activity);
                if (v instanceof android.os.IBinder) return (android.os.IBinder) v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 在 LaunchActivityItem 真正 execute 之前写入调用方。
     *
     * @param item  LaunchActivityItem
     * @param caller 真实调用方包名
     * @param infos 需要一并修补的 ActivityInfo（原 info 和替换后的 stub info）
     */
    public static void applyToLaunchItem(Object item, String caller, Object... infos) {
        if (item == null || caller == null) return;
        boolean ok = false;
        ok |= setNamed(item, ITEM_FIELDS, caller, "item");
        ok |= setByHostValue(item, caller, "item");
        if (infos != null) {
            for (Object info : infos) {
                if (info == null) continue;
                ok |= setActivityInfo(info, caller);
            }
        }
        Diag.log("caller", "LaunchActivityItem 写入调用方 " + caller
                + " ok=" + ok + " item=" + item.getClass().getName());
    }

    /** 只修 ActivityInfo（低版本 record 路径用）。 */
    public static void applyToActivityInfo(Object info, String caller) {
        if (info == null || caller == null) return;
        boolean ok = setActivityInfo(info, caller);
        Diag.log("caller", "ActivityInfo 写入调用方 " + caller + " ok=" + ok);
    }

    private static boolean setActivityInfo(Object info, String caller) {
        boolean ok = setNamed(info, ACTIVITY_FIELDS, caller, "info");
        ok |= setByHostValue(info, caller, "info");
        return ok;
    }

    /** onCreate 之前的最后一道：直接写 Activity，并回读校验。 */
    public static void applyToActivity(Activity activity, String caller) {
        if (activity == null || caller == null) return;
        boolean ok = false;
        try {
            ok |= setNamed(activity, ACTIVITY_FIELDS, caller, "activity");
            ok |= setByHostValue(activity, caller, "activity");
            Object info = null;
            try {
                Field f = Activity.class.getDeclaredField("mActivityInfo");
                f.setAccessible(true);
                info = f.get(activity);
            } catch (Throwable ignored) {
            }
            if (info != null) {
                ok |= setActivityInfo(info, caller);
            }
        } catch (Throwable ignored) {
        }
        String got = null;
        try {
            got = activity.getCallingPackage();
        } catch (Throwable ignored) {
        }
        Diag.log("caller", "activity 写入调用方 " + caller + " ok=" + ok
                + " getCallingPackage=" + got
                + " act=" + activity.getClass().getName());
    }

    private static boolean setNamed(Object obj, String[] names, String caller, String where) {
        boolean changed = false;
        for (String name : names) {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() != String.class) break;
                    f.setAccessible(true);
                    f.set(obj, caller);
                    Diag.log("caller", where + "." + name + " = " + caller);
                    changed = true;
                    break;
                } catch (NoSuchFieldException ignored) {
                    // 继续往父类找
                } catch (Throwable ignored) {
                    break;
                }
            }
        }
        return changed;
    }

    /**
     * 不依赖字段名的兜底：框架把 launchedFromPackage 换名字 / 挪到别的对象时，
     * 值仍然是宿主包名，这里按「值等于宿主包名」反查并替换。
     *
     * <p>只扫 {@code android.*} 的类，避免误改 guest 业务对象里的同名字符串。
     */
    private static boolean setByHostValue(Object obj, String caller, String where) {
        String host = null;
        try {
            host = HushCore.getHostPkg();
        } catch (Throwable ignored) {
        }
        if (host == null || host.isEmpty()) return false;

        boolean changed = false;
        List<String> hits = new ArrayList<>(2);
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getName().startsWith("android.")) break;
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (host.equals(v)) {
                        f.set(obj, caller);
                        hits.add(c.getSimpleName() + "." + f.getName());
                        changed = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (changed) {
            Diag.log("caller", where + " 按值命中 " + hits + " -> " + caller);
        }
        return changed;
    }
}
