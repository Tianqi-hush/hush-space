package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 兼容层：模块入口之一。
 *
 * <p>参数类型必须是 {@link XC_LoadPackage.LoadPackageParam} —— 和真 Xposed
 * 保持完全一致的描述符。以前这里写成了内部类
 * {@code IXposedHookLoadPackage.LoadPackageParam}，模块编译时链接的是
 * {@code callbacks.XC_LoadPackage$LoadPackageParam}，两者对不上，
 * 模块类就无法实现这个抽象方法，ART 链接阶段直接判失败，
 * 表现成「入口类 ClassNotFoundException」。
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
