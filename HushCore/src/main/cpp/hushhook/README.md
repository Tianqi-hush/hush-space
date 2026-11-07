# HushHook

`HushHook` 是本项目内置的 **ART 层 Java 方法替换引擎**，以完整源码形式随工程一起构建，
不再依赖任何预编译 `.so`。

## 来源

| 项 | 说明 |
|---|---|
| 上游 | [LSPosed/lsplant](https://github.com/LSPosed/lsplant) `v5.2` |
| 许可证 | GNU Lesser General Public License v3.0，见 `LICENSE` |
| 引入方式 | 源码整体拷入，按本项目命名规范改名 |
| 依赖 | [LSPosed/DexBuilder](https://github.com/LSPosed/DexBuilder)（LGPL-3.0，`external/dex_builder`）<br>[greg7mdp/parallel-hashmap](https://github.com/greg7mdp/parallel-hashmap)（Apache-2.0，`external/dex_builder/external/parallel_hashmap`） |

## 相对上游的改动

1. **改名**：命名空间 `lsplant` → `hushhook`（含 `inline namespace v2`），
   文件 `lsplant.cc/.hpp` → `hushhook.cc/.hpp`，生成类前缀 `LSPHooker_` → `HushHooker_`，
   默认 source 名 `"LSP"` → `"HushHook"`，日志 TAG 同步。
   导出符号随之变为 `_ZN8hushhook2v2...`，`libHushCore.so` 通过 `hushhook::` 调用。
2. **容器替换**：上游使用 abseil 的 `flat_hash_map/flat_hash_set`，
   本项目改用已在依赖树中的 `parallel_hashmap`（接口兼容），避免再引入一份 abseil。
3. **补全头文件**：`include/hushhook.hpp` 显式 `#include <functional>`（上游依赖调用方预包含）。
4. **构建修正**：`external/dex_builder/Android.mk` 补入 `slicer/sha1.cpp`
   （上游 CMake 通过源文件列表自动收集，ndk-build 需要显式列出），
   并加入 `parallel_hashmap` 头文件搜索路径。

5. **线程对象获取兼容 API 36+**：`art::Thread::Current()` 原先只走
   `_ZN3art6Thread14CurrentFromGdbEv`，该符号在新版 libart 里被裁掉，
   导致 `Thread::Init` 直接返回 false、整个引擎初始化失败。
   现在按「CurrentFromGdb → Thread::Current 符号 → TLS slot 7
   （`TLS_SLOT_ART_THREAD_SELF`）」三级回退，`Init` 不再把它当必需项。
6. **新增诊断接口**：`SetInitStageLogger(void (*)(const char *))`，
   初始化任一阶段失败时回调阶段名，便于定位到底卡在 art-method / thread /
   class-linker 等哪一步。该接口只加不改，不影响原有 ABI。
7. **可选阶段降级（API 36/37）**：`jit-code-cache`、`instrumentation`、
   `runtime-debuggable` 三处在符号缺失时不再返回 false，改为写一条
   `SetInitWarnLogger` 告警后继续。上游把 `JitCodeCache::MoveObsoleteMethod`
   当硬依赖，而新版 libart 已把该符号裁掉，结果是整个引擎初始化失败、
   所有模块都不加载。`MoveObsoleteMethod` 缺失时退回 `ArtMethod::SetData` 搬运
   `data_`；`GarbageCollectCache` 缺失时只失去「JIT GC 前搬迁 hooked 方法」这一层加强。
   硬依赖仍为 `art-method` / `thread` / `class-linker` / `mirror-class` /
   `suspend-all` / `gc-critical-section` / `dex-file` / `runtime`。

以上改动只涉及命名、容器实现、兼容性回退与构建脚本，**未改动 hook 逻辑本身**。

## 构建

```bash
cd HushCore/src/main/cpp
$ANDROID_NDK/ndk-build NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk \
  NDK_OUT=obj NDK_LIBS_OUT=libs -j8
```

产物：

- `libs/arm64-v8a/libhushhook.so`（引擎）
- `libs/arm64-v8a/libHushCore.so`（容器 native 层，依赖 `libhushhook.so`）

Gradle 只负责把 `libs/arm64-v8a/*.so` 打进 APK，**不会自动编译 C++**，
修改 `cpp/` 下任何源码后都必须先执行上面的 `ndk-build`。

## 许可证义务

`HushHook` 与 `DexBuilder` 均为 LGPL-3.0。分发本项目的 APK 或源码时，
必须保留本目录下的 `LICENSE` 与上游版权声明，并提供对应源码（本仓库即满足）。
`parallel-hashmap` 为 Apache-2.0，保留其 `LICENSE` 即可。
