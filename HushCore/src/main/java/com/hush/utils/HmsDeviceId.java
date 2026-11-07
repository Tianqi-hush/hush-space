package com.hush.utils;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 2.6.46：补上 HMS 的 UUID，让 deviceID 不再是空串。
 *
 * <p><b>2.6.45 的日志</b>：READ_PHONE_STATE 已经放行（{@code permReadPhone=0}），
 * 宿主也主动申请过了，但 {@code /IdmClientApi/IDM/loginBySMS} 仍然返回</p>
 * <pre>
 *   {"errorCode":70001201,"errorDesc":"Parameter[deviceID]is invalid"}
 * </pre>
 *
 * <p>原因在 Android 10 以后：IMEI/MEID 对非系统应用一律返回 null，
 * {@code Build.getSerial()} 在多数机型上也是 "unknown"，所以
 * {@code DeviceInfoUtil.getRegisterDeviceInfo} 只能退到
 * {@code TerminalInfo.getUUid(context)}。这个方法走的是
 * {@code content://com.huawei.hwid.inner.provider} 的 TAG_METHOD_QUERY_UUID：
 * 真机上由系统的 hwid 进程应答，容器里 com.huawei.hwid 是虚拟包，
 * 这条 provider 查不到东西 → 返回空串 → deviceID 空 → 服务端判非法。</p>
 *
 * <p>HMS 自己其实是有兜底的（{@code y32.a()} 里 memcache 为空就
 * {@code UUID.randomUUID()} 并写回 provider），只是那条兜底也要 provider
 * 能写。这里在 {@code TerminalInfo.getUUid} 返回空/"NULL"/"unknown" 时
 * 补一个**容器内持久化**的 UUID：所有 HMS 子进程都读同一个文件，
 * 保证同一个 guest 包在任何进程里拿到同一个 deviceID。</p>
 */
public final class HmsDeviceId {

    private static final String FILE = "hush_hms_device_uuid";

    private static final java.util.Set<ClassLoader> DONE =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<ClassLoader, Boolean>());

    private HmsDeviceId() {
    }

    public static void install(ClassLoader cl) {
        if (cl == null || !DONE.add(cl)) return;
        hookUuid(cl, "getUUid");
        hookUuid(cl, "getUUidPersistent");
        logOnly(cl, "getSerialNo");
        logOnly(cl, "getDeviceId");
        logOnly(cl, "getUnitedId");
    }

    private static void hookUuid(ClassLoader cl, String name) {
        try {
            Method m = Class.forName("com.huawei.hwid.common.util.TerminalInfo", false, cl)
                    .getDeclaredMethod(name, Context.class);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    String s = r == null ? "" : r.toString();
                    if (isBad(s)) {
                        String u = uuid((Context) param.args[0]);
                        param.setResult(u);
                        Diag.log("dev", "TerminalInfo." + name + " 原本为空，补 UUID " + u);
                    } else {
                        Diag.log("dev", "TerminalInfo." + name + " -> " + s);
                    }
                }
            });
            Diag.log("dev", "探针已挂 TerminalInfo." + name);
        } catch (Throwable t) {
            Diag.log("dev", "探针 TerminalInfo." + name + " 失败 " + t);
        }
    }

    private static void logOnly(ClassLoader cl, String name) {
        try {
            Method m = Class.forName("com.huawei.hwid.common.util.TerminalInfo", false, cl)
                    .getDeclaredMethod(name, Context.class);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    String s = r == null ? "null" : r.toString();
                    if (s.length() > 40) s = s.substring(0, 40) + "…";
                    Diag.log("dev", "TerminalInfo." + name + " -> " + s);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean isBad(String s) {
        if (TextUtils.isEmpty(s)) return true;
        if ("NULL".equalsIgnoreCase(s)) return true;
        if ("unknown".equalsIgnoreCase(s)) return true;
        return "null".equalsIgnoreCase(s);
    }

    /** 2.6.47 的日志里 7ms 内出现了两个不同 UUID，因为按 ctx.getFilesDir() 取目录，
     *  不同 guest 包（hwid / account / account.core）各写一份。deviceID 必须全局一致，
     *  所以固定落在 hwid 包的文件目录下，并加进程内缓存。 */
    private static volatile String sUuid;

    /** 读容器里持久化的 UUID；没有就生成一个并落盘（所有 HMS 子进程共享）。 */
    private static synchronized String uuid(Context ctx) {
        if (sUuid != null) return sUuid;
        File dir;
        try {
            dir = com.hush.core.env.BEnvironment.getDataFilesDir("com.huawei.hwid", 0);
        } catch (Throwable t) {
            dir = ctx == null ? null : ctx.getFilesDir();
        }
        if (dir == null) return UUID.randomUUID().toString();
        File f = new File(dir, FILE);
        try {
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) f.length()];
                    int off = 0;
                    while (off < b.length) {
                        int n = in.read(b, off, b.length - off);
                        if (n <= 0) break;
                        off += n;
                    }
                    String s = new String(b, 0, off, "UTF-8").trim();
                    if (!isBad(s)) {
                        sUuid = s;
                        return s;
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String u = UUID.randomUUID().toString();
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(u.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            Diag.log("dev", "写 UUID 失败 " + t);
        }
        sUuid = u;
        return u;
    }
}
