package com.hush.core.system.user;

import android.os.Parcel;
import android.os.RemoteException;

import androidx.core.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.hush.core.env.BEnvironment;
import com.hush.core.env.SpaceSettingsStore;
import com.hush.core.system.ISystemService;
import com.hush.core.system.pm.BPackageManagerService;
import com.hush.utils.CloseUtils;
import com.hush.utils.FileUtils;

public class BUserManagerService extends IBUserManagerService.Stub implements ISystemService {
    private static final BUserManagerService sService = new BUserManagerService();
    public final HashMap<Integer, BUserInfo> mUsers = new HashMap<>();
    public final Object mUserLock = new Object();

    public static BUserManagerService get() {
        return sService;
    }

    @Override
    public void systemReady() {
        scanUserL();
    }

    @Override
    public BUserInfo getUserInfo(int userId) {
        synchronized (mUserLock) {
            return mUsers.get(userId);
        }
    }

    @Override
    public boolean exists(int userId) {
        synchronized (mUsers) {
            return mUsers.get(userId) != null;
        }
    }

    @Override
    public BUserInfo createUser(int userId) throws RemoteException {
        synchronized (mUserLock) {
            if (exists(userId)) {
                return getUserInfo(userId);
            }
            return createUserLocked(userId);
        }
    }

    @Override
    public List<BUserInfo> getUsers() {
        synchronized (mUsers) {
            ArrayList<BUserInfo> bUsers = new ArrayList<>();
            for (BUserInfo value : mUsers.values()) {
                if (value.id >= 0) {
                    bUsers.add(value);
                }
            }
            return bUsers;
        }
    }

    public List<BUserInfo> getAllUsers() {
        synchronized (mUsers) {
            return new ArrayList<>(mUsers.values());
        }
    }

    @Override
    public void deleteUser(int userId) throws RemoteException {
        synchronized (mUserLock) {
            // 以前整段放在一个 try 里，任何一步抛异常（包管理器清理失败、
            // 外部存储目录被占用）都会跳过 mUsers.remove，用户点了「删除」，
            // 列表刷新后空间还在，看起来就是「空间被锁死删不掉」。
            // 现在逐段兜底，用户条目一定先移除并落盘。
            try {
                BPackageManagerService.get().deleteUser(userId);
            } catch (Throwable t) {
                com.hush.utils.Diag.log("user", "deleteUser 清理包失败 uid=" + userId + " " + t);
            }
            synchronized (mUsers) {
                mUsers.remove(userId);
                saveUserInfoLocked();
            }
            try {
                FileUtils.deleteDir(BEnvironment.getUserDir(userId));
            } catch (Throwable t) {
                com.hush.utils.Diag.log("user", "deleteUser 清理数据失败 uid=" + userId + " " + t);
            }
            try {
                // 每个空间的外部存储是独立目录，删空间时一并清掉。
                FileUtils.deleteDir(BEnvironment.getExternalStorageDirectory(userId));
            } catch (Throwable t) {
                com.hush.utils.Diag.log("user", "deleteUser 清理外部存储失败 uid=" + userId + " " + t);
            }
            try {
                SpaceSettingsStore.clearSpace(userId);
            } catch (Throwable ignored) {
            }
        }
    }

    private BUserInfo createUserLocked(int userId) {
        BUserInfo bUserInfo = new BUserInfo();
        bUserInfo.id = userId;
        bUserInfo.status = BUserStatus.ENABLE;
        mUsers.put(userId, bUserInfo);
        synchronized (mUsers) {
            saveUserInfoLocked();
        }
        return bUserInfo;
    }

    private void saveUserInfoLocked() {
        Parcel parcel = Parcel.obtain();
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getUserInfoConf());
        FileOutputStream fileOutputStream = null;
        try {
            ArrayList<BUserInfo> bUsers = new ArrayList<>(mUsers.values());
            parcel.writeTypedList(bUsers);
            try {
                fileOutputStream = atomicFile.startWrite();
                FileUtils.writeParcelToOutput(parcel, fileOutputStream);
                atomicFile.finishWrite(fileOutputStream);
            } catch (IOException e) {
                e.printStackTrace();
                atomicFile.failWrite(fileOutputStream);
            } finally {
                CloseUtils.close(fileOutputStream);
            }
        } finally {
            parcel.recycle();
        }
    }

    private void scanUserL() {
        synchronized (mUserLock) {
            Parcel parcel = Parcel.obtain();
            InputStream is = null;
            try {
                File userInfoConf = BEnvironment.getUserInfoConf();
                if (!userInfoConf.exists()) {
                    return;
                }
                is = new FileInputStream(BEnvironment.getUserInfoConf());
                byte[] bytes = FileUtils.toByteArray(is);
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);

                ArrayList<BUserInfo> loadUsers = parcel.createTypedArrayList(BUserInfo.CREATOR);
                if (loadUsers == null)
                    return;
                synchronized (mUsers) {
                    mUsers.clear();
                    for (BUserInfo loadUser : loadUsers) {
                        mUsers.put(loadUser.id, loadUser);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                parcel.recycle();
                CloseUtils.close(is);
            }
        }
    }
}