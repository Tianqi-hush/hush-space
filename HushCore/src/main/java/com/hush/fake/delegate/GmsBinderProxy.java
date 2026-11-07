package com.hush.fake.delegate;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.hush.HushCore;
import com.hush.app.BActivityThread;

public class GmsBinderProxy implements IBinder {
    private final IBinder mBase;

    public GmsBinderProxy(IBinder base) {
        this.mBase = base;
    }

    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return mBase.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return mBase.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return mBase.isBinderAlive();
    }

    @Override
    public IInterface queryLocalInterface(String descriptor) {
        return mBase.queryLocalInterface(descriptor);
    }

    @Override
    public void dump(java.io.FileDescriptor fd, String[] args) throws RemoteException {
        mBase.dump(fd, args);
    }

    @Override
    public void dumpAsync(java.io.FileDescriptor fd, String[] args) throws RemoteException {
        mBase.dumpAsync(fd, args);
    }

    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Parcel targetData = data;
        boolean needsRecycle = false;
        try {
            String descriptor = getInterfaceDescriptor();
            if (descriptor != null && descriptor.startsWith("com.google.android.gms") && descriptor.endsWith("ServiceBroker")) {
                if (code == 46 || code == 1) { // getService
                    targetData = modifyGmsRequest(data);
                    needsRecycle = true;
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("GmsBinderProxy", "Error modifying GMS request", t);
        }

        try {
            return mBase.transact(code, targetData, reply, flags);
        } finally {
            if (needsRecycle && targetData != null) {
                targetData.recycle();
            }
        }
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        mBase.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return mBase.unlinkToDeath(recipient, flags);
    }

    private Parcel modifyGmsRequest(Parcel data) {
        String guestPackage = BActivityThread.getAppPackageName();
        String hostPackage = HushCore.getHostPkg();
        if (guestPackage == null || hostPackage == null) {
            return data;
        }

        data.setDataPosition(0);

        String interfaceToken;
        IBinder callbacks;
        int hasRequest;
        try {
            interfaceToken = getInterfaceDescriptor();
            data.enforceInterface(interfaceToken);
            callbacks = data.readStrongBinder();
            hasRequest = data.readInt();
        } catch (Throwable e) {
            data.setDataPosition(0);
            return data;
        }

        Parcel newData = Parcel.obtain();
        newData.writeInterfaceToken(interfaceToken);
        newData.writeStrongBinder(callbacks);
        newData.writeInt(hasRequest);

        if (hasRequest == 1) {
            try {
                int objectHeader = data.readInt();
                int objectSize = ((objectHeader >> 16) & 0xffff);
                if (objectSize == 0xffff) {
                    objectSize = data.readInt();
                }
                int objectEnd = data.dataPosition() + objectSize;

                int newObjectHeaderPos = newData.dataPosition();
                newData.writeInt(objectHeader);
                if (objectSize == 0xffff) {
                    newData.writeInt(0);
                }

                while (data.dataPosition() < objectEnd) {
                    int header = data.readInt();
                    int fieldId = header & 0xffff;
                    int size = ((header >> 16) & 0xffff);
                    boolean isLarge = false;
                    if (size == 0xffff) {
                        size = data.readInt();
                        isLarge = true;
                    }

                    int nextFieldPos = data.dataPosition() + size;

                    if (fieldId == 4) {
                        String callingPackage = data.readString();
                        String finalPkg = callingPackage;
                        if (guestPackage.equals(callingPackage)) {
                            finalPkg = hostPackage;
                        }

                        int newFieldStartPos = newData.dataPosition();
                        newData.writeInt(header);
                        if (isLarge) {
                            newData.writeInt(0);
                        }

                        int newFieldDataStart = newData.dataPosition();
                        newData.writeString(finalPkg);
                        int newFieldDataEnd = newData.dataPosition();
                        int newFieldSize = newFieldDataEnd - newFieldDataStart;

                        int currentPos = newData.dataPosition();
                        newData.setDataPosition(newFieldStartPos);
                        if (isLarge) {
                            newData.writeInt((0xffff0000) | fieldId);
                            newData.writeInt(newFieldSize);
                        } else {
                            if (newFieldSize < 0xffff) {
                                newData.writeInt((newFieldSize << 16) | fieldId);
                            } else {
                                newData.writeInt((0xffff0000) | fieldId);
                                newData.writeInt(newFieldSize);
                            }
                        }
                        newData.setDataPosition(currentPos);
                    } else {
                        newData.writeInt(header);
                        if (isLarge) {
                            newData.writeInt(size);
                        }
                        newData.appendFrom(data, data.dataPosition(), size);
                    }

                    data.setDataPosition(nextFieldPos);
                }

                int finalPos = newData.dataPosition();
                int newObjectSize = finalPos - (newObjectHeaderPos + 4);
                if (objectSize == 0xffff) {
                    newObjectSize = finalPos - (newObjectHeaderPos + 8);
                }
                newData.setDataPosition(newObjectHeaderPos);
                if (objectSize == 0xffff) {
                    newData.writeInt((0xffff0000) | (objectHeader & 0xffff));
                    newData.writeInt(newObjectSize);
                } else {
                    newData.writeInt((newObjectSize << 16) | (objectHeader & 0xffff));
                }
                newData.setDataPosition(finalPos);

            } catch (Throwable e) {
                newData.recycle();
                data.setDataPosition(0);
                return data;
            }
        }

        if (data.dataPosition() < data.dataSize()) {
            newData.appendFrom(data, data.dataPosition(), data.dataSize() - data.dataPosition());
        }

        newData.setDataPosition(0);
        return newData;
    }
}
