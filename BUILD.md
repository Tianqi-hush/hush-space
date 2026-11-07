# hush 空间 2.6.0 · 构建与改动说明

> **当前版本：2.6.0（versionCode 120）。**
> 自 2.4.5 起，ART hook 引擎 `HushHook` 以**完整源码**内置在
> `HushCore/src/main/cpp/hushhook/`，不再使用预编译 `liblsplant.so`。
> 引擎来源、改造点与许可证见 `hushhook/README.md`。
> 下文第二章「2.0.0 做了什么」为历史记录，保留备查。

## 一、版本信息

| 项 | 值 |
|---|---|
| 应用名 | hush 空间 |
| applicationId | `com.hush.space` |
| versionCode / versionName | 120 / 2.6.0 |
| minSdk / targetSdk | 36 / 28 |
| 架构 | arm64-v8a |
| 签名 | `keystore/hush.jks`，别名 `mundo`，口令 `mundo2024` |

> **重要：包名已从 `com.mundo.space` 改为 `com.hush.space`。**
> 系统会把它当成一个全新应用，**不能覆盖安装旧版**。升级前需先卸载 1.6.x 版本。
> 旧版空间内的应用与数据存放在旧包名目录下，不会自动迁移，卸载即清除。

### 2.4.7 修复了什么

2.4.6 在 Android 16/17（API 36/37）上 `HushHook init=0`，日志里 `失败阶段: jit-code-cache`，
随后 `xp: HushHook 未就绪，跳过模块加载（模块数=4）`——所有模块因此一个都没加载。

原因是 libart 的导出符号被大幅裁剪，`JitCodeCache::MoveObsoleteMethod` /
`GarbageCollectCache` 整条消失，而 `JitCodeCache::Init` 把「找不到」当成致命错误。
这两个 hook 只是「JIT GC 时别回收已 hook 方法」的加强项，缺了不该让整个引擎判死。

2.4.7 的改动：

| 阶段 | 2.4.6 | 2.4.7 |
|---|---|---|
| `jit-code-cache` | 缺符号 → 整体失败 | 降级：`SetData` 兜底，仅记 `HushHook 降级: jit-*` |
| `instrumentation` | Hook 不上 → 整体失败 | 降级：只影响 deopt 后的入口迁移 |
| `runtime-debuggable` | 缺 `SetJavaDebuggable` → 整体失败 | 降级：按非 debuggable 处理 |
| 诊断 | 只报第一个失败阶段 | 失败阶段 + 降级项都写进 `diag.log` |

硬依赖仍保留：`art-method`、`thread`、`class-linker`、`mirror-class`、
`suspend-all`、`gc-critical-section`、`dex-file`、`runtime`——这些缺了没法工作。

另外把 `XposedEngine` 的提示从「6 秒内没就绪」改成区分「初始化失败」与「超时」，
避免下次再从一句笼统的话猜原因。

### 2.4.8 修复了什么

2.4.7 已经让 `HushHook init=1`，4 个模块开始加载，但 guest 随后在同一个点崩两次：

```
Caused by: java.lang.ClassCastException: com.tgc.sky.TGCApplication cannot be cast to android.app.Activity
    at com.example.myxposed.Sky$1.beforeHookedMethod(dex-id-...:50)
    at XposedEngine$Hooker.dispatch(XposedEngine.java:353)
    at HushHooker_.attachBaseContext(Hush)
    at androidx.multidex.MultiDexApplication.attachBaseContext(MultiDexApplication.java:38)
→ Unable to makeApplication → _exit(10)
```

模块 hook 的是 `android.content.ContextWrapper#attachBaseContext`，这个方法的 this 既可能是
Activity 也可能是 Application；xeno 的 `Sky$1` 直接强转 Activity，遇到 `TGCApplication`
就抛 `ClassCastException`。之前引擎把 before 阶段的异常 `setThrowable` 成「原方法的异常」
抛了出去，于是 Application 创建失败、进程自杀。

2.4.8 起，**模块回调自己抛的异常不再传播**：只写一条 `回调异常(before #n)` 到 diag，
然后照常执行原方法。需要「让原方法抛异常」的模块本来就会调 `param.setThrowable()`，
语义不受影响。这与 after 阶段此前的处理保持一致。

### 只支持 Android 16+

按要求把 `minSdk` 从 24 提到 **36（Android 16）**，`app` 与 `HushCore` 两个 module 同步。
`targetSdk` 保持 28（容器需要关闭分区存储等新限制）。低于 Android 16 的设备将无法安装。

minSdk 提高后 AGP 默认把 dex 改成不压缩打包，APK 会涨到 49MB；已在
`packagingOptions.dex.useLegacyPackaging` 里改回压缩，体积维持 ~16MB。

### 2.4.9 修复 zll（NPatch 系）模块注入

2.4.8 之后模块能加载、进程不再自杀，但 zll 的 `skymod_boot.log` 停在：

```
ModuleSource: codeSource err java.lang.NullPointerException: ... getCodeSource() on a null object reference
ModuleSource: found apk .../cache/npatch/com.netease.sky/base.apk size=18269419 hasManifest=true
ModuleSource: manifest ok build=rel-2.0.0 pkg=com.sky.zll
authenticated native bootstrap failed: IOException(Release file size mismatch)
ensureLoaded=false
```

第一行是根。zll 用 `ModuleSource.class.getProtectionDomain().getCodeSource().getLocation()`
拿自己的 apk 路径；容器里模块类由 `ModuleClassLoader`（DexClassLoader）加载，ART 返回
**null**，它只能退化到「扫 cacheDir 找 npatch 载荷」，然后拿宿主的 2GB base.apk 去
对 manifest 里的 `classes.dex 220080` 做校验 —— 自然 mismatch，`ensureLoaded=false`，
功能全废。

2.4.9 在加载模块前挂一个 `Class#getProtectionDomain` 的 after hook：结果为 null 且
ClassLoader 是 `ModuleClassLoader` 时，用模块 apk 造一个带 `CodeSource` 的
`ProtectionDomain`（证书从 APK 签名取，模块若校验 `getCertificates()` 也能对上）返回。
这样 zll 走回真机那条路，用自己的 apk 做校验。

同时新增 `payload 校验` 日志：按 `assets/zll/release.manifest` 逐条比对 apk 里的
entry 大小，下次若还有 mismatch，日志会直接写清是哪个文件、差多少。

### 2.5.0 修复 zll 仍未注入

2.4.9 的桥接一次都没命中，日志里仍是 `ModuleSource: codeSource=null`。原因不是 hook 没装，
而是**判断条件写错了**：ART 对 DexClassLoader 定义的类返回的是一个**非 null** 的
`ProtectionDomain`，只是里面的 `CodeSource` 是 null。2.4.9 写的是
`if (param.getResult() != null) return;`，于是每次都在这里直接返回。

zll 因此继续走退化路径：扫 cacheDir 找到上次遗留的 `npatch/<宿主>/base.apk`，
再按 `release.manifest` 逐条比 size，报 `Release file size mismatch`，`ensureLoaded=false`。

2.5.0 改了三处：

| 改动 | 说明 |
|---|---|
| 桥接条件 | 不再看 `result == null`，改看 `CodeSource` 是否为 null；命中后第一次写 `ProtectionDomain 命中 <类> -> <路径>` |
| 目标路径 | 优先用容器内**真实存在**的 payload 副本，而不是虚拟安装路径 `/data/app/...`（容器里 `isFile()` 为 false，模块拿到也打不开） |
| payload 自愈 | 每次启动按 manifest 逐条比对副本；不符就删掉重投，日志写 `载荷副本陈旧，准备重投 不符=N` |

另加一层 zll 探针：hook `com.sky.zll.ModuleSource` 的构造器打印它实际选中的 apk，
并跳过 `l(String)` 的纯文件校验（只比 size/sha256，无副作用；真正解压 so 的
`d(File)` 还会再校验一次）。日志形如：

```
xp: zll 选中源 /data/.../cache/npatch/com.netease.sky/base.apk size=18269419
xp: zll 跳过校验 classes.dex apk=/data/.../base.apk
xp: zll 探针已安装 方法=3 apk=/data/app/.../com.sky.zll.../base.apk
```

### 2.5.1 找到 zll 真正的失败分支

2.5.0 的探针日志暴露了关键时序：`manifest ok` 到 `bootstrap failed` 只隔 **1 毫秒**。
这说明模块根本没走到解压，而是选了另一条分支。

反编译 `com.sky.zll.ModuleSource` 后确认：它的构造器第二个参数是

```java
boolean staging = !"com.sky.zll".equals(context.getPackageName());
```

真机上 `getPackageName()` 返回宿主包名（`com.netease.sky`），`staging=true`，
模块把 `release.manifest` 里登记的几个文件解压到 `codeCacheDir/modlibs`、`modfonts`，
再逐个校验。

而容器为了让模块的盗版检测通过，`ModuleIdentity` 会把模块代码看到的
`getPackageName()` 伪装成 `com.sky.zll` —— 于是这个表达式变成 `false`，模块改走
「直接从 `ApplicationInfo.nativeLibraryDir` 加载」的捷径，去
`/data/app/~~xxx==/com.netease.sky-xxx==/lib/arm64/libWIDGETS_EXPERT.so` 找文件。
那是宿主安装目录的虚拟路径，容器里既不存在也不会被重定向到模块 apk，
`File.isFile()` 直接 false，`m()` 立刻抛 `Release file size mismatch`。

2.5.1 的改动：

| 改动 | 说明 |
|---|---|
| 构造参数纠正 | hook `ModuleSource.<init>`，把第二个参数 `Z` 从 `false` 强制改成 `true`，让它走真机同款「自解压 + 校验」分支 |
| 暂存目录自清 | 模块只在异常分支清 `modlibs`/`modfonts`，进程被杀会留下残骸，而它对 `mkdirs()` 返回 false 的处理是直接抛 `Cannot create staging`（一次失败后永久失败）。现在每次引导前先清空 |
| 引导结果诊断 | hook `com.sky.zll.a3#b(Context)`，把 `modlibs`/`modfonts` 里实际有哪些文件、各自多少字节写进日志 |

日志会变成：

```
xp: zll 构造参数 b=false -> 强制 true（改走自解压分支）
xp: zll 选中源 /data/.../cache/npatch/com.netease.sky/base.apk size=18269419
xp: zll 引导结果=true codeCache=... exists=true | modlibs exists=true n=4 libWIDGETS_EXPERT.so:3632368 ... | modfonts exists=true n=1 fonts.ttf:1461044
```

### 2.6.0 模块专属空间 + 空间级配置 + 区域/语言/时区

**模块专属空间**

模块的 APK 一直装在 `USER_XPOSED=-4`，但它的设置界面此前会被复制进「当前空间」，
和普通应用混在一起。2.6.0 起：

- 空间列表固定第一条是「模块空间」（userId=99），不可删除、始终存在；
- 模块页右侧 ▶ 改为把模块装进 99 并启动，不再碰当前空间；
- 99 与 0/1/2… 完全隔离：应用列表、数据目录、设备伪装、环境开关互不影响；
- 模块本体仍在 -4，引擎识别模块的逻辑不变。模块数据若写在外部存储
  （例如 zll 的 `skymod_boot.log`）仍然和游戏共享。

**环境 / 当前空间支持「全部空间」与「仅当前空间」**

两张卡片顶部各加了作用范围选择：

| 作用范围 | 行为 |
|---|---|
| 全部空间 | 写入 `system/space-global.json`，所有未单独配置的空间跟随 |
| 仅当前空间 | 写入 `system/space-<userId>.json`，只覆盖该空间，其它空间不受影响 |

- 环境：隐藏 Root、隐藏 Xposed 按空间生效（每个 guest 进程启动时套用）；
  守护服务是宿主级能力，只在「全部空间」下可改。
- 当前空间：区域、语言、时区、设备伪装都支持全局默认 + 单空间覆盖；
  Google 服务仍然只能单独给某个空间安装。
- 老版本升级时，`hush_space.xml` 里的三个开关会自动迁移成全局配置。

**区域 / 语言 / 时区**

「当前空间」卡片新增三行，可选：

- 语言：跟随系统、简体中文、繁體中文、English、日本語、한국어、Deutsch、Français、Español
- 区域：跟随系统、中国大陆、中国香港、中国台湾、中国澳门、美国、日本、韩国、英国、德国
- 时区：跟随系统、北京/香港/台北、东京/首尔、伦敦/柏林、纽约/洛杉矶、UTC

生效点在 `beforeCreateApplication`：`Locale.setDefault`、`LocaleList.setDefault`、
Resources 的 Configuration、`TimeZone.setDefault` 全部按该空间的合并配置重设，
因此日期格式、数字格式、应用内语言、时区偏移都会跟着变。改动后需重启该空间里的应用。

**存储位置新增**

| 路径 | 内容 |
|---|---|
| `system/space-global.json` | 全局环境与区域/语言/时区默认值 |
| `system/space-<userId>.json` | 单空间覆盖项，只写改过的字段 |
| `device_profile_global.json` | 全部空间的默认机型（未被单空间覆盖时使用） |

### 2.5.2 模块作用域 + 模块应用分组 + 数据位置

**模块作用域（Scope）**

模块页每张卡片新增「作用域」一行，点击卡片打开作用域面板：

- 默认「全部应用」——等价于 2.5.1 的行为，升级后功能不变；
- 关闭「全部应用」后可以按应用勾选，支持搜索、全选、清空；
- 只有勾选的应用在启动时才会加载该模块，其余应用的 `diag.log` 会写
  `有 N 个启用模块，但都不在作用域 <包名> 内`，并且**不会启动 HushHook 引擎**。

存储上，`system/xposed-module.conf` 的 Parcel 布局增加了
`模块包名 → 作用域列表`。文件头写了 `0x7F` magic：读到旧格式会自动回退到
原解析路径，因此从 2.5.1 升级不会丢模块开关状态。空列表 = 全部应用。

**模块应用与普通应用分开**

「打开模块 App」会把模块装进当前空间，它以前会混在「我的应用」里。现在：

- `AppEntry` 增加 `module` 标记（以 `USER_XPOSED` 里是否装有同包名模块为准）；
- 「我的应用」网格拆成两组：普通应用在前，「模块应用 N」单独一组在后；
- 模块图标的右下角带一个「模块」角标。

**数据存放点**

宿主私有根：`/data/user/0/com.hush.space/files/`

| 路径 | 内容 |
|---|---|
| `data/app/<包名>/base.apk` | 所有导入的 APK（普通应用与模块共用；模块的安装状态记在 package.conf 的 `-4` 用户里） |
| `data/app/<包名>/package.conf` | 包信息、安装用户、权限、组件 |
| `data/user/<userId>/<包名>/` | 空间内应用数据：`files`、`cache`、`databases`、`shared_prefs`、`lib` |
| `data/user/-4/<包名>/` | `USER_XPOSED`（模块用户）下的模块数据 |
| `data/user_de/<userId>/<包名>/` | 设备加密存储目录 |
| `system/xposed-module.conf` | 模块总开关、每个模块的启用状态、**作用域** |
| `system/user.conf` | 空间（虚拟用户）列表与名称 |
| `system/accounts.conf` | 虚拟账号 |
| `system/uid.conf` | 包名 → uid 映射 |
| `system/shared-user.conf` | sharedUserId 分组 |
| `system/fake-location.conf` | 虚拟定位配置 |
| `system/fake-device.conf` | 设备伪装配置（底层副本） |
| `icons/<包名>.png` | 用户自定义图标（512×512） |
| `diag/diag.log` | 诊断日志（外部目录不可写时的兜底位置） |

> 应用看到的 `/data/data/<包名>` 会被 IO 重定向到
> `files/data/user/<userId>/<包名>`，所以上面这些是**真实物理路径**。

外部存储（本机实测可写根）：

| 路径 | 内容 |
|---|---|
| `/storage/emulated/0/Android/data/com.hush.space/files/diag.log` | 诊断日志（首选位置） |
| `…/files/vbox/SdCard/` | 容器外部沙盒根 |
| `…/SdCard/Android/data/<guest包名>/` | guest 的 `getExternalFilesDir()` 等目录 |
| `…/SdCard/Download/` | guest 下载目录（如模块导出文件） |

宿主缓存（可被系统清理）：

| 路径 | 内容 |
|---|---|
| `cache/import/*.apk` | 「导入 APK」时先复制到这里的临时文件 |
| `cache/npatch/<宿主包名>/base.apk` | zll 系模块要求的 npatch 载荷副本（从模块 APK 复制，按 manifest 自检） |

SharedPreferences（`/data/user/0/com.hush.space/shared_prefs/`）：

| 文件 | 内容 |
|---|---|
| `hush_space.xml` | 当前空间、主题、启动页、开关等 UI 配置 |
| `hush_icon_store.xml` | 自定义应用名称（图标本体在 `files/icons/`） |

设备伪装：`files/device_profile_<userId>.json`（每个空间一份）。

模块自身的数据（例如 zll 的 `skymod_boot.log`）在
`files/data/user/-4/<模块包名>/` 和
`…/vbox/SdCard/Android/data/<模块包名>/` 下，属于模块自己的沙盒，容器不解析。

## 二、2.0.0 做了什么

### 1. 全量改名 Mundo → hush

| 层 | 改动 |
|---|---|
| 应用包名 | `com.mundo.space` → `com.hush.space`（24 个 Java 文件迁到新包） |
| 引擎模块 | 目录 `MundoCore/` → `HushCore/`，Java 包 `com.mundo` → `com.hush`（1574 个文件） |
| 引擎类 | `MundoCore` → `HushCore`，`MundoManager` → `HushManager` 等 |
| native | `libMundoCore.so` → `libHushCore.so`，JNI 注册类名 `com/hush/core/VNative`，已重新编译 |
| 资源 | `Theme.MundoSpace` → `Theme.HushSpace`，全部文案与字符串 |
| 其他 | 签名文件 `mundo.jks` → `hush.jks`，SharedPreferences 文件名同步 |

JNI 采用 `RegisterNatives` 动态注册，类名由 `BoxCore.h` 的 `VMCORE_CLASS` 宏指定，
因此改包名必须同时重编 so —— 本包中的 `libHushCore.so` 已用 NDK 26b 重编，
`strings` 校验结果为 `com/hush/core/VNative`，`com/mundo` 残留 0 处。

### 2. 界面改为 Kotlin + Compose Material 3

- 新增 4 个 Kotlin 文件：`HushTheme.kt`、`HushMainActivity.kt`、`AppsScreen.kt`、`SpaceScreen.kt`、`SettingsScreen.kt`
- 主界面与设置页完全用 Compose 重写；其余页面（应用信息、设备伪装、模块管理、图标裁剪等）沿用原 MD3 XML 布局，配色同步
- 入口：`SplashActivity` → `HushMainActivity`（原 `MainActivity` 保留，不再作为入口）

### 3. 底部导航栏

原右上角三个入口（空间 / 模块 / 设置）移到屏幕底部的 MD3 `NavigationBar`：

| 位置 | 页面 |
|---|---|
| 应用 | 应用网格（我的应用 / 本机应用 分段切换、搜索、导入 APK、长按操作菜单） |
| 空间 | 空间列表（切换 / 新建 / 删除，显示每个空间的应用数） |
| 模块 | 模块说明与「模块管理」入口 |
| 设置 | 外观、环境、当前空间、支持、关于 |

### 4. 主题系统

设置 → 外观：

- **模式**：跟随系统 / 浅色 / 深色
- **预设配色**：静海（靛蓝）、青瓷（青绿）、暮橙、森野（绿）、樱雾（粉）、石墨（灰）
- **自定义颜色**：输入 `#RRGGBB` 即时生效
- **动态取色**：Android 12+ 从壁纸取色，开启后忽略预设

实现方式：只存一个种子色，明暗两套共 30 多个槽位由 `hushScheme()` 现算
（HSL 近似 Material 的 tone 板：亮色主色 tone 40 / 容器 90 / 文字 10，暗色相反）。
因此任意颜色都能得到协调配色，新增预设只需在 `HushPresets` 里加一行。

持久化字段在 `Prefs.java`：

```java
theme() / setTheme()                 // 0 跟随系统 1 浅色 2 深色
themePreset() / setThemePreset()     // 预设下标
themeSeed() / setThemeSeed()         // 自定义种子色 ARGB
dynamicColor() / setDynamicColor()   // 动态取色开关
```

## 三、编译

```bash
# JDK 17，Android SDK 34
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
```

只改 Java/Kotlin 时**不需要 NDK**。仅当修改 `HushCore/src/main/cpp` 下的 C++ 时才需要：

```bash
cd HushCore/src/main/cpp
$ANDROID_NDK/ndk-build NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk \
  NDK_OUT=obj NDK_LIBS_OUT=libs -j8
```

产物：`libs/arm64-v8a/libHushCore.so`、`libs/arm64-v8a/libhushhook.so`
（jniLibs 直接引用该目录）。

`libhushhook.so` 由 `hushhook/` 下的源码编译（含 `external/dex_builder` 与
`parallel_hashmap`），`Android.mk` 末尾已接入该模块，无需额外配置。

## 四、目录结构

```
HushSpace/
├── HushCore/                     # 引擎（SDK 源码，独立 module）
│   ├── src/main/java/com/hush/   # 1574 个 Java 文件
│   ├── src/main/aidl/com/hush/   # AIDL 定义（包路径已同步）
│   └── src/main/cpp/             # C++ 源码 + Dobby + hushhook（引擎全源）
│       └── libs/arm64-v8a/       # libHushCore.so / libhushhook.so / libc++_shared.so
├── app/
│   ├── src/main/java/com/hush/space/
│   │   ├── core/                 # AppRepo / Prefs / IconStore / Diag …
│   │   └── ui/
│   │       ├── compose/          # 新增：Compose 界面与主题
│   │       └── *.java            # 原有 MD3 页面
│   └── src/main/res/
├── keystore/hush.jks
├── build.gradle / settings.gradle
└── BUILD.md（本文件）
```

## 五、保留的原能力

导入 APK、本机应用克隆、多空间、设备伪装、Xposed 模块、GMS 安装、
隐藏 Root / Xposed、守护服务、快捷方式、自定义图标与重命名、诊断日志导出 —— 全部未改动。

## 六、已知事项

1. **必须卸载旧版**：包名变更导致无法覆盖安装。
2. **主题仅作用于 Compose 页面**：应用信息、设备伪装等 Java 页面使用固定 MD3 配色，
   切换预设后不会跟着变；如需全局一致，需要逐页迁移到 Compose。
3. `local.properties` 未包含，首次导入 Android Studio 会自动生成，或复制 `local.properties.example`。
