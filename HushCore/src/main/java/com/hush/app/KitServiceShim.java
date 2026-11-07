package com.hush.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 2.6.59：HMS runtimekit「虚拟服务」的真实 Service 壳。
 *
 * <p>HMS 账号 kit 里的 {@code HwidInnerService}、{@code HwAccMgrService}
 * 都继承 {@code SafeService -> KitService -> KitContext -> Context}，
 * 它们不是 {@code android.app.Service}，容器没法直接拿去做系统服务，
 * 之前只能返回 null。后果是：
 * <ul>
 *   <li>HMS core 登录成功后要 bind {@code HwidInnerService} 同步账号数据，
 *       拿到 {@code binder=false}；</li>
 *   <li>{@code HwidInnerServiceClient} 有一个约 5 秒的绑定超时
 *       （{@code DELAY_TIME / MSG_BINDSERVICE_TIMEOUT}），超时后回调
 *       {@code onBindFailed}，登录流程随即被判失败，页面退回登录页；</li>
 *   <li>用户看到的就是「输完验证码登录成功，转几秒又让你重新登录」。</li>
 * </ul>
 *
 * <p>HwidInnerService 的 {@code onBind()} 其实只是
 * {@code return this.innerBinder}，而这个 Binder 在构造函数里就创建好了
 * （{@code HwidInnerAIDLInvoke}）。所以只要把它的生命周期托管到一个
 * 真正的 Service 上，bind 就能立刻拿到可用的 Binder，不再触发超时。
 */
public class KitServiceShim extends Service {

    private Object mImpl;
    private Class<?> mCls;

    public static KitServiceShim wrap(Object impl, Class<?> cls) {
        try {
            KitServiceShim s = new KitServiceShim();
            s.mImpl = impl;
            s.mCls = cls;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object call(String name, Class<?>[] types, Object... args) {
        if (mImpl == null || mCls == null) return null;
        try {
            java.lang.reflect.Method m = types == null
                    ? mCls.getMethod(name)
                    : mCls.getMethod(name, types);
            return m.invoke(mImpl, args);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // SafeService 自己会吞异常；这里再兜一层，kit 服务初始化失败也不能
        // 把宿主进程带走。
        call("onCreate", null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Object r = call("onBind", new Class<?>[]{Intent.class}, intent);
        return r instanceof IBinder ? (IBinder) r : null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Object r = call("onUnbind", new Class<?>[]{Intent.class}, intent);
        return r instanceof Boolean ? (Boolean) r : false;
    }

    @Override
    public void onRebind(Intent intent) {
        call("onRebind", new Class<?>[]{Intent.class}, intent);
    }

    @Override
    public void onDestroy() {
        call("onDestroy", null);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Object r = call("onStartCommand",
                new Class<?>[]{Intent.class, int.class, int.class}, intent, flags, startId);
        return r instanceof Integer ? (Integer) r : START_NOT_STICKY;
    }
}
