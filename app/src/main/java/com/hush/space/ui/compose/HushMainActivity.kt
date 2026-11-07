package com.hush.space.ui.compose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.hush.HushCore
import com.hush.entity.pm.InstallResult
import com.hush.space.R
import com.hush.space.core.AppEntry
import com.hush.space.core.AppRepo
import com.hush.space.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 底部导航的四个目的地。 */
private data class Dest(val label: String, val icon: ImageVector)

private val Dests = listOf(
    Dest("应用", Icons.Default.Apps),
    Dest("空间", Icons.Default.Layers),
    Dest("模块", Icons.Default.Extension),
    Dest("设置", Icons.Default.Settings),
)

class HushMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPhonePermission()
        setContent {
            var themeMode by remember { mutableIntStateOf(Prefs.theme()) }
            var presetId by remember { mutableIntStateOf(Prefs.themePreset()) }
            var customSeed by remember { mutableIntStateOf(Prefs.themeSeed()) }
            var dynamicColor by remember { mutableStateOf(Prefs.dynamicColor()) }

            HushTheme(
                themeMode = themeMode,
                presetId = presetId,
                customSeed = customSeed,
                dynamicColor = dynamicColor,
            ) {
                val dark = when (themeMode) {
                    Prefs.THEME_LIGHT -> false
                    Prefs.THEME_DARK -> true
                    else -> isSystemInDarkTheme()
                }
                LaunchedEffect(dark) { applyBars(dark) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootScreen(
                        themeMode = themeMode,
                        presetId = presetId,
                        customSeed = customSeed,
                        dynamicColor = dynamicColor,
                        onThemeMode = { themeMode = it; Prefs.setTheme(it) },
                        onPreset = { presetId = it; Prefs.setThemePreset(it) },
                        onSeed = { customSeed = it; Prefs.setThemeSeed(it) },
                        onDynamic = { dynamicColor = it; Prefs.setDynamicColor(it) },
                    )
                }
            }
        }
    }

    /**
     * 2.6.45：HMS 的 deviceID 取的是 IMEI / SN，两者都要 READ_PHONE_STATE。
     * 宿主之前没申请这条权限，容器里 getDeviceId / Build.getSerial 一律抛
     * SecurityException，HMS 只好退到 UUID（内嵌 provider 在容器里也查不到），
     * deviceID 变成空串，服务端报 70001201 Parameter[deviceID] is invalid。
     */
    private fun requestPhonePermission() {
        if (Build.VERSION.SDK_INT < 23) return
        val perms = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
        )
        val need = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) return
        requestPermissions(need.toTypedArray(), 0x51)
    }

    /** 状态栏/导航栏透明，图标明暗跟随当前主题，避免深色下白底白字。 */
    private fun applyBars(dark: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScreen(
    themeMode: Int,
    presetId: Int,
    customSeed: Int,
    dynamicColor: Boolean,
    onThemeMode: (Int) -> Unit,
    onPreset: (Int) -> Unit,
    onSeed: (Int) -> Unit,
    onDynamic: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var userId by remember { mutableIntStateOf(Prefs.userId()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf<String?>(null) }
    var pendingModule by remember { mutableStateOf<File?>(null) }
    var pendingIconPkg by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        // 2.6.58：HMS/GMS 开关是在后台 Thread 里装完再弹 toast。Android 11+
        // 在没有 Looper 的线程里调 Toast.show() 会直接抛
        // 「Can't toast on a thread that has not called Looper.prepare()」，
        // 未捕获 → 主进程当场被杀：日志停在「阶段 done」，App 闪退回桌面。
        // 所有调用点统一在这里切回主线程，从根上消掉这一类崩溃。
        val run = { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { run() }
        }
    }

    // ---------- 导入 APK ----------
    val apkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = "正在读取安装包…"
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val apk = AppRepo.copyToCache(ctx, uri, "apk")
                    var isModule = false
                    try {
                        isModule = HushCore.get().isXposedModule(apk)
                    } catch (t: Throwable) {
                        // 模块检测失败不影响正常安装
                    }
                    Triple(apk, isModule, null as String?)
                }
                val (apk, isModule, _) = outcome
                if (isModule) {
                    installing = null
                    pendingModule = apk
                } else {
                    installing = "正在安装…"
                    val r = withContext(Dispatchers.IO) { AppRepo.installApk(apk, userId) }
                    installing = null
                    if (r != null && r.success) {
                        toast("安装成功：${r.packageName ?: apk.name}")
                        refreshKey++
                    } else {
                        toast("安装失败：${r?.msg ?: "未知错误"}")
                    }
                }
            } catch (t: Throwable) {
                installing = null
                toast("安装失败：${t.message}")
            }
        }
    }

    // ---------- 更换图标 ----------
    val iconCropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingIconPkg?.let { AppRepo.invalidateIcon(it) }
        pendingIconPkg = null
        refreshKey++
    }

    val iconLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val pkg = pendingIconPkg
        if (uri == null || pkg == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (t: Throwable) {
            // 某些文件源不授予持久权限，裁剪仍可继续
        }
        val i = Intent(ctx, IconCropActivity::class.java)
        i.putExtra(IconCropActivity.EXTRA_PACKAGE, pkg)
        i.putExtra(IconCropActivity.EXTRA_IMAGE, uri.toString())
        iconCropLauncher.launch(i)
    }

    BackHandler(enabled = tab != 0) { tab = 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "hush 空间",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            Prefs.spaceName(ctx, userId),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Dests.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when (tab) {
                0 -> AppsScreen(
                    userId = userId,
                    refreshKey = refreshKey,
                    onPickApk = {
                        apkLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                    },
                    onPickIcon = { pkg ->
                        pendingIconPkg = pkg
                        iconLauncher.launch(arrayOf("image/*"))
                    },
                    onOpenInfo = { e: AppEntry ->
                        val i = Intent(ctx, AppInfoActivity::class.java)
                        i.putExtra(AppInfoActivity.EXTRA_PACKAGE, e.packageName)
                        i.putExtra(AppInfoActivity.EXTRA_USER_ID, userId)
                        ctx.startActivity(i)
                    },
                    onChanged = { refreshKey++ },
                )

                1 -> SpaceScreen(
                    currentUserId = userId,
                    onSwitched = {
                        userId = Prefs.userId()
                        refreshKey++
                    },
                    onToast = { toast(it) },
                )

                2 -> ModulesScreen(
                    onOpenManager = {
                        ctx.startActivity(Intent(ctx, XposedActivity::class.java))
                    }
                )

                3 -> SettingsScreen(
                    themeMode = themeMode,
                    presetId = presetId,
                    customSeed = customSeed,
                    dynamicColor = dynamicColor,
                    onThemeMode = onThemeMode,
                    onPreset = onPreset,
                    onSeed = onSeed,
                    onDynamic = onDynamic,
                    onOpenSpoof = { global ->
                        ctx.startActivity(
                            Intent(ctx, DeviceSpoofActivity::class.java)
                                .putExtra("extra_spoof_global", global)
                        )
                    },
                    onOpenFeedback = {
                        ctx.startActivity(Intent(ctx, FeedbackActivity::class.java))
                    },
                    onOpenGuide = {
                        ctx.startActivity(Intent(ctx, GuideActivity::class.java))
                    },
                    onToast = { toast(it) },
                )
            }
        }
    }

    if (installing != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("请稍候") },
            text = {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.padding(end = 14.dp), strokeWidth = 2.dp)
                    Text(installing ?: "")
                }
            },
        )
    }

    pendingModule?.let { apk ->
        AlertDialog(
            onDismissRequest = { pendingModule = null },
            title = { Text("检测到模块安装包") },
            text = {
                Text("这个安装包是一个 Xposed 模块。安装到模块空间后才能被空间内的应用加载。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingModule = null
                    installFile(apk, true, userId, scope) { ok, msg ->
                        if (ok) {
                            toast("模块安装成功")
                            refreshKey++
                        } else toast("安装失败：$msg")
                    }
                }) { Text("安装为模块") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingModule = null
                    installFile(apk, false, userId, scope) { ok, msg ->
                        if (ok) {
                            toast("安装成功")
                            refreshKey++
                        } else toast("安装失败：$msg")
                    }
                }) { Text("安装为普通应用") }
            },
        )
    }
}

private fun installFile(
    apk: File,
    asModule: Boolean,
    userId: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    done: (Boolean, String?) -> Unit,
) {
    scope.launch {
        val r = withContext(Dispatchers.IO) {
            try {
                if (asModule) HushCore.get().installXPModule(apk)
                else AppRepo.installApk(apk, userId)
            } catch (t: Throwable) {
                null
            }
        }
        done(r?.success == true, r?.msg)
    }
}
