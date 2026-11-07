package com.hush.entity.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;

import com.hush.HushCore;
import com.hush.core.system.user.BUserHandle;
import com.hush.utils.FileUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Milk on 5/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class InstalledModule implements Parcelable {
    public String packageName;
    public String name;
    public String desc;
    public String main;
    public boolean enable;
    /**
     * 作用域：允许加载该模块的应用包名。
     * 空集合表示「全部应用」，这是新装模块的默认值。
     */
    public List<String> scope = new ArrayList<>();

    public InstalledModule() {
    }


    public ApplicationInfo getApplication() {
        return HushCore.getBPackageManager().getApplicationInfo(packageName, FileUtils.FileMode.MODE_IWUSR, BUserHandle.USER_XPOSED);
    }

    public PackageInfo getPackageInfo() {
        return HushCore.getBPackageManager().getPackageInfo(packageName, FileUtils.FileMode.MODE_IWUSR, BUserHandle.USER_XPOSED);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.packageName);
        dest.writeString(this.name);
        dest.writeString(this.desc);
        dest.writeString(this.main);
        dest.writeByte(this.enable ? (byte) 1 : (byte) 0);
        if (this.scope == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(this.scope.size());
            for (String pkg : this.scope) {
                dest.writeString(pkg);
            }
        }
    }

    protected InstalledModule(Parcel in) {
        this.packageName = in.readString();
        this.name = in.readString();
        this.desc = in.readString();
        this.main = in.readString();
        this.enable = in.readByte() != 0;
        int n = in.readInt();
        this.scope = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String pkg = in.readString();
            if (pkg != null) {
                this.scope.add(pkg);
            }
        }
    }

    public static final Creator<InstalledModule> CREATOR = new Creator<InstalledModule>() {
        @Override
        public InstalledModule createFromParcel(Parcel source) {
            return new InstalledModule(source);
        }

        @Override
        public InstalledModule[] newArray(int size) {
            return new InstalledModule[size];
        }
    };
}
