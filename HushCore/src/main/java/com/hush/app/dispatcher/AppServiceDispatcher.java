package com.hush.app.dispatcher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.entity.ServiceRecord;
import com.hush.entity.UnbindRecord;
import com.hush.proxy.record.ProxyServiceRecord;

import static android.app.Service.START_NOT_STICKY;

/**
 * Created by @officialakshit01 on 4/1/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class AppServiceDispatcher {
    private static final AppServiceDispatcher sServiceDispatcher = new AppServiceDispatcher();
    private final Map<Intent.FilterComparison, ServiceRecord> mService = new HashMap<>();
    private final Handler mHandler = HushCore.get().getHandler();

    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    public IBinder onBind(Intent proxyIntent) {
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);
        Intent intent = serviceRecord.mServiceIntent;
        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;

        if (intent == null || serviceInfo == null) {
            return null;
        }

        Service service = getOrCreateService(serviceRecord);
        if (service == null) {
            return null;
        }
        intent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(intent);
        record.incrementAndGetBindCount(intent);

        if (record.hasBinder(intent)) {
            if (record.isRebind()) {
                service.onRebind(intent);
                record.setRebind(false);
            }
            return record.getBinder(intent);
        }

        try {
            IBinder iBinder = service.onBind(intent);
            record.addBinder(intent, iBinder);
            // 2.6.74：把服务端真正返回的 binder 挂上 onTransact，确认 SDK 的请求
            // 到底有没有被 HMS 处理（2.6.73 只证明了客户端发出了 code=2 的请求）。
            com.hush.utils.HmsAidlProbe.tapServerBinder(iBinder,
                    serviceInfo.packageName + "/" + serviceInfo.name);
            return iBinder;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public void onStartCommand(Intent proxyIntent) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return;
        }

        Service service = getOrCreateService(stubRecord);
        if (service == null) {
            return;
        }
        stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(stubRecord.mServiceIntent);
        record.setStartId(stubRecord.mStartId);
    }

    public void onDestroy() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onDestroy();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        mService.clear();
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onConfigurationChanged(newConfig);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void onLowMemory() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onLowMemory();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void onTrimMemory(int level) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onTrimMemory(level);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void onUnbind(Intent proxyIntent) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return;
        }

        Intent intent = stubRecord.mServiceIntent;
        try {
            UnbindRecord unbindRecord = HushCore.getBActivityManager().onServiceUnbind(proxyIntent,
                    BActivityThread.getUserId());
            if (unbindRecord == null) {
                return;
            }

            Service service = getOrCreateService(stubRecord);
            if (service == null) {
                return;
            }
            stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());

            ServiceRecord record = findRecord(intent);
            boolean destroy = unbindRecord.getStartId() == 0;

            if (destroy || record.decreaseConnectionCount(intent)) {
                if (destroy) {
                    service.onDestroy();

                    HushCore.getBActivityManager().onServiceDestroy(proxyIntent, BActivityThread.getUserId());
                    mService.remove(new Intent.FilterComparison(intent));
                }
                record.setRebind(true);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public IBinder peekService(Intent intent) {
        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return null;
        }
        return record.getBinder(intent);
    }

    public void stopService(Intent intent) {
        if (intent == null) {
            return;
        }

        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return;
        }

        if (record.getService() != null) {
            try {
                record.getService().onDestroy();
                HushCore.getBActivityManager().onServiceDestroy(intent, BActivityThread.getUserId());
                mService.remove(new Intent.FilterComparison(intent));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private ServiceRecord findRecord(Intent intent) {
        return mService.get(new Intent.FilterComparison(intent));
    }

    private Service getOrCreateService(ProxyServiceRecord proxyServiceRecord) {
        Intent intent = proxyServiceRecord.mServiceIntent;
        ServiceInfo serviceInfo = proxyServiceRecord.mServiceInfo;
        IBinder token = proxyServiceRecord.mToken;

        ServiceRecord record = findRecord(intent);
        if (record != null && record.getService() != null) {
            return record.getService();
        }

        Service service = BActivityThread.currentActivityThread().createService(serviceInfo, token);
        if (service == null) {
            return null;
        }

        record = new ServiceRecord();
        record.setService(service);
        mService.put(new Intent.FilterComparison(intent), record);
        return service;
    }
}
