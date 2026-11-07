package net_62v.external;

import android.content.Context;
import com.hush.app.configuration.AppLifecycleCallback;
import com.hush.app.configuration.ClientConfiguration;
import com.hush.HushCore;

public class MetaCore {

    /**
     * Bridges the legacy lifecycle callback to your core's AppLifecycleCallback.
     * Called in attachBaseContext inside BoxApplication.kt.
     */
    public static void setProcessLifecycleCallback(final IHushProcessCallback callback) {
        if (callback == null) return;

        // Auto-initialize HushCore if the callback is an Application Context!
        // This ensures compatibility with users of this wrapper who forget to call attachBaseContext.
        if (callback instanceof Context) {
            final Context context = (Context) callback;
            
            // Only attach if it hasn't been attached yet to avoid affecting existing correct setups
            if (HushCore.getContext() == null) {
                HushManager.attachBaseContext(context, new ClientConfiguration() {
                    @Override
                    public String getHostPackageName() {
                        return context.getPackageName();
                    }
                });
            }
        }

        HushManager.addAppLifecycleCallback(new AppLifecycleCallback() {
            @Override
            public void beforeCreateApplication(String packageName, String processName, Context context, int userId) {
                // BoxApplication.kt checks if the first string equals "com.pubg.imobile"
                // So we pass the processName as the first parameter.
                callback.onCreate(processName, null);
            }
        });
    }
}
