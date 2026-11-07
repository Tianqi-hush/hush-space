package com.hush.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hush.env.android.app.ActivityThreadActivityClientRecordContext;
import com.hush.env.android.app.BRActivityClient;
import com.hush.env.android.app.BRActivityClientActivityClientControllerSingleton;
import com.hush.env.android.app.BRActivityManagerNative;
import com.hush.env.android.app.BRActivityThread;
import com.hush.env.android.app.BRActivityThreadActivityClientRecord;
import com.hush.env.android.app.BRActivityThreadCreateServiceData;
import com.hush.env.android.app.BRActivityThreadH;
import com.hush.env.android.app.BRIActivityManager;
import com.hush.env.android.app.servertransaction.BRClientTransaction;
import com.hush.env.android.app.servertransaction.BRLaunchActivityItem;
import com.hush.env.android.app.servertransaction.LaunchActivityItem;
import com.hush.env.android.app.servertransaction.LaunchActivityItemContext;
import com.hush.env.android.os.BRHandler;
import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.fake.hook.IInjectHook;
import com.hush.proxy.ProxyManifest;
import com.hush.proxy.record.ProxyActivityRecord;
import com.hush.utils.CallerFix;
import com.hush.utils.Slog;
import com.hush.utils.compat.BuildCompat;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class HCallbackStub implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";
    private Handler.Callback mOtherCallback;
    private final AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        Handler h = getH();
        if (h == null) return null;
        try {
            return BRHandler.get(h).mCallback();
        } catch (Throwable t) {
            return null;
        }
    }

    private Handler getH() {
        Object currentActivityThread = HushCore.mainThread();
        if (currentActivityThread == null) return null;
        try {
            return BRActivityThread.get(currentActivityThread).mH();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void injectHook() {
        Handler h = getH();
        if (h == null) return;
        try {
            mOtherCallback = getHCallback();
            if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
                mOtherCallback = null;
            }
            BRHandler.get(h)._set_mCallback(this);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }
    
    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                if (BuildCompat.isPie()) {
                    if (msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                } else {
                    if (msg.what == BRActivityThreadH.get().LAUNCH_ACTIVITY()) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                }
                if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                    return handleCreateService(msg.obj);
                }
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
                }
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }
    
    private Object getLaunchActivityItem(Object clientTransaction) {
		if (clientTransaction == null) {
			return null;
		}
		List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();
		if (mActivityCallbacks != null) {
			for (Object obj : mActivityCallbacks) {
				if (obj != null && BRLaunchActivityItem.getRealClass().isInstance(obj)) {
					return obj;
				}
			}
		}
		return null;
	}
    
    private Object getLaunchActivityItem3(Object clientTransaction) {
        // NULL CHECK ADDED
        if (clientTransaction == null) { return null; }
        List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();
        if (mActivityCallbacks != null) {
            for (Object obj : mActivityCallbacks) {
                if (obj != null && BRLaunchActivityItem.getRealClass().getName().equals(obj.getClass().getCanonicalName())) {
                    return obj;
                }
            }
            return null;
        }
        return null;
    }
    
    private static final java.util.concurrent.atomic.AtomicInteger sTxnLog =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 打一次事务里到底有哪些生命周期项（Launch/Resume/Stop/Destroy/…)。
     *
     * guest 能走到 bind DONE、也能建 Activity，但屏幕上什么都不出来时，
     * 唯一能区分「根本没 Resume」和「Resume 了但画不出来」的就是这张清单。
     *
     * 上限从 8 提到 200：事务的频率是「每次 Activity 生命周期一次」，不是高频，
     * 而判断「页面到底是自己 finish 还是进程被杀」必须看到最后一条。只记前 8 条
     * 的话，闪退前那个 Destroy 项永远在预算之外，等于没记。
     */
    private void dumpTxn(Object client) {
        if (!BuildCompat.isPie()) return;
        if (sTxnLog.getAndIncrement() >= 200) return;
        try {
            List<Object> cbs = BRClientTransaction.get(client).mActivityCallbacks();
            StringBuilder sb = new StringBuilder("txn[");
            if (cbs != null) {
                for (Object o : cbs) {
                    if (sb.length() > 4) sb.append(',');
                    sb.append(o == null ? "null" : o.getClass().getSimpleName());
                }
            }
            sb.append(']');
            com.hush.utils.Diag.log("launch", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private Boolean handleLaunchActivity(Object client) {
        if (client == null) { return false; }
        dumpTxn(client);
        Object r;
        if (BuildCompat.isPie()) {
            // ClientTransaction
            r = getLaunchActivityItem(client);
            if (r == null) { return false; }
        } else {
            // ActivityClientRecord
            r = client;
        }

        Intent intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            intent = BRLaunchActivityItem.get(r).mIntent();
            token = BRClientTransaction.get(client).mActivityToken();
        } else {
            ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
            intent = clientRecordContext.intent();
            token = clientRecordContext.token();
        }
        
        if (intent == null) return true;
        // =================================
        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            // Android 12+ 的 ActivityClientRecord 要等 LaunchActivityItem.execute()
            // 之后才建出来，改 record 永远慢一步。这里趁事务还没执行，直接写
            // LaunchActivityItem（以及它携带的 ActivityInfo）—— performLaunchActivity
            // 会把它拷进 record / Activity，getCallingPackage() 才能拿到 guest 包名。
            String callerPkg = CallerFix.callerOf(
                    stubRecord.mTarget != null ? stubRecord.mTarget : intent);
            if (callerPkg != null) {
                if (BuildCompat.isPie()) {
                    Object origInfo = null;
                    try {
                        origInfo = BRLaunchActivityItem.get(r).mInfo();
                    } catch (Throwable ignored) {
                    }
                    CallerFix.applyToLaunchItem(r, callerPkg, origInfo, activityInfo);
                } else {
                    CallerFix.applyToActivityInfo(activityInfo, callerPkg);
                }
            }
            if (BActivityThread.getAppConfig() == null) {
                HushCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);
                Intent launchIntentForPackage = HushCore.getBPackageManager().getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);
                if (launchIntentForPackage == null) {
                    // HMS Core / account kit 这类包没有 LAUNCHER activity，
                    // getLaunchIntentForPackage 返回 null。以前直接把 null 存进
                    // stub，下一轮 create() 取出 null 再 _set_mIntent(null)，
                    // Android 17 的 LaunchActivityItem.hashCode() 就对 mIntent
                    // 调 filterHashCode() 抛 NPE，整个进程在启动阶段被打死。
                    launchIntentForPackage = stubRecord.mTarget != null ? stubRecord.mTarget : intent;
                }
                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                ProxyActivityRecord.saveStub(intent,launchIntentForPackage,stubRecord.mActivityInfo,stubRecord.mActivityRecord,stubRecord.mUserId);
                if (BuildCompat.isPie()) {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(intent);
                    launchActivityItemContext._set_mInfo(activityInfo);
                } else {
                    ActivityThreadActivityClientRecordContext clientRecordContext =BRActivityThreadActivityClientRecord.get(r);
                    clientRecordContext._set_intent(intent);
                    clientRecordContext._set_activityInfo(activityInfo);
                }
                return true;
            }
            // bind
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName, activityInfo.processName);
                return true;
            }

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            applyCallingPackage(stubRecord.mTarget, token);
            HushCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);

            // mTarget 为空说明这个 stub intent 是旧的 / 被系统重写过。此时绝对
            // 不能把 null 写回 LaunchActivityItem.mIntent —— Android 13+ 的
            // LaunchActivityItem.hashCode() 会直接对 mIntent 调 filterHashCode()，
            // 抛 NPE 打死进程。保留原 intent（它本身还是能启动的 stub）。
            Intent targetIntent = stubRecord.mTarget != null ? stubRecord.mTarget : intent;

            if (BuildCompat.isS()) {
                Object record = BRActivityThread.get(HushCore.mainThread()).getLaunchingActivity(token);
                if (record != null) {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(record);
                    clientRecordContext._set_intent(targetIntent);
                    clientRecordContext._set_activityInfo(activityInfo);
                    clientRecordContext._set_packageInfo(BActivityThread.currentActivityThread().getPackageInfo());
                } else {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(targetIntent);
                    launchActivityItemContext._set_mInfo(activityInfo);
                }
                checkActivityClient();
            } else if (BuildCompat.isPie()) {
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(targetIntent);
                launchActivityItemContext._set_mInfo(activityInfo);
            } else {
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                clientRecordContext._set_intent(targetIntent);
                clientRecordContext._set_activityInfo(activityInfo);
            }
        }
        return false;
    }
    
    /**
     * 把 guest 侧记录的「真正调用方」写回 ActivityClientRecord.launchedFromPackage。
     *
     * <p>{@code Activity.getCallingPackage()} 返回的 {@code mLaunchedFromPackage}
     * 是 performLaunchActivity 从 record 里拷过去的。容器里所有 guest 共用宿主
     * 进程身份，系统只会填 {@code com.hush.space}；于是像 HMS Core ForegroundBus
     * 那种「调用方必须等于请求里的 cpPackageName」的校验必然失败，返回
     * 907135000，表现就是账号页弹「无法获取数据，请稍后再试」。
     *
     * <p>调用方包名由 {@code ActivityManagerCommonProxy.StartActivity} 写进
     * target intent 的 extra，这里取出后立刻移除，避免被 guest 看到。
     */
    private void applyCallingPackage(Intent target, IBinder token) {
        if (target == null || token == null) return;
        try {
            com.hush.utils.ActivityClientHook.install();
        } catch (Throwable ignored) {
        }
        String caller;
        try {
            caller = target.getStringExtra(ProxyActivityRecord.EXTRA_CALLER_PKG);
        } catch (Throwable t) {
            return;
        }
        if (caller == null || caller.isEmpty()) return;
        // 不在这里移除 extra：AppInstrumentation 在 onCreate 前还会用它兜底，
        // 由那边读完统一删掉。

        Object record = null;
        try {
            Object main = HushCore.mainThread();
            // Android 12+ 的启动记录在 launchingActivity 里；
            // 更低版本直接查 mActivities。
            try {
                record = BRActivityThread.get(main).getLaunchingActivity(token);
            } catch (Throwable ignored) {
            }
            if (record == null) {
                java.util.Map<IBinder, Object> map = BRActivityThread.get(main).mActivities();
                if (map != null) record = map.get(token);
            }
        } catch (Throwable ignored) {
        }
        if (record == null) {
            // Android 12+ 的 LaunchActivityItem 会在 record 建立之前就执行，
            // 这里拿不到很正常。真正的兜底在 AppInstrumentation#checkActivity。
            IActivityTaskManagerProxy.recordCaller(token, caller);
            com.hush.utils.Diag.log("caller", "record 未找到 token=" + token + " caller=" + caller);
            return;
        }
        try {
            java.lang.reflect.Field f = record.getClass().getDeclaredField("launchedFromPackage");
            f.setAccessible(true);
            f.set(record, caller);
            com.hush.utils.Diag.log("caller", "launchedFromPackage=" + caller);
        } catch (Throwable t) {
            com.hush.utils.Diag.log("caller", "写 launchedFromPackage 失败 " + t);
        }
        // 兜底给 ATM：某些 ROM 的 getCallingPackage() 会绕过 record 直接查服务。
        IActivityTaskManagerProxy.recordCaller(token, caller);
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;
            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                HushCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    private void checkActivityClient() {
        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    
    
    
}
