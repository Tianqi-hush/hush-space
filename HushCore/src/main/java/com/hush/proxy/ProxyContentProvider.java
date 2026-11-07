package com.hush.proxy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hush.app.BActivityThread;
import com.hush.entity.AppConfig;
import com.hush.utils.compat.BundleCompat;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProxyContentProvider extends ContentProvider {
    private static final String TAG = "ProxyContentProvider";
    
    @Override
    public boolean onCreate() {
        return true; // true return karo
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        try {
            if (method != null && method.equals("_Black_|_init_process_")) {
                if (extras != null) {
                    extras.setClassLoader(AppConfig.class.getClassLoader());
                    AppConfig appConfig = extras.getParcelable(AppConfig.KEY);
                    
                    if (appConfig != null) {
                        BActivityThread activityThread = BActivityThread.currentActivityThread();
                        if (activityThread != null) {
                            activityThread.initProcess(appConfig);
                            Bundle bundle = new Bundle();
                            BundleCompat.putBinder(bundle, "_Black_|_client_", activityThread);
                            return bundle;
                        } else {
                            Log.e(TAG, "BActivityThread is null");
                        }
                    } else {
                        Log.e(TAG, "AppConfig is null");
                    }
                } else {
                    Log.e(TAG, "Extras is null");
                }
            }
            return super.call(method, arg, extras);
        } catch (Exception e) {
            // 🔥 CRASH FIX: Sab exceptions catch karo
            Log.e(TAG, "Error in call method: " + e.getMessage());
            return new Bundle(); // Empty bundle return karo
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    // Inner classes for each provider instance
    public static class P0 extends ProxyContentProvider { }
    public static class P1 extends ProxyContentProvider { }
    public static class P2 extends ProxyContentProvider { }
    public static class P3 extends ProxyContentProvider { }
    public static class P4 extends ProxyContentProvider { }
    public static class P5 extends ProxyContentProvider { }
    public static class P6 extends ProxyContentProvider { }
    public static class P7 extends ProxyContentProvider { }
    public static class P8 extends ProxyContentProvider { }
    public static class P9 extends ProxyContentProvider { }
    public static class P10 extends ProxyContentProvider { }
    public static class P11 extends ProxyContentProvider { }
    public static class P12 extends ProxyContentProvider { }
    public static class P13 extends ProxyContentProvider { }
    public static class P14 extends ProxyContentProvider { }
    public static class P15 extends ProxyContentProvider { }
    public static class P16 extends ProxyContentProvider { }
    public static class P17 extends ProxyContentProvider { }
    public static class P18 extends ProxyContentProvider { }
    public static class P19 extends ProxyContentProvider { }
    public static class P20 extends ProxyContentProvider { }
    public static class P21 extends ProxyContentProvider { }
    public static class P22 extends ProxyContentProvider { }
    public static class P23 extends ProxyContentProvider { }
    public static class P24 extends ProxyContentProvider { }

    public static class P25 extends ProxyContentProvider {}
    public static class P26 extends ProxyContentProvider {}
    public static class P27 extends ProxyContentProvider {}
    public static class P28 extends ProxyContentProvider {}
    public static class P29 extends ProxyContentProvider {}
    public static class P30 extends ProxyContentProvider {}
    public static class P31 extends ProxyContentProvider {}
    public static class P32 extends ProxyContentProvider {}
    public static class P33 extends ProxyContentProvider {}
    public static class P34 extends ProxyContentProvider {}
    public static class P35 extends ProxyContentProvider {}
    public static class P36 extends ProxyContentProvider {}
    public static class P37 extends ProxyContentProvider {}
    public static class P38 extends ProxyContentProvider {}
    public static class P39 extends ProxyContentProvider {}
    public static class P40 extends ProxyContentProvider {}
    public static class P41 extends ProxyContentProvider {}
    public static class P42 extends ProxyContentProvider {}
    public static class P43 extends ProxyContentProvider {}
    public static class P44 extends ProxyContentProvider {}
    public static class P45 extends ProxyContentProvider {}
    public static class P46 extends ProxyContentProvider {}
    public static class P47 extends ProxyContentProvider {}
    public static class P48 extends ProxyContentProvider {}
    public static class P49 extends ProxyContentProvider {}
}
