package com.hush.core.xposed;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块登记表。
 *
 * <p>2.4.3 起，原先为 Star / Month 加的运行期探针已全部移除，包括：画布 /
 * 布局 / TextView 文案拦截、字符串构造探针、授权文件检查、子进程输出过滤、
 * 点击与 exec 记录、socket 与日志镜像。这一层不再 hook 任何方法，只保留
 * 模块 APK 路径登记，供加载器查询。
 */
final class ModuleProbe {

    private static final List<String> sApks = new ArrayList<>();

    private ModuleProbe() {
    }

    static void addModuleApk(String path) {
        if (path == null) {
            return;
        }
        synchronized (sApks) {
            if (!sApks.contains(path)) {
                sApks.add(path);
            }
        }
    }

    static void install(ClassLoader moduleLoader, List<String> entries) {
        // 模块运行期探针已移除，保留空实现以兼容加载器调用。
    }
}
