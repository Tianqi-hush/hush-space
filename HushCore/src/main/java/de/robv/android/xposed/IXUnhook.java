package de.robv.android.xposed;

/**
 * Xposed 兼容层：卸载句柄。
 *
 * <p>模块编译时依赖这个接口，签名必须与官方一致，否则
 * {@code XposedBridge.hookMethod()} 的返回值在模块里无法赋值。
 */
public interface IXUnhook<T> {

    T getCallback();

    void unhook();
}
