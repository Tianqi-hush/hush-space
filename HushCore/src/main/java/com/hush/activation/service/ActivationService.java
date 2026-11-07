package com.hush.activation.service;

import android.Meta.IRemoteManager;
import com.hush.activation.SdkBridge;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class ActivationService extends Service {

    private final IRemoteManager.Stub binder = new IRemoteManager.Stub() {
        @Override public void activateSdk(String k) throws RemoteException { SdkBridge.getInstance().activateSdk(k); }
        @Override public boolean getActivatedSdk() throws RemoteException  { return SdkBridge.getInstance().getActivatedSdk(); }
        @Override public String getServerMessage() throws RemoteException  { return SdkBridge.getInstance().getServerMessage(); }
        @Override public boolean getNetwork() throws RemoteException       { return SdkBridge.getInstance().getNetwork(); }
    };

    @Override
    public IBinder onBind(Intent intent) { return binder; }
}
