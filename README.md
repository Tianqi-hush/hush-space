# hush 空间 · 可二次开发工程

一个基于 **Hush 引擎**（虚拟化多开容器）的 Android 工程，界面为 Material 3。
本仓库是**完整可编译的源码**，不是反编译产物：直接导入 Android Studio 或命令行 `gradlew` 即可构建、改包名、加功能。

```
HushSpace/
├── HushCore/                 # 引擎层（SDK 源码，独立 module）
│   ├── src/main/java/com/hush/…
│   └── src/main/cpp/libs/     # libHushCore.so（arm64-v8a，预编译）
├── app/                       # 应用层（UI + 业务，日常改这里）
├── gradlew / gradle/          # Gradle Wrapper 8.7（已指向国内镜像）
├── settings.gradle
└── build.gradle
```

> 仓库不包含签名私钥与华为官方 APK，构建前请按「一、编译」里的**前置条件**自行准备。

---

## 功能清单（v2.0.0）

| 模块 | 能力 |
|---|---|
| 空间 | 新建 / 切换 / 重命名 / 删除，各自独立数据 |
| 应用 | 导入 APK 安装、本机应用一键克隆、启动、强停、清数据、卸载 |
| 桌面 | 生成快捷方式（指向空间内应用） |
| 图标 | 自定义图标（选图 + 裁剪，512×512）、重命名（仅宿主展示层） |
| 伪装 | 按空间配置品牌/机型/厂商/代号/序列号/指纹，3 组预设 |
| 模块 | Xposed 总开关、逐模块启用、导入时自动识别模块包 |
| 环境 | 隐藏 Root、隐藏 Xposed、守护服务、Google 服务（GMS）安装/移除 |
| 界面 | Kotlin + Compose Material 3，底部导航栏，预设/自定义/动态取色 |
| 界面 | M3 明暗双主题、搜索、网格/列表切换、首次引导、反馈 |

---

## 一、编译

环境：JDK 17、Android SDK（platform 34、build-tools 34.0.0）、NDK 不需要。

### 前置条件（仓库未包含的两类文件）

**1. 签名密钥**（`keystore/` 未上传，避免私钥公开）

```bash
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/hush.jks -alias hush \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass <你的口令> -keypass <你的口令> \
        -dname "CN=Hush Space, OU=Dev, O=Hush, L=Shenzhen, ST=Guangdong, C=CN"
```

然后在 `local.properties` 中补上（缺省时产物为未签名 APK）：

```properties
storeFile=keystore/hush.jks
storePassword=<你的口令>
keyAlias=hush
keyPassword=<你的口令>
```

**2. 内置 HMS 组件**（`app/src/main/assets/hms/` 下的 10 个 APK 未上传）

工程需要以下官方安装包才能编译出带华为登录能力的版本，请从你自己的设备或官方渠道获取后按原文件名放入：

```
app/src/main/assets/hms/hmscore.apk                  # com.huawei.hwid
app/src/main/assets/hms/kits/AccountKit-base.apk     # com.huawei.hms.account
app/src/main/assets/hms/kits/GameServiceKit.apk      # com.huawei.hms.game
app/src/main/assets/hms/kits/Framework-*.apk         # runtimekit / fwui / fwkit / commonkit / kitstore / webapp / privacy
```

缺少这些文件时工程仍可编译，只是不含内置 HMS 的登录适配。

### 构建

```bash
# 1. 指定 SDK 路径
echo "sdk.dir=/your/android/sdk" > local.properties

# 2. 构建
./gradlew :app:assembleRelease

# 产物：app/build/outputs/apk/release/app-release.apk
```

调试用 `./gradlew :app:assembleDebug`。首次构建会下载 Gradle 与依赖，请保持网络通畅。

> 引擎只提供 ARM 库，因此**模拟器（x86）无法运行**，只能用 ARM 真机调试。

---

## 二、工程分层

| 层 | 位置 | 职责 |
|---|---|---|
| 引擎 | `HushCore/` | 虚拟空间、应用安装、进程启动、代理组件、Xposed、设备属性 hook |
| 业务 | `app/core/` | 应用列表、安装/启动/卸载、图标与名称存储、设备伪装配置 |
| 界面 | `app/ui/` | Activity / Adapter / 自定义 View |
| 资源 | `app/res/` | M3 主题 token、布局、矢量图标 |

### 引擎入口（只有两个常用调用）

```java
// 初始化：Application.attachBaseContext / onCreate 中
HushCore.get().doCreate();

// 启动空间内的应用
HushCore.get().launchApk(packageName, userId);
```

`HushCore` 的其余常用 API 见 `app/core/AppRepo.java`，那里把所有引擎调用都包了一层：

| 方法 | 说明 |
|---|---|
| `installPackageAsUser(File, int)` | 装 APK 到指定空间 |
| `installPackageAsUser(String, int)` | 把本机已装应用克隆进空间 |
| `getInstalledPackages(int, int)` | 列出空间内应用 |
| `uninstallPackageAsUser / clearPackage / stopPackage` | 卸载 / 清数据 / 强停 |
| `isAppRunning(String, int)` | 是否在运行 |
| `isXposedModule(File)` / `installXPModule(File)` | 模块识别与安装 |

> **不要**在 UI 层直接调引擎——统一走 `AppRepo`，这样以后换引擎只需改一个文件。

---

## 三、核心机制

### 1. 空间（userId）

每个空间是一个独立 `userId`，应用数据隔离在各自目录。`Prefs.userId()` 保存当前空间，切换空间后重新加载列表即可。
新增空间 = `HushCore.get().createUser(...)`；删除 = `deleteUser(id)`。见 `ui/SpaceActivity.java`。

### 2. 设备伪装

- 界面：`ui/DeviceSpoofActivity.java`，按空间保存品牌/机型/厂商/代号/序列号/指纹。
- 生效：`SpaceApplication` 注册 `AppLifecycleCallback`，在**目标应用 Application 创建之前**调用 `SpoofManager.apply(context, userId)`。
- 落点：`SpoofManager` 写入 `SystemPropertyHook` 的覆盖表，native 层已 hook `__system_property_get`，无需改 C++。

要加新的伪装字段（如 `ro.serialno`、`gsm.sim.operator`），只需在 `SpoofManager` 的覆盖表里加键值。

### 3. 自定义图标与名称

`core/IconStore.java`：

- 图标落盘 `files/icons/<pkg>.png`（512×512），由 `ui/IconCropActivity` + `CropImageView` 生成；
- 名称存 SharedPreferences，键 `label_<pkg>`；
- `AppRepo.loadIcon()` 优先返回自定义图标，`resolveLabel()` 优先返回自定义名称。

这一层**不修改 APK**，因此对引擎零侵入，卸载时 `IconStore.clear()` 一起清掉。

---

## 四、二次开发常见改动

### 换包名（做成自己的产品）

1. `app/build.gradle` → `namespace` 与 `applicationId` 改成你的；
2. `app/src/main/res/values/strings.xml` → `app_name`；
3. **不要**改 `HushCore` 的包名（`com.hush.*` 已被 native 层按字符串引用）；
4. 清理 `build/` 后重新构建。

### 换应用名 / 图标

- 名称：`strings.xml` 的 `app_name`
- 图标：替换 `res/mipmap-*/ic_launcher.png`（5 个密度）+ `res/drawable/ic_launcher_foreground.xml`（自适应图标前景）

### 换主题色

`res/values/colors.xml` 与 `res/values-night/colors.xml` 是同一套 M3 token，改这两个文件即可全局换色：

```
primary / on_primary / primary_container / on_primary_container
secondary / tertiary / surface / surface_container_high / outline …
```

### 加一个新页面（示例：应用锁）

1. `res/layout/activity_app_lock.xml` 写布局（用 `Widget.HushSpace.Card` 保持风格统一）
2. `ui/AppLockActivity.java` 继承 `AppCompatActivity`
3. `AndroidManifest.xml` 注册 `<activity android:name=".ui.AppLockActivity" android:exported="false" />`
4. 入口：`MainActivity.showAppSheet()` 里加一行 `bindRow(content.findViewById(R.id.actLock), …)`

### 加一个引擎能力

在 `AppRepo` 增加静态方法封装引擎调用，UI 只依赖 `AppRepo`。这样单元测试可以 mock 掉引擎。

### 替换反馈通道

`ui/FeedbackActivity.buildBody()` 产出纯文本，目前只做「复制 / 系统分享」。
接自己的服务端时，在这个类里加一个 HTTP POST 即可，不需要动其它文件。

---

## 五、构建配置要点

| 项 | 值 | 说明 |
|---|---|---|
| compileSdk | 34 | |
| minSdk | 24 | 引擎要求 |
| targetSdk | 28 | **不要提高**：高于 28 会触发分区存储与后台限制，引擎的目录重定向会失效 |
| ABI | arm64-v8a, armeabi-v7a | 引擎只提供这两个 |
| 签名 | v1 + v2 + v3 | 低版本 ROM 需要 v1 |

`targetSdk` 保持 28 是关键，改成 33/34 会出现「空间内应用读不到自己的数据目录」。

---

## 六、已知限制

1. **只能 ARM 真机运行**，模拟器无 x86 库。
2. 引擎为闭源预编译 `.so`，本工程提供的是**接入层源码**（`com.hush.*` 的 Java 部分），C++ 部分不可改。
3. 强反作弊应用（如带网易易盾的国服游戏）在无 root 的无根容器里通常仍会拒绝运行，这属于方案边界，不是配置问题。
4. 代理组件已扩到 `P0–P49`（引擎 `FREE_COUNT = 50`），如果未来要同时跑更多进程，需要同步扩充 `com/hush/proxy/*` 与清单。

---

## 七、目录速查

```
app/src/main/java/com/hush/space/
├── SpaceApplication.java        引擎初始化 + 伪装回调注册
├── core/
│   ├── AppRepo.java             引擎调用的唯一出口
│   ├── AppEntry.java            列表数据模型
│   ├── IconStore.java           自定义图标 / 名称
│   ├── DeviceProfile.java       伪装字段模型
│   ├── SpoofManager.java        伪装写入引擎
│   └── Prefs.java               本地配置
└── ui/
    ├── SplashActivity           启动 → 权限 → 引导 → 主页
    ├── PermissionActivity       存储权限引导
    ├── GuideActivity            首次引导
    ├── MainActivity             主页（网格 / 列表 / 搜索 / 导入）
    ├── AppInfoActivity          应用详情
    ├── IconCropActivity         图标裁剪
    ├── SpaceActivity            空间管理
    ├── DeviceSpoofActivity      设备伪装
    ├── XposedActivity           模块管理
    ├── SettingsActivity         设置
    ├── FeedbackActivity         反馈
    ├── CropImageView            裁剪控件（缩放/拖动/双击）
    └── adapter/                 AppAdapter / SpaceAdapter / ModuleAdapter
```

---

## 五、许可

本工程包含由 [BlackBox](https://github.com/FBlackBox/BlackBox) 派生的引擎代码（`HushCore/`），
依 GPL-3.0 发布；应用层（`app/`）同样以 GPL-3.0 授权。完整条款见 [LICENSE](LICENSE)。

引擎附带的 SandHook、xdl 等第三方组件版权归各自作者所有。

仓库**不含**华为官方 APK 与签名私钥，请勿将二者提交到公开仓库。
