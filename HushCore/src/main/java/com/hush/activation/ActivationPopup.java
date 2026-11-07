package com.hush.activation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.hush.HushCore;
import org.lsposed.lsparanoid.Obfuscate;

/**
 * Shows simple Toast messages only — no HTML, no WebView, no Dialog.
 * Only user-facing messages: Expired / Blocked / Not Activated.
 * Internal gate codes (G1, G2, G3, G4) are NEVER shown to the user.
 */
@Obfuscate
public final class ActivationPopup {

    private static final Handler H = new Handler(Looper.getMainLooper());

    /** Called when gateCheck() detects expired or tampered vault. */
    public static void showAuto() {
        // No-op
    }

    /** Called with an explicit reason code from doActivate. */
    public static void showForCode(String code) {
        // No-op
    }

    private static String resolveMessage(String code) {
        if (code == null) return "SDK Not Activated";
        switch (code) {
            case "G3":
                // Gate 3 = server rejected key or no network
                return "SDK Not Activated";
            case "G4":
                // Gate 4 = server response bad — treat as not activated
                return "SDK Not Activated";
            case "Fail":
                // vault tampered / device mismatch
                return "SDK Blocked";
            case "NA":
                // vault empty — never activated
                return "SDK Not Activated";
            case "OK":
            case "Valid":
                return null; // no toast on success
            default:
                // G1:x, G2, NL, NC — internal, never expose to user
                return "SDK Not Activated";
        }
    }

    private static void showToast(String msg) {
        if (msg == null) return;
        Context ctx = HushCore.getContext();
        if (ctx == null) return;
        H.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }
}
