package com.hush.fake.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ScanClass;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.hush.env.android.net.BRIConnectivityManagerStub;
import com.hush.env.android.os.BRServiceManager;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.ProxyMethod;
import com.hush.utils.Slog;

@ScanClass(VpnCommonProxy.class)
public class IConnectivityManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IConnectivityManagerProxy";

    public IConnectivityManagerProxy() {
        super(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    /**
     * 把「查询方的包名」统一换成宿主包名。
     *
     * <p>HMS 的 kit 页面跑在虚拟 Activity（KitContext）里，它拿到的
     * {@link ConnectivityManager} 绑的是 kit 自己的 ContextImpl，包名是
     * {@code com.huawei.hms.account} 这类虚拟包。真机上这个包和调用 uid
     * 是配对的；容器里所有 guest 共用宿主 uid，system_server 的
     * {@code AppOpsManager.checkPackage(uid, pkg)} 直接抛 SecurityException
     * 或返回 null，HMS 就会把「查不到」当成「没有网络」。</p>
     *
     * <p>Activity / Application 的 Context 已经在 ContextCompat.fix 里改成了
     * 宿主包名，但 KitContext 是 HMS 自己 new 出来的，漏在外面。这条代理在
     * binder 出口处统一收口：凡是把华为/荣耀系虚拟包名传给
     * IConnectivityManager 的，都换成宿主包名，和内核 uid 对齐。</p>
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            String host = HushCore.getHostPkg();
            if (host != null && args != null && args.length > 0) {
                String guest = BActivityThread.getAppPackageName();
                for (int i = 0; i < args.length; i++) {
                    Object v = args[i];
                    if (!(v instanceof String)) continue;
                    String s = (String) v;
                    boolean hms = s.startsWith("com.huawei.hwid")
                            || s.startsWith("com.huawei.hms.")
                            || s.startsWith("com.hihonor.");
                    if (hms || (guest != null && guest.equals(s))) {
                        args[i] = host;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 改包名失败也不能影响正常连通性查询，原样放行
        }
        return super.invoke(proxy, method, args);
    }

    @Override
    protected Object getWho() {
        return BRIConnectivityManagerStub.get().asInterface(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}

