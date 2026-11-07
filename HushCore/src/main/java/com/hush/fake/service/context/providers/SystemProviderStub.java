package com.hush.fake.service.context.providers;

import android.os.IInterface;

import java.lang.reflect.Method;

import com.hush.env.android.content.BRAttributionSource;
import com.hush.HushCore;
import com.hush.fake.hook.ClassInvocationStub;
import com.hush.utils.compat.ContextCompat;

/**
 * Created by @officialakshit01 on 4/8/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class SystemProviderStub extends ClassInvocationStub implements BContentProvider {
	private IInterface mBase;

	@Override
	public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
		mBase = contentProviderProxy;
		injectHook();
		return (IInterface) getProxyInvocation();
	}

	@Override
	protected Object getWho() {
		return mBase;
	}

	@Override
	protected void inject(Object baseInvocation, Object proxyInvocation) {
	}

	@Override
	protected void onBindMethod() {
	}

	@Override
	public boolean isBadEnv() {
		return false;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if ("asBinder".equals(method.getName())) {
			return method.invoke(mBase, args);
		}
		if (args != null && args.length > 0) {
			if (args[0] instanceof String) {
				args[0] = HushCore.getHostPkg();
			}
			for (Object arg : args) {
				if (arg != null && arg.getClass().getName().equals(BRAttributionSource.getRealClass().getName())) {
					ContextCompat.fixAttributionSourceState(arg, HushCore.getHostUid());
				}
			}
		}

		if ("call".equals(method.getName()) && args != null) {
			// Settings.Secure.getString(ctx, "android_id") → call(hostPkg, "GET_secure", "android_id", bundle)
			// arg[0] already replaced above; scan from index 1 for "android_id".
			boolean isAndroidId = false;
			for (int i = 1; i < args.length; i++) {
				if ("android_id".equals(args[i])) { isAndroidId = true; break; }
			}
			if (isAndroidId) {
				android.os.Bundle b = new android.os.Bundle();
				b.putString("value", "6e42b8e3a2b1c4d5");
				return b;
			}
		}

		if ("query".equals(method.getName()) && args != null) {
			boolean isAndroidId = false;
			for (Object arg : args) {
				if (arg instanceof String[]) {
					for (String s : (String[]) arg) {
						if ("android_id".equals(s)) { isAndroidId = true; break; }
					}
				} else if ("android_id".equals(arg)) {
					isAndroidId = true;
				}
			}
			if (isAndroidId) {
				android.database.MatrixCursor cursor = new android.database.MatrixCursor(new String[]{"name", "value"});
				cursor.addRow(new Object[]{"android_id", "6e42b8e3a2b1c4d5"});
				return cursor;
			}
		}

		try {
			return method.invoke(mBase, args);
		} catch (java.lang.reflect.InvocationTargetException ite) {
			Throwable cause = ite.getCause();
			// The system Settings provider enforces READ_DEVICE_CONFIG server-side
			// and throws a SecurityException that arrives via Parcel deserialization.
			// GservicesProvider.onCreate() calls DeviceConfig.getBoolean() which calls
			// Settings$Config.getStrings() → NameValueCache.getStringsForPrefix() → call().
			// Returning an empty Bundle here makes DeviceConfig.getProperty() return null,
			// so GservicesStorageState.isGmsCoreStorageEnabled() returns false and the
			// provider falls back to the legacy storage path without crashing.
			if (isDeviceConfigPermissionDenial(cause)) {
				android.util.Log.d("SystemProviderStub",
						"Suppressed READ_DEVICE_CONFIG denial for GservicesProvider init");
				android.os.Bundle b = new android.os.Bundle();
				b.putSerializable("value", new java.util.HashMap<String, String>());
				return b;
			}
			// For any other invocation exception, unwrap and rethrow the real cause
			throw (cause != null ? cause : ite);
		}
	}

	/**
	 * Returns true when the throwable chain contains a SecurityException
	 * specifically for READ_DEVICE_CONFIG (which comes back from the system
	 * server over a Parcel as a SecurityException inside a DatabaseException
	 * or directly).
	 */
	private static boolean isDeviceConfigPermissionDenial(Throwable t) {
		Throwable cur = t;
		int depth = 0;
		while (cur != null && depth < 6) {
			if (cur instanceof SecurityException) {
				String msg = cur.getMessage();
				if (msg != null && (msg.contains("READ_DEVICE_CONFIG")
						|| msg.contains("reading from settings"))) {
					return true;
				}
			}
			cur = cur.getCause();
			depth++;
		}
		return false;
	}
}
