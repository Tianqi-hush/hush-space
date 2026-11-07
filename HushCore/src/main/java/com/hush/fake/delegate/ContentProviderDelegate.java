package com.hush.fake.delegate;

import android.net.Uri;
import android.os.Build;
import android.os.IInterface;
import android.util.ArrayMap;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import com.hush.env.android.app.BRActivityThread;
import com.hush.env.android.app.BRActivityThreadProviderClientRecordP;
import com.hush.env.android.app.BRIActivityManagerContentProviderHolder;
import com.hush.env.android.content.BRContentProviderHolderOreo;
import com.hush.env.android.providers.BRSettingsContentProviderHolder;
import com.hush.env.android.providers.BRSettingsGlobal;
import com.hush.env.android.providers.BRSettingsNameValueCache;
import com.hush.env.android.providers.BRSettingsNameValueCacheOreo;
import com.hush.env.android.providers.BRSettingsSecure;
import com.hush.env.android.providers.BRSettingsSystem;
import com.hush.HushCore;
import com.hush.fake.service.context.providers.ContentProviderStub;
import com.hush.fake.service.context.providers.SystemProviderStub;
import com.hush.utils.compat.BuildCompat;

/**
 * Created by @officialakshit01 on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class ContentProviderDelegate {
    public static final String TAG = "ContentProviderDelegate";
    private static final Set<String> sInjected = new HashSet<>();

    public static void update(Object holder, String auth) {
        IInterface iInterface;
        if (BuildCompat.isOreo()) {
            iInterface = BRContentProviderHolderOreo.get(holder).provider();
        } else {
            iInterface = BRIActivityManagerContentProviderHolder.get(holder).provider();
        }

        if (iInterface instanceof Proxy)
            return;
        IInterface bContentProvider;
        if ("settings".equals(auth) || "media".equals(auth) || "telephony".equals(auth)
                || "com.google.android.gsf.gservices".equals(auth)
                || "com.google.android.gsf.gservices.internal".equals(auth)) {
            bContentProvider = new SystemProviderStub().wrapper(iInterface, HushCore.getHostPkg());
        } else {
            bContentProvider = new ContentProviderStub().wrapper(iInterface, HushCore.getHostPkg());
        }
        if (BuildCompat.isOreo()) {
            BRContentProviderHolderOreo.get(holder)._set_provider(bContentProvider);
        } else {
            BRIActivityManagerContentProviderHolder.get(holder)._set_provider(bContentProvider);
        }
    }

    public static void init() {
        clearSettingProvider();

        HushCore.getContext().getContentResolver().call(Uri.parse("content://settings"), "", null, null);
        Object activityThread = HushCore.mainThread();
        ArrayMap<Object, Object> map = (ArrayMap<Object, Object>) BRActivityThread.get(activityThread).mProviderMap();

        for (Object value : map.values()) {
            String[] mNames = BRActivityThreadProviderClientRecordP.get(value).mNames();
            if (mNames == null || mNames.length <= 0) {
                continue;
            }
            String providerName = mNames[0];
            if (!sInjected.contains(providerName)) {
                sInjected.add(providerName);
                final IInterface iInterface = BRActivityThreadProviderClientRecordP.get(value).mProvider();
                if (iInterface instanceof Proxy)
                    continue;

                IInterface wrappedProvider;
                if ("settings".equals(providerName) || "media".equals(providerName)
                        || "telephony".equals(providerName)
                        || "com.google.android.gsf.gservices".equals(providerName)
                        || "com.google.android.gsf.gservices.internal".equals(providerName)) {
                    wrappedProvider = new SystemProviderStub().wrapper(iInterface, HushCore.getHostPkg());
                } else {
                    // Do NOT wrap the guest app's own local ContentProviders!
                    // Wrapping them replaces with a Proxy, causing ClassCastException
                    // when the app casts them to their real concrete class.
                    continue;
                }

                BRActivityThreadProviderClientRecordP.get(value)._set_mProvider(wrappedProvider);
            }
        }
    }

    public static void clearSettingProvider() {
        Object cache;
        cache = BRSettingsSystem.get().sNameValueCache();
        if (cache != null) {
            clearContentProvider(cache);
        }
        cache = BRSettingsSecure.get().sNameValueCache();
        if (cache != null) {
            clearContentProvider(cache);
        }
        if (BRSettingsGlobal.getRealClass() != null) {
            cache = BRSettingsGlobal.get().sNameValueCache();
            if (cache != null) {
                clearContentProvider(cache);
            }
        }
    }

    private static void clearContentProvider(Object cache) {
        if (BuildCompat.isOreo()) {
            Object holder = BRSettingsNameValueCacheOreo.get(cache).mProviderHolder();
            if (holder != null) {
                BRSettingsContentProviderHolder.get(holder)._set_mContentProvider(null);
            }
        } else {
            BRSettingsNameValueCache.get(cache)._set_mContentProvider(null);
        }
    }
}
