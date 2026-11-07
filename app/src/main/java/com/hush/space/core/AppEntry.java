package com.hush.space.core;

import android.graphics.drawable.Drawable;

/** 列表中的一条应用记录（虚拟空间应用或本机应用）。 */
public class AppEntry {

    public String packageName;
    public String label;
    public String versionName;
    public String apkPath;
    public int iconRes;
    public int labelRes;
    public int targetSdk;
    public long size;
    public boolean system;
    public boolean cloned;
    /** 同时被作为 Xposed 模块安装在 USER_XPOSED 里。 */
    public boolean module;
    /** 华为/荣耀账号与基础服务（HMS Core 等），装进空间内部运行。 */
    public boolean systemBridge;
    public Drawable icon;

    public AppEntry(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppEntry)) return false;
        AppEntry other = (AppEntry) o;
        return packageName != null && packageName.equals(other.packageName);
    }

    @Override
    public int hashCode() {
        return packageName == null ? 0 : packageName.hashCode();
    }
}
