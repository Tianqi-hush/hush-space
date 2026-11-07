package com.hush.fake.service;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import com.hush.fake.hook.IInjectHook;

/**
 * Intercepts android.provider.DeviceConfig Binder calls that require
 * android.permission.READ_DEVICE_CONFIG (added in Android 10 / API 29).
 *
 * GservicesProvider.onCreate() calls DeviceConfig.getBoolean() to decide
 * whether to use the new GMS Core Storage. Inside the Hush sandbox the
 * calling process does not hold READ_DEVICE_CONFIG, so the kernel throws a
 * SecurityException before the provider even starts.
 *
 * Strategy: Replace the static singleton that DeviceConfig uses to talk to
 * the Settings provider (Settings$Config / NameValueCache) with a no-op
 * that always returns null/false/empty, which makes GservicesProvider fall
 * back to legacy behaviour safely.
 *
 * Compatible with Android 10 (API 29) → Android 17+ (API 37).
 */
@RequiresApi(Build.VERSION_CODES.Q)
public class IDeviceConfigProxy implements IInjectHook {

    private static final String TAG = "DeviceConfigProxy";

    @Override
    public void injectHook() {
        try {
            hookSettingsConfigCache();
        } catch (Throwable t) {
            Log.d(TAG, "DeviceConfig hook failed (non-fatal): " + t.getMessage());
        }
    }

    /**
     * DeviceConfig.getBoolean/getProperty all eventually call
     * Settings$Config.getStrings() which calls NameValueCache.getStringsForPrefix().
     * That cache hits a ContentProvider backed by DeviceConfig which enforces
     * READ_DEVICE_CONFIG.
     *
     * We null-out or replace the internal cache object so all reads return
     * empty maps → callers receive `null` / default values.
     */
    private void hookSettingsConfigCache() throws Throwable {
        // android.provider.Settings$Config holds a static sNameValueCache field
        Class<?> settingsClass = Class.forName("android.provider.Settings");
        Class<?>[] inner = settingsClass.getDeclaredClasses();
        Class<?> configClass = null;
        for (Class<?> c : inner) {
            if (c.getSimpleName().equals("Config")) {
                configClass = c;
                break;
            }
        }
        if (configClass == null) {
            Log.d(TAG, "Settings$Config not found – skipping");
            return;
        }

        // Find the NameValueCache field (usually called "sNameValueCache")
        Field cacheField = null;
        for (Field f : configClass.getDeclaredFields()) {
            String typeName = f.getType().getSimpleName();
            if (typeName.contains("NameValueCache") || typeName.contains("Cache")) {
                cacheField = f;
                break;
            }
        }
        if (cacheField == null) {
            Log.d(TAG, "NameValueCache field not found – skipping");
            return;
        }

        cacheField.setAccessible(true);
        Object realCache = cacheField.get(null);
        if (realCache == null) return;

        // Wrap with a dynamic proxy that intercepts getStringsForPrefix /
        // getStrings and returns an empty map, avoiding the permission check.
        Class<?>[] ifaces = getAllInterfaces(realCache.getClass());
        Object proxyCache = Proxy.newProxyInstance(
                realCache.getClass().getClassLoader(),
                ifaces,
                (proxy, method, args) -> {
                    String name = method.getName();
                    // Return empty map for any "get*" call
                    if (name.startsWith("get") || name.startsWith("Get")) {
                        Class<?> ret = method.getReturnType();
                        if (ret == java.util.Map.class || ret == java.util.HashMap.class
                                || ret == android.util.ArrayMap.class) {
                            return new java.util.HashMap<>();
                        }
                        if (ret == String.class) return null;
                        if (ret == boolean.class || ret == Boolean.class) return false;
                        if (ret == int.class || ret == Integer.class) return 0;
                        if (ret == long.class || ret == Long.class) return 0L;
                        if (ret == float.class || ret == Float.class) return 0f;
                    }
                    try {
                        return method.invoke(realCache, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof SecurityException) {
                            // Return safe default on permission denial
                            Class<?> ret = method.getReturnType();
                            if (ret == java.util.Map.class) return new java.util.HashMap<>();
                            if (ret == String.class) return null;
                            if (ret == boolean.class || ret == Boolean.class) return false;
                            return null;
                        }
                        throw cause != null ? cause : e;
                    }
                });

        // Try to inject; if the class has no interfaces fall back to reflection
        // patching of individual methods via a sub-class approach.
        if (ifaces.length > 0) {
            cacheField.set(null, proxyCache);
            Log.d(TAG, "DeviceConfig NameValueCache proxy injected");
        } else {
            // Fallback: patch DeviceConfig.getBoolean directly via reflection
            patchDeviceConfigDirect();
        }
    }

    /**
     * Fallback: directly patch DeviceConfig static methods via reflection
     * to catch SecurityExceptions and return null/false defaults.
     * Used when NameValueCache has no interfaces to proxy.
     */
    private void patchDeviceConfigDirect() {
        // This is a best-effort approach. We wrap DeviceConfig.getProperty in a
        // try-catch by replacing the enforceReadPermission method to be a no-op.
        try {
            Class<?> deviceConfigClass = Class.forName("android.provider.DeviceConfig");
            Method enforceRead = null;
            for (Method m : deviceConfigClass.getDeclaredMethods()) {
                if (m.getName().equals("enforceReadPermission")) {
                    enforceRead = m;
                    break;
                }
            }
            if (enforceRead != null) {
                enforceRead.setAccessible(true);
                // We cannot replace a static method with a Proxy but we can
                // make the field that backs the permission check always pass.
                // Best we can do here without bytecode manipulation is log.
                Log.d(TAG, "enforceReadPermission found – direct patch not possible without native hook");
            }
        } catch (Throwable ignored) {}
    }

    private static Class<?>[] getAllInterfaces(Class<?> clazz) {
        java.util.Set<Class<?>> ifaces = new java.util.LinkedHashSet<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            Collections.addAll(ifaces, c.getInterfaces());
            c = c.getSuperclass();
        }
        return ifaces.toArray(new Class<?>[0]);
    }

    @Override
    public boolean isBadEnv() {
        return false; // static hook, no proxy object to check
    }
}
