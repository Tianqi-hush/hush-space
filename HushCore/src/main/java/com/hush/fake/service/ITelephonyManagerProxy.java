package com.hush.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import com.hush.env.android.os.BRServiceManager;
import com.hush.env.com.android.internal.telephony.BRITelephonyStub;
import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.entity.location.BCell;
import com.hush.fake.frameworks.BLocationManager;
import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.utils.Md5Utils;

public class ITelephonyManagerProxy extends BinderInvocationStub {
	public static final String TAG = "ITelephonyManagerProxy";

	public ITelephonyManagerProxy() {
		super(BRServiceManager.get().getService(Context.TELEPHONY_SERVICE));
	}

	@Override
	protected Object getWho() {
		IBinder telephony = BRServiceManager.get().getService(Context.TELEPHONY_SERVICE);
		return BRITelephonyStub.get().asInterface(telephony);
	}

	@Override
	protected void inject(Object baseInvocation, Object proxyInvocation) {
		replaceSystemService(Context.TELEPHONY_SERVICE);
	}

	@Override
	public boolean isBadEnv() {
		return false;
	}

	@ProxyMethod("getDeviceId")
	public static class GetDeviceId extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.md5(HushCore.getHostPkg());
		}
	}

	@ProxyMethod("getImeiForSlot")
	public static class getImeiForSlot extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.md5(HushCore.getHostPkg());
		}
	}

	@ProxyMethod("getMeidForSlot")
	public static class GetMeidForSlot extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.md5(HushCore.getHostPkg());
		}
	}

	@ProxyMethod("isUserDataEnabled")
	public static class IsUserDataEnabled extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return true;
		}
	}

	@ProxyMethod("getLine1NumberForDisplay")
	public static class getLine1NumberForDisplay extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return null;
		}
	}

	@ProxyMethod("getSubscriberId")
	public static class GetSubscriberId extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.md5(HushCore.getHostPkg());
		}
	}

	@ProxyMethod("getDeviceIdWithFeature")
	public static class GetDeviceIdWithFeature extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.md5(HushCore.getHostPkg());
		}
	}

	@ProxyMethod("getCellLocation")
	public static class GetCellLocation extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			Log.d(TAG, "getCellLocation");
			if (BLocationManager.isFakeLocationEnable()) {
				BCell cell = BLocationManager.get().getCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
				if (cell != null) {
					// TODO Transfer BCell to CdmaCellLocation/GsmCellLocation
					return null;
				}
			}
			return method.invoke(who, args);
		}
	}

	@ProxyMethod("getAllCellInfo")
	public static class GetAllCellInfo extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			if (BLocationManager.isFakeLocationEnable()) {
				List<BCell> cell = BLocationManager.get().getAllCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
				// TODO Transfer BCell to CdmaCellLocation/GsmCellLocation
				return cell;
			}
			try {
				return method.invoke(who, args);
			} catch (Throwable e) {
				return null;
			}
		}
	}

	@ProxyMethod("getNetworkOperator")
	public static class GetNetworkOperator extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			Log.d(TAG, "getNetworkOperator");
			return method.invoke(who, args);
		}
	}

	@ProxyMethod("getNetworkTypeForSubscriber")
	public static class GetNetworkTypeForSubscriber extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			try {
				return method.invoke(who, args);
			} catch (Throwable e) {
				return 0;
			}
		}
	}

	@ProxyMethod("getNeighboringCellInfo")
	public static class GetNeighboringCellInfo extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			Log.d(TAG, "getNeighboringCellInfo");
			if (BLocationManager.isFakeLocationEnable()) {
				List<BCell> cell = BLocationManager.get().getNeighboringCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
				// TODO Transfer BCell to CdmaCellLocation/GsmCellLocation
				return null;
			}
			return method.invoke(who, args);
		}
	}
}
