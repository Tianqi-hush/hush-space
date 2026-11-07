package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * Xposed 兼容层：handleLoadPackage 的参数类型。
 *
 * <p><b>这个类必须放在 {@code de.robv.android.xposed.callbacks} 下</b>，
 * 不能塞进 {@code IXposedHookLoadPackage} 当内部类。模块在编译期链接的是
 * 真 Xposed 的 {@code callbacks.XC_LoadPackage$LoadPackageParam}，方法描述符写死成
 * {@code (Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V}；
 * 如果容器给的接口参数类型对不上，模块类就「实现」不了这个抽象方法，
 * ART 在链接阶段直接放弃整个类，外面只能看到一个没有 cause 的
 * ClassNotFoundException —— 看起来就像模块里根本没有入口类。
 */
public abstract class XC_LoadPackage extends XCallback {

    public XC_LoadPackage() {
        super();
    }

    public XC_LoadPackage(int priority) {
        super(priority);
    }

    protected abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;

    public static final class LoadPackageParam extends XCallback.Param {

        public LoadPackageParam(Object[] callbacks) {
            super(callbacks);
        }

        /** 作用域包名，容器里就是 guest 的包名。 */
        public String packageName;
        /** 进程名，可能是 {@code pkg:sub}。 */
        public String processName;
        /** guest 的 ClassLoader，模块 hook 游戏类全靠它。 */
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
        /** 模块 APK 路径，部分模块用它做自校验。 */
        public String modulePath;

        public ClassLoader getClassLoader() {
            return classLoader;
        }

        public ApplicationInfo getAppInfo() {
            return appInfo;
        }

        public boolean isFirstApplication() {
            return isFirstApplication;
        }
    }
}
