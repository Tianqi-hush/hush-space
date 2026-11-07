package com.hush.utils.compat;

import android.content.ContentProvider;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Binder;

import com.hush.env.android.app.BRContextImpl;
import com.hush.env.android.app.BRContextImplKitkat;
import com.hush.env.android.content.AttributionSourceStateContext;
import com.hush.env.android.content.BRAttributionSource;
import com.hush.env.android.content.BRAttributionSourceState;
import com.hush.env.android.content.BRContentResolver;
import com.hush.HushCore;
import com.hush.app.BActivityThread;
/**
 * Created by @jagdish_via on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ContextCompat {
	public static final String TAG = "ContextCompat";

	public static void fixAttributionSourceState(Object obj, int uid) {
		fixAttributionSourceState(obj, uid, 0);
	}

	public static void fixAttributionSourceState(Object obj, int uid, int depth) {
		if (depth >= 10) return;
		if (obj != null && BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
			Object mAttributionSourceState = BRAttributionSource.get(obj).mAttributionSourceState();
			AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(mAttributionSourceState);
			attributionSourceStateContext._set_packageName(HushCore.getHostPkg());
			attributionSourceStateContext._set_uid(uid);
			fixAttributionSourceState(BRAttributionSource.get(obj).getNext(), uid, depth + 1);
		}
	}

	public static void fix(Context context) {
        if (context == null) return;
		try {
			int deep = 0;
			while (context instanceof ContextWrapper) {
				context = ((ContextWrapper) context).getBaseContext();
				deep++;
				if (deep >= 10) {
					return;
				}
			}
			BRContextImpl.get(context)._set_mPackageManager(null);
			try {
				context.getPackageManager();
			} catch (Throwable e) {
				e.printStackTrace();
			}

			BRContextImpl.get(context)._set_mBasePackageName(HushCore.getHostPkg());
			BRContextImplKitkat.get(context)._set_mOpPackageName(HushCore.getHostPkg());
			BRContentResolver.get(context.getContentResolver())._set_mPackageName(HushCore.getHostPkg());

			if (BuildCompat.isS()) {
				// 这里必须用宿主的真实 uid，不能用 guest 的虚拟 uid。
				//
				// 进程内所有 Binder 事务的内核 uid 都是宿主 uid（所有 guest 共用一个
				// Linux 进程身份），而 AttributionSource 会带着这里的值跨进程传出去。
				// Android 12+ 的 AttributionSource.enforceCallingUid()、
				// ContentProvider.getCallingAttributionSource() 里的
				// checkPackage(uid, packageName) 都要求「声明 uid == 真实调用 uid」，
				// 声明成 10001、内核给 10442 时会直接抛
				//   SecurityException: Calling uid: 10442 doesn't match source uid: 10001
				// HMS 的 :core 进程因此每秒崩一次，kit 容器服务永远起不来。
				//
				// 另外 l.62/63 已经把包名统一成宿主包名，uid 也只有用宿主 uid
				// 才和它配对（checkPackage 才过得去）。
				fixAttributionSourceState(BRContextImpl.get(context).getAttributionSource(), HushCore.getHostUid());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
