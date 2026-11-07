package de.robv.android.xposed.callbacks;

import android.content.res.Resources;

/**
 * Xposed 兼容层：资源 hook 的参数类型。
 *
 * <p>放在 callbacks 下的原因同 {@link XC_LoadPackage}：模块链接的是真 Xposed 的
 * {@code callbacks.XC_InitPackageResources$InitPackageResourcesParam}。
 * 容器没有改 AssetManager，所以这个回调即使被调用也不会真的替换资源，
 * 但类型必须在位，否则模块类直接链接失败。
 */
public abstract class XC_InitPackageResources extends XCallback {

    public XC_InitPackageResources() {
        super();
    }

    public XC_InitPackageResources(int priority) {
        super(priority);
    }

    protected abstract void handleInitPackageResources(InitPackageResourcesParam resparam)
            throws Throwable;

    public static final class InitPackageResourcesParam extends XCallback.Param {

        public InitPackageResourcesParam(Object[] callbacks) {
            super(callbacks);
        }

        public String packageName;
        public Resources res;
        /** 资源替换是否可用：容器里恒为 false。 */
        public boolean isFirstPackage;
    }
}
