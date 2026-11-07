package com.hush.proxy;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import com.hush.HushCore;
import com.hush.app.BActivityThread;

public class HostIntentInterceptActivity extends Activity {
    private static final String TAG = "HostIntentIntercept";

    // The virtual game package + userId that last opened an external login.
    // Set by ActivityManagerCommonProxy.StartActivity BEFORE the intent leaks
    // out to the real host, so when the return deep-link arrives here we know
    // exactly which virtual game is waiting for the result.
    private static volatile String sLastCallerPkg = null;
    private static volatile int sLastCallerUserId = 0;

    public static void attachPendingBrowserCaller(String pkg, int userId) {
        sLastCallerPkg = pkg;
        sLastCallerUserId = userId;
        Log.i(TAG, "Registered pending return caller: " + pkg + " (userId=" + userId + ")");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Intent intent = getIntent();
            if (intent == null || intent.getData() == null) {
                finish();
                return;
            }

            Uri uri = intent.getData();
            Log.i(TAG, "Intercepted deep-link return: " + uri);

            // ── Step 1: write to the bridge file so AppInstrumentation can
            // inject the URL into the game's login activity even if
            // virtual AMS routing below fails.
            ProxyWebActivity.storePendingRedirect(uri.toString());

            // ── Step 2: determine the target virtual game package ────────────
            String targetPkg = null;

            // 2a. Try virtual PM resolution — if the game's manifest declares
            // an intent-filter for this scheme the virtual PM knows it.
            try {
                Intent probe = new Intent(Intent.ACTION_VIEW, uri);
                probe.setComponent(null);
                probe.setPackage(null);
                ResolveInfo ri = HushCore.getBPackageManager().resolveActivity(
                        probe, 0, null, sLastCallerUserId);
                if (ri != null && ri.activityInfo != null) {
                    targetPkg = ri.activityInfo.packageName;
                    Log.i(TAG, "Virtual PM resolved scheme to: " + targetPkg);
                }
            } catch (Throwable ignored) {
            }

            // 2b. Fall back to the game that registered as pending caller.
            if (targetPkg == null && sLastCallerPkg != null) {
                targetPkg = sLastCallerPkg;
                Log.i(TAG, "Using registered pending caller: " + targetPkg);
            }

            // 2c. Fall back to cct.<pkg> host convention (legacy).
            if (targetPkg == null) {
                String host = uri.getHost();
                if (host != null && host.startsWith("cct.")) {
                    targetPkg = host.substring(4);
                    Log.i(TAG, "CCT convention resolved to: " + targetPkg);
                }
            }

            // 2d. Last resort: first running virtual process.
            if (targetPkg == null) {
                try {
                    java.util.List<String> running = com.hush.core.system.BProcessManagerService.get()
                            .getRunningPackages();
                    if (running != null && !running.isEmpty()) {
                        targetPkg = running.get(0);
                        Log.i(TAG, "Fallback to first running process: " + targetPkg);
                    }
                } catch (Exception ignored) {
                }
            }

            // ── Step 3: route to the virtual game ───────────────────────────
            Intent redirectIntent = new Intent(Intent.ACTION_VIEW, uri);
            redirectIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            if (targetPkg != null) {
                redirectIntent.setPackage(targetPkg);
                Log.i(TAG, "Routing return deep-link to virtual game: " + targetPkg);
            }

            int userId = sLastCallerUserId;
            HushCore.get().startActivity(redirectIntent, userId);

        } catch (Exception e) {
            Log.e(TAG, "Failed to route return deep-link", e);
        } finally {
            // Always clear after use to prevent stale routing on next login.
            sLastCallerPkg = null;
            sLastCallerUserId = 0;
            finish();
        }
    }
}