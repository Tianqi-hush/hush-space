package com.hush.core.hook;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Parcel;

import com.hush.HushCore;
import com.hush.core.VNative;
import com.hush.utils.Diag;

import java.lang.reflect.Method;

/**
 * 签名兼容层（对齐拾光的 SignCompat）。
 *
 * 为什么容器里必须单独做这一层：
 *
 * 容器自己的 PMS（{@code PackageManagerCompat}）确实会把 guest 的原版签名返回给
 * 「走容器 PMS 的调用」。但加固壳不这么查——它在 native 里直接
 * {@code CallObjectMethod} 打宿主的 {@code ApplicationPackageManager.getPackageInfo}。
 * 那时调用者 uid 是宿主，返回的证书也是宿主的，壳一比就不一致，随后走进
 * 「签名不符」的错误分支。之前那个 {@code strlen(0xfffff9)} 就是这条分支的产物。
 *
 * 这一层的作用很窄：只把查询结果里的签名对象换回 guest 的，其余字段一概不动。
 *
 * 两个必须守住的纪律：
 *
 * 1. 签名必须在装 hook 之前取好（{@link #prime}）。否则 hook 装完再去查，
 *    查询本身又会被 hook 拦住，直接递归。
 * 2. 返回的 PackageInfo 必须先深拷贝再改。Binder 反序列化出来的对象虽然通常是
 *    新的，但容器 PMS 有缓存路径，直接改原对象会污染其他调用者。
 */
public final class SignatureCompat {

    /** 重入闸门。hook 内部任何一次 getPackageInfo 都不允许再次进入替换逻辑。 */
    private static final ThreadLocal<Boolean> sBusy = new ThreadLocal<>();

    private static volatile String sGuestPkg;
    private static volatile String sHostPkg;
    private static volatile Signature[] sGuestSigs;
    private static volatile SigningInfo sGuestSigningInfo;
    /** prime 时缓存的 guest 完整 PackageInfo。backup 失效时用它顶上，保证不返回 null。 */
    private static volatile PackageInfo sGuestPi;
    private static volatile boolean sPrimed;

    private SignatureCompat() {
    }

    /**
     * 取 guest 的原版签名并缓存。**必须在 install() 之前调用。**
     *
     * @param guestPkg guest 包名，例如 com.netease.sky
     * @param userId   guest 用户 id
     */
    public static boolean prime(String guestPkg, int userId) {
        if (sPrimed) return true;
        if (guestPkg == null || guestPkg.isEmpty()) return false;

        int flags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        }

        try {
            PackageInfo pi = HushCore.getBPackageManager().getPackageInfo(guestPkg, flags, userId);
            if (pi == null) {
                Diag.log("sign", "prime miss: container PMS returned null for " + guestPkg);
                return false;
            }

            Signature[] sigs = pi.signatures;
            SigningInfo si = null;
            if (Build.VERSION.SDK_INT >= 28) {
                si = pi.signingInfo;
                if (sigs == null && si != null) {
                    sigs = si.getApkContentsSigners();
                }
            }

            if (sigs == null || sigs.length == 0) {
                Diag.log("sign", "prime miss: no signature on " + guestPkg);
                return false;
            }

            sGuestPkg = guestPkg;
            sGuestSigs = sigs;
            sGuestSigningInfo = si;
            sGuestPi = cloneViaParcel(pi);
            try {
                sHostPkg = HushCore.getContext().getPackageName();
            } catch (Throwable ignored) {
            }
            sPrimed = true;

            Diag.log("sign", "prime ok pkg=" + guestPkg
                    + " sigs=" + sigs.length
                    + " first=" + shortHash(sigs[0])
                    + (si != null ? " signingInfo=yes" : " signingInfo=no"));
            return true;
        } catch (Throwable t) {
            Diag.log("sign", "prime failed " + t);
            return false;
        }
    }

    /** 装 hook。返回是否至少挂上一个。 */
    public static boolean install() {
        if (!sPrimed) {
            Diag.log("sign", "install skipped: not primed");
            return false;
        }

        boolean a = hookGetPackageInfoInt();
        boolean b = hookGetPackageInfoFlags();
        boolean c = hookCheckSignatures();
        Diag.log("sign", "install int=" + a + " flags=" + b + " checkSig=" + c);
        return a || b || c;
    }

    // ── hook 实现 ──────────────────────────────────────────────────────

    /**
     * getPackageInfo(String, int) —— API 33 之前的主路径，也是壳最常调的。
     * 实例方法，args = [this, packageName, flags]。
     */
    public static final class InfoIntHooker {
        public Method backup;

        public Object callback(Object[] args) {
            if (sBusy.get() != null) {
                return invoke(args);
            }
            sBusy.set(Boolean.TRUE);
            try {
                Object result = invoke(args);
                return patch(result, firstString(args));
            } finally {
                sBusy.remove();
            }
        }

        private Object invoke(Object[] args) {
            Object r = callBackup("Int", backup, args);
            return r;
        }
    }

    /**
     * getPackageInfo(String, PackageInfoFlags) —— API 33+ 新增的重载。
     * Android 17 上壳完全可能调这个，漏了就等于没做。
     */
    public static final class InfoFlagsHooker {
        public Method backup;

        public Object callback(Object[] args) {
            if (sBusy.get() != null) {
                return invoke(args);
            }
            sBusy.set(Boolean.TRUE);
            try {
                Object result = invoke(args);
                return patch(result, firstString(args));
            } finally {
                sBusy.remove();
            }
        }

        private Object invoke(Object[] args) {
            return callBackup("Flags", backup, args);
        }
    }

    /** 每个 hook 只打一次诊断，避免 getPackageInfo 高频调用把日志刷爆。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sDiagInt =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean sDiagFlags =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 统一调用 backup。HushHook 的 backup 是生成的静态方法，参数表里第一个是 this，
     * 因此 {@code invoke(null, args)} 必须把 this 一起传进去。
     *
     * 这里不再默默吞异常：第一次失败会把参数个数、参数类型、backup 形参表、异常
     * 全打出来。上一版正是因为吞掉了异常，才表现为「壳拿到 null 直接 NPE」而无迹可循。
     */
    private static Object callBackup(String tag, Method backup, Object[] args) {
        Throwable err = null;
        Object r = null;
        if (backup == null) {
            err = new IllegalStateException("backup is null");
        } else {
            try {
                r = HookInvoke.call(backup, args);
            } catch (Throwable t) {
                err = t;
            }
        }
        java.util.concurrent.atomic.AtomicBoolean flag =
                "Int".equals(tag) ? sDiagInt : sDiagFlags;
        if (flag.compareAndSet(false, true)) {
            StringBuilder sb = new StringBuilder();
            sb.append(tag).append(" args=").append(args == null ? "null" : String.valueOf(args.length));
            if (args != null) {
                sb.append("(");
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
                }
                sb.append(")");
            }
            sb.append(" backup=").append(backup == null ? "null"
                    : java.util.Arrays.toString(backup.getParameterTypes()));
            if (backup != null && args != null) {
                int pn = backup.getParameterTypes().length;
                sb.append(" mode=").append(pn == args.length ? "static"
                        : (pn == args.length - 1 ? "instance" : "mismatch"));
            }
            sb.append(" ret=").append(r == null ? "null" : r.getClass().getSimpleName());
            if (err != null) sb.append(" err=").append(err);
            Diag.log("sign", sb.toString());
        }
        return r;
    }

    private static boolean hookGetPackageInfoInt() {
        try {
            // ApplicationPackageManager 是 @hide 类，编译期不可见，只能反射取
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            Method target = apm.getDeclaredMethod("getPackageInfo", String.class, int.class);
            target.setAccessible(true);
            InfoIntHooker hooker = new InfoIntHooker();
            Method cb = InfoIntHooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) {
                Diag.log("sign", "hook Int failed: backup null");
                return false;
            }
            hooker.backup = backup;
            return true;
        } catch (Throwable t) {
            Diag.log("sign", "hook Int failed " + t);
            return false;
        }
    }

    private static boolean hookGetPackageInfoFlags() {
        try {
            Class<?> flagsCls = Class.forName("android.content.pm.PackageManager$PackageInfoFlags");
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            Method target = apm.getDeclaredMethod("getPackageInfo", String.class, flagsCls);
            target.setAccessible(true);
            InfoFlagsHooker hooker = new InfoFlagsHooker();
            Method cb = InfoFlagsHooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) {
                Diag.log("sign", "hook Flags failed: backup null");
                return false;
            }
            hooker.backup = backup;
            return true;
        } catch (Throwable t) {
            // API 33 以下没有这个重载，属正常情况，不当作错误刷日志
            Diag.log("sign", "hook Flags skipped " + t);
            return false;
        }
    }

    /**
     * checkSignatures(String, String) —— 壳常用它判断「两个包是不是同一签名」，
     * 例如拿自己和某个系统包、或者拿自己和自己的历史版本比。传进来的两个包名
     * 只要有一个是 guest，就一律视为匹配。
     * 实例方法，args = [this, pkg1, pkg2]。
     */
    public static final class CheckSigHooker {
        public Method backup;

        public Object callback(Object[] args) {
            String p1 = null, p2 = null;
            if (args != null) {
                for (Object o : args) {
                    if (!(o instanceof String)) continue;
                    if (p1 == null) {
                        p1 = (String) o;
                    } else {
                        p2 = (String) o;
                        break;
                    }
                }
            }
            boolean involve = sGuestPkg != null
                    && (sGuestPkg.equals(p1) || sGuestPkg.equals(p2));
            if (involve) {
                if (sCheckLog.getAndIncrement() < 3) {
                    Diag.log("sign", "checkSignatures(" + p1 + ", " + p2 + ") -> MATCH" + caller());
                }
                return PackageManager.SIGNATURE_MATCH;
            }
            // 与 guest 无关的比对必须原样交给真实实现。
            // 1.6.6 之前这里写的是 backup.invoke(null, args)：实例方法给了 null receiver，
            // 必然抛异常，被 catch 吞掉后无条件返回 SIGNATURE_MATCH ——
            // 等于对全进程所有包都说「签名相同」。这是普通 App 出问题的直接原因之一。
            try {
                return HookInvoke.call(backup, args);
            } catch (Throwable t) {
                if (sCheckLog.getAndIncrement() < 3) {
                    Diag.log("sign", "checkSignatures passthrough failed " + t);
                }
                return PackageManager.SIGNATURE_MATCH;
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger sCheckLog =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 2.6.72：谁在调 checkSignatures —— 4 秒内出现两次，要确认是不是 SDK 的连接的返回值。 */
    private static String caller() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("java.lang.Thread")
                        || c.startsWith("com.hush.core.hook.SignatureCompat")) {
                    continue;
                }
                sb.append(" <- ").append(c).append('.').append(e.getMethodName())
                        .append(':').append(e.getLineNumber());
                if (++shown >= 6) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return " <- ?";
        }
    }
    private static final java.util.concurrent.atomic.AtomicInteger sPatchLog =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean hookCheckSignatures() {
        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            Method target = apm.getDeclaredMethod("checkSignatures", String.class, String.class);
            target.setAccessible(true);
            CheckSigHooker hooker = new CheckSigHooker();
            Method cb = CheckSigHooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) {
                Diag.log("sign", "hook checkSig failed: backup null");
                return false;
            }
            hooker.backup = backup;
            return true;
        } catch (Throwable t) {
            Diag.log("sign", "hook checkSig failed " + t);
            return false;
        }
    }

    // ── 替换逻辑 ────────────────────────────────────────────────────────

    private static Object patch(Object result, String pkg) {
        if (pkg == null) return result;
        // 只处理 guest 自己的包。宿主的包（com.hush.space）绝不能被改成 guest 的信息，
        // 否则宿主自己查自己会拿到别的包名和别的证书，行为完全错乱。
        boolean isGuest = pkg.equals(sGuestPkg);
        if (!isGuest) return result;

        // ① 原方法返回 null：壳读 versionCode 就是 NPE。
        //    用 prime 缓存的副本顶上——字段全是 guest 的真实值，壳能继续往下走。
        if (result == null) {
            PackageInfo fb = cloneViaParcel(sGuestPi);
            if (sPatchLog.getAndIncrement() < 3) {
                Diag.log("sign", "patch null(" + pkg + ") -> fallback=" + (fb != null));
            }
            return fb != null ? fb : result;
        }

        if (!(result instanceof PackageInfo)) return result;

        PackageInfo pi = (PackageInfo) result;
        // ② 查 guest 却拿到别家的包信息（binder 没路由过去），整个换掉。
        if (pi.packageName != null && !pkg.equals(pi.packageName)) {
            PackageInfo fb = cloneViaParcel(sGuestPi);
            if (fb != null) {
                if (sPatchLog.getAndIncrement() < 3) {
                    Diag.log("sign", "patch wrong pkg (" + pkg + " got " + pi.packageName + ") -> replace");
                }
                return fb;
            }
        }

        // ③ 签名本来就对：直接返回原对象。
        //    这是绝大多数调用走的分支，不再做 Parcel 深拷贝、不再写日志。
        if (sameSignatures(pi)) {
            return result;
        }

        try {
            PackageInfo copy = cloneViaParcel(pi);
            if (copy == null) copy = pi;

            copy.signatures = sGuestSigs;
            if (Build.VERSION.SDK_INT >= 28 && sGuestSigningInfo != null) {
                copy.signingInfo = sGuestSigningInfo;
            }

            if (sPatchLog.getAndIncrement() < 3) {
                Diag.log("sign", "patched getPackageInfo(" + pkg + ") -> " + shortHash(sGuestSigs[0]));
            }
            return copy;
        } catch (Throwable t) {
            Diag.log("sign", "patch failed " + t);
            return pi;
        }
    }

    /** 返回对象的证书是否已经等于 guest 的证书。相等就什么都不用做。 */
    private static boolean sameSignatures(PackageInfo pi) {
        Signature[] cur = pi.signatures;
        if (cur == null || sGuestSigs == null) return false;
        if (cur.length != sGuestSigs.length) return false;
        try {
            for (int i = 0; i < cur.length; i++) {
                if (cur[i] == null || sGuestSigs[i] == null) return false;
                if (!java.util.Arrays.equals(cur[i].toByteArray(), sGuestSigs[i].toByteArray())) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用 Parcel 做深拷贝：PackageInfo 没有公开的拷贝构造，字段又太多。 */
    private static PackageInfo cloneViaParcel(PackageInfo src) {
        Parcel p = Parcel.obtain();
        try {
            src.writeToParcel(p, 0);
            p.setDataPosition(0);
            return PackageInfo.CREATOR.createFromParcel(p);
        } catch (Throwable t) {
            return null;
        } finally {
            p.recycle();
        }
    }

    /**
     * 取参数表里的第一个 String，也就是 getPackageInfo 的包名。
     *
     * 不按固定下标取：HushHook 回调给的 args 是否包含 this，取决于它的版本约定，
     * 按下标取值一旦错位就会把 flags 当成包名，静默失效。扫描不依赖这个约定。
     */
    private static String firstString(Object[] args) {
        if (args == null) return null;
        for (Object o : args) {
            if (o instanceof String) return (String) o;
        }
        return null;
    }

    /** 只取证书哈希前 8 位做指纹，够比对，也不占日志。 */
    private static String shortHash(Signature sig) {
        try {
            byte[] b = sig.toByteArray();
            int h = 0;
            for (int i = 0; i < b.length && i < 256; i++) {
                h = h * 31 + b[i];
            }
            return Integer.toHexString(h);
        } catch (Throwable t) {
            return "?";
        }
    }
}
