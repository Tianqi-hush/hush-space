package com.hush.utils;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.FileDescriptor;

/**
 * 2.6.71：把华为 SDK 拿到的那一个 binder 换成 Java 层的包装。
 *
 * <p>2.6.69/2.6.70 记录 {@code android.os.BinderProxy.transact} 一条都没有：
 * 那是 native 方法，容器里的 hook 挂不上。但 SDK 的调用一定得经过我们交给
 * 它的这个 IBinder 引用，所以直接包一层，transact 在 Java 代码里记录
 * code / data 大小 / reply 大小 / 返回值 / 异常，然后原样转发。</p>
 */
public final class HmsBinderTap implements IBinder {

    private final IBinder base;
    private final String why;
    private final String desc;

    public HmsBinderTap(IBinder base, String why) {
        this.base = base;
        this.why = why == null ? "?" : why;
        String d = null;
        try {
            d = base.getInterfaceDescriptor();
        } catch (Throwable ignored) {
        }
        this.desc = d;
        Diag.log("aidl", "包装 binder " + this.why + " 原始类=" + base.getClass().getName()
                + " desc=" + d);
    }

    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        int in = data == null ? -1 : data.dataSize();
        Diag.log("aidl", "客户端 transact " + why + " code=" + code + " in=" + in
                + " flags=" + flags + " str=" + HmsAidlProbe.parcelStrings(data));
        try {
            boolean r = base.transact(code, data, reply, flags);
            Diag.log("aidl", "客户端 transact 返回 " + why + " code=" + code
                    + " result=" + r + " out=" + (reply == null ? -1 : reply.dataSize()));
            return r;
        } catch (RemoteException e) {
            Diag.log("aidl", "客户端 transact 异常 " + why + " code=" + code + " ERR=" + e);
            throw e;
        } catch (RuntimeException e) {
            Diag.log("aidl", "客户端 transact 异常 " + why + " code=" + code + " ERR=" + e);
            throw e;
        }
    }

    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return base.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return base.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return base.isBinderAlive();
    }

    @Override
    public IInterface queryLocalInterface(String descriptor) {
        return base.queryLocalInterface(descriptor);
    }

    @Override
    public void dump(FileDescriptor fd, String[] args) throws RemoteException {
        base.dump(fd, args);
    }

    @Override
    public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
        base.dumpAsync(fd, args);
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        base.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return base.unlinkToDeath(recipient, flags);
    }
}
