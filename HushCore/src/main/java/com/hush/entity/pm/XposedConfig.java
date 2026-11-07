package com.hush.entity.pm;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Milk on 5/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 *
 * <p>2.5.2 起增加 moduleScope：模块 → 允许生效的应用包名集合。
 * 集合为空表示「全部作用域」（默认值），这样从旧版本升级上来的模块
 * 行为完全不变。为了兼容旧版 xposed-module.conf，文件头写了 magic
 * 字节，读到旧格式时回退到原解析路径。
 */
public class XposedConfig implements Parcelable {

    /** 新格式标记。旧版文件第一个字节只可能是 0/1（enable），不会撞上。 */
    private static final byte MAGIC = 0x7F;
    private static final int VERSION = 1;

    public boolean enable;
    public Map<String, Boolean> moduleState = new HashMap<>();
    /** 模块包名 → 作用域应用包名。缺失或空集合 = 全部应用。 */
    public Map<String, List<String>> moduleScope = new HashMap<>();

    public XposedConfig() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(MAGIC);
        dest.writeInt(VERSION);
        dest.writeByte(this.enable ? (byte) 1 : (byte) 0);
        dest.writeInt(this.moduleState.size());
        for (Map.Entry<String, Boolean> entry : this.moduleState.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeValue(entry.getValue());
        }
        dest.writeInt(this.moduleScope.size());
        for (Map.Entry<String, List<String>> entry : this.moduleScope.entrySet()) {
            dest.writeString(entry.getKey());
            List<String> value = entry.getValue();
            if (value == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(value.size());
                for (String pkg : value) {
                    dest.writeString(pkg);
                }
            }
        }
    }

    public XposedConfig(Parcel in) {
        int start = in.dataPosition();
        byte magic = 0;
        try {
            magic = in.readByte();
        } catch (Throwable ignored) {
        }
        if (magic != MAGIC) {
            // 旧格式：没有 magic，回到起点按老布局读。
            in.setDataPosition(start);
            readLegacy(in);
            return;
        }
        in.readInt(); // version
        this.enable = in.readByte() != 0;
        int stateSize = in.readInt();
        this.moduleState = new HashMap<>(stateSize);
        for (int i = 0; i < stateSize; i++) {
            String key = in.readString();
            Boolean value = (Boolean) in.readValue(Boolean.class.getClassLoader());
            this.moduleState.put(key, value);
        }
        int scopeSize = in.readInt();
        this.moduleScope = new HashMap<>(scopeSize);
        for (int i = 0; i < scopeSize; i++) {
            String key = in.readString();
            int n = in.readInt();
            List<String> list = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                String pkg = in.readString();
                if (pkg != null) {
                    list.add(pkg);
                }
            }
            this.moduleScope.put(key, list);
        }
    }

    private void readLegacy(Parcel in) {
        this.enable = in.readByte() != 0;
        int size = in.readInt();
        this.moduleState = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = in.readString();
            Boolean value = (Boolean) in.readValue(Boolean.class.getClassLoader());
            this.moduleState.put(key, value);
        }
        this.moduleScope = new HashMap<>();
    }

    public static final Parcelable.Creator<XposedConfig> CREATOR = new Parcelable.Creator<XposedConfig>() {
        @Override
        public XposedConfig createFromParcel(Parcel source) {
            return new XposedConfig(source);
        }

        @Override
        public XposedConfig[] newArray(int size) {
            return new XposedConfig[size];
        }
    };
}
