package com.hush.fake.service;

import java.lang.reflect.Method;
import java.util.Collections;
import com.hush.env.android.content.integrity.BRIAppIntegrityManager;
import com.hush.env.android.content.integrity.BRIAppIntegrityManagerStub;
import com.hush.env.android.os.BRServiceManager;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.utils.compat.ParceledListSliceCompat;

/**
 * @author gm
 * @function
 * @date :2024/4/23 16:13
 **/
public class IAppIntegrityManagerProxy extends BinderInvocationStub {
    private static final String SERVER_NAME = "app_integrity";

    public IAppIntegrityManagerProxy() {
        super(BRServiceManager.get().getService(SERVER_NAME));
    }

    @Override
    protected Object getWho() {
        return BRIAppIntegrityManagerStub.get().asInterface(BRServiceManager.get().getService("alarm"));
    }

    @Override
    protected void inject(Object base, Object proxy) {
        replaceSystemService(SERVER_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
    
    @ProxyMethod("getCurrentRuleSetProvider")
    public static class GetCurrentRuleSetProvider extends MethodHook {
        @Override
        protected Object hook(Object proxy, Method method, Object[] args) throws Throwable {
            return ""; // spoof empty provider name
        }
    }

    @ProxyMethod("getCurrentRuleSetVersion")
    public static class GetCurrentRuleSetVersion extends MethodHook {
        @Override
        protected Object hook(Object proxy, Method method, Object[] args) throws Throwable {
            return ""; // spoof empty version
        }
    }

    @ProxyMethod("getCurrentRules")
    public static class GetCurrentRules extends MethodHook {
        @Override
        protected Object hook(Object proxy, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(Collections.emptyList()); // spoof empty rules
        }
    }

    @ProxyMethod("getWhitelistedRuleProviders")
    public static class GetWhitelistedRuleProviders extends MethodHook {
        @Override
        protected Object hook(Object proxy, Method method, Object[] args) throws Throwable {
            return Collections.emptyList(); // spoof empty whitelist
        }
    }

    @ProxyMethod("updateRuleSet")
    public static class UpdateRuleSet extends MethodHook {
        @Override
        protected Object hook(Object proxy, Method method, Object[] args) throws Throwable {
            return null; // ignore updates
        }
    }
}

