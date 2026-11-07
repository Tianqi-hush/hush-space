package de.robv.android.xposed;

/**
 * Xposed 兼容层：Zygote 阶段初始化。
 *
 * <p>容器没有 zygote 阶段，这个回调在模块被加载时（也就是 guest 进程里）
 * 立即调用一次，{@code modulePath} 给的是模块 APK 的绝对路径。
 */
public interface IXposedHookZygoteInit {

    void initZygote(StartupParam startupParam) throws Throwable;

    final class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;

        public StartupParam(String modulePath, boolean startsSystemServer) {
            this.modulePath = modulePath;
            this.startsSystemServer = startsSystemServer;
        }
    }
}
