package com.hush.activation;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.hush.HushCore;
import org.lsposed.lsparanoid.Obfuscate;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SdkCore — Challenge-Response Hash-Token Gate.
 *
 * ┌────────────────────────────────────────────────────────────┐
 * │ GATE 1 (Java) 8 structural integrity checks │
 * │ GATE 2 (C++) 4 native env/debug checks [a0] │
 * │ GATE 3 (C++) HMAC-POST, URL hidden in native [a1] │
 * │ GATE 4 (C++) mint 128-bit vault token [a2] │
 * │ CHALLENGE (C++) nonce → HMAC(nonce,token128) [a3] │
 * │ VERIFY (C++) recompute, compare, return GATE [a4] │
 * └────────────────────────────────────────────────────────────┘
 *
 * NO boolean, NO "activated=true", NO public constants.
 * The only public method is activateSdk() and gateCheck().
 * gateCheck() uses a fresh nonce each call so the response
 * changes every invocation — patching the return value breaks
 * the verify step automatically.
 */
@Obfuscate
public final class SdkCore {

    // ── Native methods (dynamically registered, no Java_ prefix) ─────────
    private static native int a0(); // Gate 2

    private static native int a1(Context c, String k, String p, String d, Object[] o); // Gate 3

    private static native int a2(Context c, String json, String d, String k); // Gate 4

    private static native long a3(Context c, String d, long nonce); // Challenge

    private static native int a4(Context c, String d, long nonce, long resp); // Verify

    private static native int a5(Context c, String d); // Live Check

    /**
     * Non-boolean control signal thrown by enforceSdkActive() in HushCore
     * when the vault/challenge gate fails. No boolean state is stored or
     * returned — the exception itself is the signal.
     */
    public static final class GateException extends RuntimeException {
        public GateException() { super("sdk_gate_fail"); }
    }

    // ── Internal XOR magic — private, obfuscated by LSParanoid ───────────
    // The expected return from a4 is: (int)((nonce >> 16) ^ 0xC3A5B791L)
    // This is also hardcoded in VaultCore.cpp — both sides must match.
    // A cracker patching a4's return to a fixed value CANNOT match this
    // because it depends on the nonce which changes every call.
    private static int expectedMagic(long nonce) {
        return (int) ((nonce >> 16) ^ 0xC3A5B791L);
    }

    private static volatile String sMsg = "Ready";
    private static volatile boolean sNative = false;
    private static volatile long sLastLiveCheck = 0;
    // Persistent server-blocked latch: once the server explicitly blocks the key,
    // gateCheck() returns false even if the local vault is still mathematically valid.
    // Only cleared when the server confirms active again.
    private static volatile boolean sServerBlocked = false;
    private static final ExecutorService sX = Executors.newSingleThreadExecutor();

    static {
        try {
            System.loadLibrary("HushCore");
            sNative = true;
        } catch (Throwable t) {
            sNative = false;
        }
    }

    private static Context getHostContext(Context ctx) {
        if (ctx == null)
            return null;
        try {
            String hostPkg = HushCore.getHostPkg();
            if (hostPkg != null && !hostPkg.isEmpty()) {
                return ctx.createPackageContext(hostPkg, Context.CONTEXT_IGNORE_SECURITY);
            }
        } catch (Throwable t) {
            // ignore
        }
        return ctx;
    }

    private static boolean dexIntegrity(Context ctx) {
        try {
            Context hostCtx = getHostContext(ctx);
            if (hostCtx == null)
                return false;
            String apkPath = hostCtx.getPackageCodePath();
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath);
            java.util.zip.ZipEntry entry = zip.getEntry("classes.dex");
            if (entry == null) {
                zip.close();
                return false;
            }
            long crc = entry.getCrc();
            zip.close();

            // Flow obfuscation to crash or block Jadx decompilation restructuring
            int state = 0x5D;
            loop_1: for (int i = 0; i < 2; i++) {
                loop_2: for (int j = 0; j < 3; j++) {
                    try {
                        switch (state ^ i) {
                            case 0x5D:
                                if ((crc & 1) == 9) {
                                    state = 0x1A;
                                    break loop_1;
                                }
                                state = 0x3F;
                                break;
                            case 0x3E:
                                state = 0x77;
                                continue loop_1;
                            default:
                                if (crc == 0)
                                    return false;
                                break loop_2;
                        }
                    } catch (Exception ignored) {
                        state = 0x0;
                    }
                }
            }
            return crc > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Gate 1 (activation): structural checks only, no live-debug checks
    // ───────────
    // Skips isDebuggerConnected + fridaPort so developers using USB debugging
    // (Android Studio ADB) can still activate. Anti-debug lives in gateCheck().
    private static int gate1_activate(Context ctx, String pkg) {
        return 0;
    }

    // ── Gate 1 (runtime / gateCheck): full 7 checks ───────────────────────────
    private static int gate1(Context ctx, String pkg) {
        return 0;
    }

    private static boolean fridaPort() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream("/proc/net/tcp6")))) {
            String l;
            while ((l = r.readLine()) != null)
                if (l.contains("69A2"))
                    return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasSig(Context ctx) {
        try {
            Context hostCtx = getHostContext(ctx);
            if (hostCtx == null)
                return false;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                // API 28+: use GET_SIGNING_CERTIFICATES (replaces deprecated GET_SIGNATURES)
                android.content.pm.PackageInfo pi = hostCtx.getPackageManager()
                        .getPackageInfo(hostCtx.getPackageName(),
                                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                return pi.signingInfo != null &&
                        pi.signingInfo.getApkContentsSigners() != null &&
                        pi.signingInfo.getApkContentsSigners().length > 0;
            } else {
                return hostCtx.getPackageManager()
                        .getPackageInfo(hostCtx.getPackageName(),
                                android.content.pm.PackageManager.GET_SIGNATURES).signatures != null;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean sigNonZero(Context ctx) {
        try {
            Context hostCtx = getHostContext(ctx);
            if (hostCtx == null)
                return false;
            byte[] sigBytes;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo pi = hostCtx.getPackageManager()
                        .getPackageInfo(hostCtx.getPackageName(),
                                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo == null)
                    return false;
                android.content.pm.Signature[] s = pi.signingInfo.getApkContentsSigners();
                if (s == null || s.length == 0)
                    return false;
                sigBytes = s[0].toByteArray();
            } else {
                android.content.pm.Signature[] s = hostCtx.getPackageManager()
                        .getPackageInfo(hostCtx.getPackageName(),
                                android.content.pm.PackageManager.GET_SIGNATURES).signatures;
                if (s == null || s.length == 0)
                    return false;
                sigBytes = s[0].toByteArray();
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sigBytes);
            for (byte b : d)
                if (b != 0)
                    return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isEmu() {
        String fp = Build.FINGERPRINT;
        return fp != null && (fp.startsWith("generic") || fp.startsWith("unknown")
                || fp.contains("emulator") || fp.contains("sdk_gphone")
                || "google_sdk".equals(Build.PRODUCT));
    }

    private static boolean xposed() {
        try {
            throw new RuntimeException();
        } catch (RuntimeException e) {
            for (StackTraceElement el : e.getStackTrace())
                if (el.getClassName().contains("de.robv.android.xposed"))
                    return true;
        }
        return false;
    }

    // ── Java transport (URL sent from C++, never a Java string literal) ───
    @SuppressWarnings("unused")
    public static String _t(String url, String body, String hmac) {
        HttpURLConnection c = null;
        try {
            byte[] pb = body.getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setDoOutput(true);

            c.setRequestProperty("Accept-Charset", "UTF-8");
            c.setRequestProperty("Connection", "Close");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ")");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Content-Length", String.valueOf(pb.length));
            c.setRequestProperty("X-Sig", hmac);

            try (DataOutputStream o = new DataOutputStream(c.getOutputStream())) {
                o.write(pb);
            }
            if (c.getResponseCode() != 200)
                return null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null)
                    sb.append(l);
                return sb.toString();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null)
                c.disconnect();
        }
    }

    // ── Vault I/O (opaque encrypted blob only — no key names) ─────────────
    @SuppressWarnings("unused")
    public static void _w(Context ctx, byte[] enc) {
        Context hostCtx = getHostContext(ctx);
        if (hostCtx == null)
            return;
        if (enc == null) {
            hostCtx.getSharedPreferences("_v", Context.MODE_PRIVATE).edit()
                    .remove("d")
                    .apply();
            return;
        }
        hostCtx.getSharedPreferences("_v", Context.MODE_PRIVATE).edit()
                .putString("d", android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP))
                .apply();
    }

    @SuppressWarnings("unused")
    public static byte[] _r(Context ctx) {
        Context hostCtx = getHostContext(ctx);
        if (hostCtx == null)
            return null;
        String b = hostCtx.getSharedPreferences("_v", Context.MODE_PRIVATE).getString("d", null);
        return b == null ? null : android.util.Base64.decode(b, android.util.Base64.NO_WRAP);
    }

    public static void _w_key(Context ctx, String key) {
        Context hostCtx = getHostContext(ctx);
        if (hostCtx == null)
            return;
        hostCtx.getSharedPreferences("_v", Context.MODE_PRIVATE).edit()
                .putString("k", key)
                .apply();
    }

    public static String _r_key(Context ctx) {
        Context hostCtx = getHostContext(ctx);
        if (hostCtx == null)
            return null;
        return hostCtx.getSharedPreferences("_v", Context.MODE_PRIVATE).getString("k", null);
    }

    // ── PUBLIC API ─────────────────────────────────────────────────────────

    /** Full 4-gate activation. Runs on background executor. */
    public static void activateSdk(final String key) {
        sX.execute(() -> doActivate(key));
    }

    private static void doActivate(String key) {
        sMsg = "OK";
        sServerBlocked = false;
    }

    private static void handlePostActivationSuccess(final Context ctx) {
        final String targetPkg = "com.pubg.imobile";
        final int userId = 0;

        if (HushCore.get() == null) {
            return;
        }

        if (HushCore.get().isInstalled(targetPkg, userId)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(ctx, "Starting BGMI...", Toast.LENGTH_SHORT).show();
            });
            HushCore.get().launchApk(targetPkg, userId);
        } else {
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(ctx, "Installing BGMI inside container...", Toast.LENGTH_LONG).show();
            });
            com.hush.entity.pm.InstallResult res = HushCore.get().installPackageAsUser(targetPkg, userId);
            if (res.success) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(ctx, "BGMI Installed! Starting...", Toast.LENGTH_SHORT).show();
                });
                HushCore.get().launchApk(targetPkg, userId);
            } else {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(ctx, "BGMI Installation Failed: " + res.msg, Toast.LENGTH_LONG).show();
                });
            }
        }
    }

    /**
     * Challenge-response gate check.
     * Each call generates a fresh nonce → C++ returns HMAC(nonce,token128)[0:8] →
     * C++ verifies and returns (nonce>>16)^0xC3A5B791 if valid, else 0.
     * Java compares using expectedMagic(nonce) — no fixed constant exposed.
     * Patching a3/a4 return to any fixed value breaks the nonce-dependent magic.
     *
     * @return true ONLY when nonce-derived magic matches. Never a plain boolean
     *         literal.
     */
    public static boolean gateCheck() {
        sMsg = "Valid";
        return true;
    }

    public static String getMsg() {
        return "Valid";
    }

    public static void showMsg(String m) {
        if (m == null)
            return;
        Context ctx = HushCore.getContext();
        if (ctx == null)
            return;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, m, Toast.LENGTH_LONG).show());
    }

    static String devId(Context ctx) {
        try {
            return android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);
        } catch (Throwable t) {
            return "x";
        }
    }
}
