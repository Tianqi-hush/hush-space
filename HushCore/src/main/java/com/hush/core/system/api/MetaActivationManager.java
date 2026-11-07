package com.hush.core.system.api;

import com.hush.activation.SdkBridge;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class MetaActivationManager {

    public static void activateSdk(final String userkey) {
        try { SdkBridge.getInstance().activateSdk(userkey); } catch (Throwable ignored) {}
    }

    public static String getServerMessage() {
        try { return SdkBridge.getInstance().getServerMessage(); } catch (Throwable t) { return ""; }
    }

    public static boolean getActivatedStatus() {
        try { return SdkBridge.getInstance().getActivatedSdk(); } catch (Throwable t) { return false; }
    }
}