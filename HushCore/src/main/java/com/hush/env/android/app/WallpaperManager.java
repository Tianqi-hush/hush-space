package com.hush.env.android.app;


import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;
import com.hush.reflection.annotation.BStaticField;

@BClassName("android.app.WallpaperManager")
public interface WallpaperManager {
    @BStaticField
    Object sGlobals();

    @BClassName("android.app.WallpaperManager$Globals")
    interface Globals {
        @BField
        Object mService();
    }
}
