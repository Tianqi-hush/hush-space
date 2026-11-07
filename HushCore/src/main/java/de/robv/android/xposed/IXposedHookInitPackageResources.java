package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_InitPackageResources;

/**
 * Xposed 兼容层：资源 hook。
 *
 * <p>容器没有改 AssetManager，实现这个接口的模块可以正常加载、正常跑
 * {@link IXposedHookLoadPackage}，只是资源替换不会生效。
 *
 * <p>参数类型用 {@code callbacks.XC_InitPackageResources.InitPackageResourcesParam}，
 * 和真 Xposed 一致，否则模块类链接不上。
 */
public interface IXposedHookInitPackageResources {

    void handleInitPackageResources(
            XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable;
}
