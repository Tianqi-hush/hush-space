package net_62v.external;

import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public interface IHushProcessCallback {
    /**
     * Legacy callback method expected by BoxApplication.kt
     * @param processName The name of the process starting (e.g., "com.pubg.imobile")
     * @param s1 Additional parameter (usually null)
     */
    default void onCreate(String processName, String s1) {
    }
}
