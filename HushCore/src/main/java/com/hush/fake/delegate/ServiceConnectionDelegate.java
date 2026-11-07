package com.hush.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.hush.env.android.app.BRIServiceConnectionO;
import com.hush.utils.compat.BuildCompat;

/**
 * 包一层 IServiceConnection，把系统回调里的 ComponentName 换回 guest 自己的组件。
 *
 * 2026-11-01 14:48:16.638 在 SDK 37 上的崩溃（高德 bindService 时进程直接死）：
 *
 *   java.lang.AbstractMethodError: abstract method
 *     "void android.app.IServiceConnection.connected(ComponentName, IBinder,
 *      android.app.IBinderSession, boolean)"
 *     on receiver java.lang.Class<com.hush.fake.delegate.ServiceConnectionDelegate>
 *       at android.app.IServiceConnection$Stub.onTransact(IServiceConnection.java:90)
 *
 * 原因很明确：SDK 37 给 connected 加了第四个参数 IBinderSession，AIDL 事务码没变（还是 1），
 * 而我们是拿 android-34 的 Stub 编译的。运行时 Stub.onTransact 派发到那个 4 参抽象方法，
 * 我们的类里没有对应实现 —— 这不是普通异常，AbstractMethodError 直接把进程带走。
 *
 * 所以这里不再依赖 Stub.onTransact 的派发：自己接管 onTransact，按 AIDL 线上格式解包
 * （ComponentName 用 writeTypedObject 写、dead 永远是最后一个 int），再按运行时真实签名
 * 反射回调 guest 自己的连接对象。解析不出来就整包原样转发，无论如何都不让异常逃出去。
 */
public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final String TAG = "HushSCDelegate";
    private static final String DESCRIPTOR = "android.app.IServiceConnection";
    /** AIDL: oneway void connected(in ComponentName, in IBinder, [in IBinderSession,] boolean) */
    private static final int TRANSACTION_connected = 1;

    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != TRANSACTION_connected || data == null) {
            return super.onTransact(code, data, reply, flags);
        }
        Parcel in = Parcel.obtain();
        try {
            in.appendFrom(data, 0, data.dataSize());
            in.setDataPosition(0);
            if (readConnected(in)) {
                if (reply != null) {
                    try {
                        reply.writeNoException();
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse connected failed: " + t);
        } finally {
            in.recycle();
        }
        // 解不出来就整包原样转给 guest 自己的连接对象：宁可少一次组件名纠正，也不能崩。
        Parcel raw = Parcel.obtain();
        try {
            raw.appendFrom(data, 0, data.dataSize());
            raw.setDataPosition(0);
            mConn.asBinder().transact(code, raw, reply, flags);
        } catch (Throwable t) {
            Log.w(TAG, "forward connected failed: " + t);
        } finally {
            raw.recycle();
        }
        return true;
    }

    private boolean readConnected(Parcel in) {
        in.enforceInterface(DESCRIPTOR);
        int mark = in.dataPosition();

        ComponentName name = null;
        try {
            // API 30+ 的 AIDL 对 in Parcelable 参数写的是 [presence int][parcelable]
            name = in.readTypedObject(ComponentName.CREATOR);
        } catch (Throwable ignored) {
        }
        if (name == null) {
            // 老格式：没有 presence int，直接就是 ComponentName 本体
            in.setDataPosition(mark);
            try {
                name = ComponentName.CREATOR.createFromParcel(in);
            } catch (Throwable ignored) {
            }
        }
        if (name == null) {
            return false;
        }

        IBinder service = null;
        try {
            service = in.readStrongBinder();
        } catch (Throwable ignored) {
        }

        // 2.6.73：SDK 37 在 ComponentName 和 IBinder 之后还塞了一个
        // android.app.IBinderSession，再后面才是 dead。之前的实现把它跳过了，
        // 但回调到 guest 的 4 参 connected 时给 null；如果框架内部要用它，
        // 回调就会失败。这里尽力把它原样读出来一起传下去（读失败才退回 null）。
        int end = in.dataSize();
        Object session = null;
        if (end - in.dataPosition() > 4) {
            try {
                Class<?> sessionCls = Class.forName("android.app.IBinderSession");
                Object creator = sessionCls.getField("CREATOR").get(null);
                session = in.readTypedObject((android.os.Parcelable.Creator) creator);
            } catch (Throwable ignored) {
            }
        }
        boolean dead = false;
        if (end - in.dataPosition() >= 4) {
            in.setDataPosition(end - 4);
            try {
                dead = in.readInt() != 0;
            } catch (Throwable ignored) {
            }
        } else {
            in.setDataPosition(end);
        }

        try {
            connected(name, service, dead, session);
        } catch (Throwable t) {
            Log.w(TAG, "dispatch connected failed: " + t);
        }
        return true;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    private void connected(ComponentName name, IBinder service, boolean dead, Object session)
            throws RemoteException {
        this.mSession = session;
        connected(name, service, dead);
    }

    /** SDK 37 的 IBinderSession，随 connected 一起透传给 guest。 */
    private Object mSession;

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        try {
            com.hush.utils.Diag.log("svc", "onServiceConnected " + name
                    + " binder=" + (service != null) + " dead=" + dead);
        } catch (Throwable ignored) {
        }
        if (service != null && name != null) {
            String pkgName = name.getPackageName();
            if ("com.google.android.gms".equals(pkgName) || "com.google.android.gsf".equals(pkgName)) {
                service = new GmsBinderProxy(service);
            }
        }
        ComponentName targetName = mComponentName != null ? mComponentName : name;
        // 2.6.69：华为渠道 SDK 连 HMS Core 的 binder 一事务都不成功，观察它拿到的
        // 那一个 binder 上的所有调用（见 HmsAidlProbe）。
        // 2.6.70：这里回调出来的 name 是宿主代理组件（com.hush.space/ProxyService$Px），
        // 不是 guest 组件，必须用 mComponentName 判断，否则永远匹配不上。
        // 2.6.71：BinderProxy.transact 是 native 方法，hook 不生效（2.6.69/70 一条
         // 记录都没有）。改为在 Java 层包一层 IBinder，SDK 的每一次事务都会经过
         // HmsBinderTap.transact，记录完再原样转发。
        if (service != null && targetName != null
                && "com.huawei.hwid".equals(targetName.getPackageName())) {
            com.hush.utils.HmsAidlProbe.watch(service, targetName.flattenToShortString());
            service = new com.hush.utils.HmsBinderTap(service, targetName.flattenToShortString());
        }
        if (dispatch(targetName, service, dead)) {
            return;
        }
        try {
            mConn.connected(name, service);
        } catch (Throwable t) {
            com.hush.utils.Diag.log("svc", "connected 兜底失败 " + t);
            Log.w(TAG, "connected fallback failed: " + t);
        }
    }

    /**
     * 按运行时真实签名回调，不写死任何一种：
     *   3 参 = Android 8 ~ 36；4 参 = SDK 37 起（多一个 IBinderSession，这里给 null）；
     *   2 参 = 最老的版本。
     */
    private boolean dispatch(ComponentName name, IBinder service, boolean dead) {
        // 2.6.73：2.6.72 的日志证明这里是个假成功 —— 每次都是
        //   "dispatch ok 3参 ... service=com.hush.utils.HmsBinderTap"
        // 但 SDK 的 ServiceConnection 一次都没被调用（InnerConnection 探针 0 命中），
        // 于是 SDK 拿不到 binder，10 秒超时重绑。
        // 原因是 BRIServiceConnectionO 的黑反射按 3 参签名查找方法：SDK 37 上
        // InnerConnection 只有 connected(ComponentName, IBinder, IBinderSession, boolean)
        // 这一个方法，查不到时黑反射安静地什么都不做（不抛异常），于是被当成成功。
        // 这里改成先枚举真实方法，按 4 参 → 3 参 → 2 参 的顺序真正 invoke。
        Method m4 = null, m3 = null, m2 = null;
        for (Method m : mConn.getClass().getMethods()) {
            if (!"connected".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[0] == ComponentName.class && p[1] == IBinder.class) {
                m4 = m;
            } else if (p.length == 3 && p[0] == ComponentName.class && p[1] == IBinder.class) {
                m3 = m;
            } else if (p.length == 2 && p[0] == ComponentName.class && p[1] == IBinder.class) {
                m2 = m;
            }
        }
        com.hush.utils.Diag.log("svc", "dispatch 可用签名 4参=" + (m4 != null)
                + " 3参=" + (m3 != null) + " 2参=" + (m2 != null)
                + " session=" + (mSession == null ? "null" : mSession.getClass().getName())
                + " conn=" + mConn.getClass().getName());
        if (m4 != null) {
            try {
                m4.invoke(mConn, name, service, mSession, dead);
                com.hush.utils.Diag.log("svc", "dispatch ok 4参 " + m4 + " name=" + name);
                return true;
            } catch (Throwable t) {
                com.hush.utils.Diag.log("svc", "dispatch 4参失败 " + t
                        + (t.getCause() == null ? "" : " cause=" + t.getCause()));
            }
        }
        if (m3 != null) {
            try {
                m3.invoke(mConn, name, service, dead);
                com.hush.utils.Diag.log("svc", "dispatch ok 3参 " + m3 + " name=" + name);
                return true;
            } catch (Throwable t) {
                com.hush.utils.Diag.log("svc", "dispatch 3参失败 " + t
                        + (t.getCause() == null ? "" : " cause=" + t.getCause()));
            }
        }
        if (m2 != null) {
            try {
                m2.invoke(mConn, name, service);
                com.hush.utils.Diag.log("svc", "dispatch ok 2参 " + m2 + " name=" + name);
                return true;
            } catch (Throwable t) {
                com.hush.utils.Diag.log("svc", "dispatch 2参失败 " + t
                        + (t.getCause() == null ? "" : " cause=" + t.getCause()));
            }
        }
        return false;
    }
}
