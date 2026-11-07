package com.hush.fake.service;

import java.lang.reflect.Method;

import com.hush.env.android.os.BRServiceManager;
import com.hush.env.com.android.internal.telephony.BRITelephonyRegistryStub;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.utils.MethodParameterUtils;

/**
 * Created by @officialakshit01 on 2021/5/17.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class ITelephonyRegistryProxy extends BinderInvocationStub {
	public ITelephonyRegistryProxy() {
		super(BRServiceManager.get().getService("telephony.registry"));
	}

	@Override
	protected Object getWho() {
		return BRITelephonyRegistryStub.get().asInterface(BRServiceManager.get().getService("telephony.registry"));
	}

	@Override
	protected void inject(Object baseInvocation, Object proxyInvocation) {
		replaceSystemService("telephony.registry");
	}

	@Override
	public boolean isBadEnv() {
		return false;
	}

	@ProxyMethod("listenForSubscriber")
	public static class ListenForSubscriber extends MethodHook {

		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			MethodParameterUtils.replaceFirstAppPkg(args);
			return method.invoke(who, args);
		}
	}

	@ProxyMethod("listen")
	public static class Listen extends MethodHook {

		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			MethodParameterUtils.replaceFirstAppPkg(args);
			return method.invoke(who, args);
		}
	}
}
