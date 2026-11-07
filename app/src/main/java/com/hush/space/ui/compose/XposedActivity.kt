package com.hush.space.ui.compose

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hush.HushCore
import com.hush.entity.pm.InstalledModule
import com.hush.space.R
import com.hush.space.core.AppEntry
import com.hush.space.core.AppRepo
import com.hush.space.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 模块（Xposed）管理（Compose 版）。 */
class XposedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HushThemeFromPrefs { ModulesManageScreen(onBack = { finish() }) }
        }
    }
}

@Composable
private fun ModulesManageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var master by remember { mutableStateOf(false) }
    var modules by remember { mutableStateOf<List<InstalledModule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 正在编辑作用域的模块；null 表示未打开。 */
    var scopeTarget by remember { mutableStateOf<InstalledModule?>(null) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    produceState(Unit, refresh) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            try {
                HushCore.get().isXPEnable() to HushCore.get().getInstalledXPModules()
            } catch (t: Throwable) {
                false to emptyList<InstalledModule>()
            }
        }
        master = result.first
        modules = result.second ?: emptyList()
        loading = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "正在校验模块…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val apk = AppRepo.copyToCache(ctx, uri, "module")
                    if (!HushCore.get().isXposedModule(apk)) {
                        ctx.getString(R.string.xposed_not_module)
                    } else {
                        val r = HushCore.get().installXPModule(apk)
                        if (r != null && r.success) {
                            val name = r.packageName ?: apk.name
                            refresh++
                            ctx.getString(R.string.install_success, name)
                        } else {
                            ctx.getString(
                                R.string.install_fail,
                                r?.msg ?: "unknown",
                            )
                        }
                    }
                } catch (t: Throwable) {
                    ctx.getString(R.string.install_fail, t.message ?: "unknown")
                }
            }
            busy = null
            toast(msg)
        }
    }

    HushPage(
        title = "模块",
        subtitle = "Xposed 模块逐应用生效",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    picker.launch(arrayOf("application/vnd.android.package-archive"))
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入模块") },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 10.dp, bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                HushCard {
                    HushRow(
                        icon = Icons.Default.Extension,
                        title = "模块总开关",
                        desc = "关闭后所有模块停止注入",
                        trailing = {
                            Switch(
                                checked = master,
                                onCheckedChange = { on ->
                                    master = on
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                HushCore.get().setXPEnable(on)
                                            } catch (t: Throwable) {
                                            }
                                        }
                                    }
                                },
                            )
                        },
                        onClick = {
                            val on = !master
                            master = on
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        HushCore.get().setXPEnable(on)
                                    } catch (t: Throwable) {
                                    }
                                }
                            }
                        },
                    )
                }
            }

            if (loading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            } else if (modules.isEmpty()) {
                item { EmptyModules() }
            } else {
                item {
                    Text(
                        "带独立界面的模块（例如 Star），点右侧 ▶ 会把它装进「模块空间」并打开；" +
                            "模块空间与普通空间完全隔离，不会和你的应用混在一起。\n" +
                            "点模块卡片可调整作用域，默认对全部应用生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                items(modules, key = { it.packageName }) { m ->
                    ModuleRow(
                        module = m,
                        onToggle = { on ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        HushCore.get().setModuleEnable(m.packageName, on)
                                    } catch (t: Throwable) {
                                    }
                                }
                            }
                        },
                        onScope = { scopeTarget = m },
                        onOpen = {
                            busy = "正在打开 ${m.name?.takeIf { it.isNotBlank() } ?: m.packageName}…"
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) { openModuleApp(ctx, m) }
                                busy = null
                                if (msg != null) toast(msg)
                            }
                        },
                    )
                }
            }
        }
    }

    scopeTarget?.let { target ->
        ScopeSheet(
            module = target,
            onDismiss = { scopeTarget = null },
            onSave = { list ->
                scopeTarget = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            HushCore.get().setModuleScope(target.packageName, list)
                            true
                        } catch (t: Throwable) {
                            false
                        }
                    }
                    if (ok) {
                        refresh++
                        toast(
                            if (list.isEmpty()) "作用域已设为全部应用，重启目标应用后生效"
                            else "作用域已保存（${list.size} 个应用），重启目标应用后生效"
                        )
                    } else {
                        toast("作用域保存失败")
                    }
                }
            },
        )
    }

    if (busy != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("请稍候") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(busy ?: "")
                }
            },
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = {},
            text = { Text(it) },
        )
    }
}

@Composable
private fun EmptyModules() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "还没有安装模块",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "点右下角导入 Xposed 模块，安装后可逐应用启用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun ModuleRow(
    module: InstalledModule,
    onToggle: (Boolean) -> Unit,
    onScope: () -> Unit,
    onOpen: () -> Unit,
) {
    val ctx = LocalContext.current
    var enabled by remember(module.packageName, module.enable) { mutableStateOf(module.enable) }

    val icon = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, module.packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val entry = AppEntry(module.packageName)
                val app = module.application
                if (app != null) {
                    entry.apkPath = app.sourceDir
                    entry.iconRes = app.icon
                    entry.labelRes = app.labelRes
                }
                AppRepo.toBitmap(AppRepo.loadIcon(ctx, entry), 120)?.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }

    val name = module.name?.takeIf { it.isNotBlank() } ?: module.packageName
    val desc = module.desc?.takeIf { it.isNotBlank() } ?: module.packageName
    val scopeSize = module.scope?.size ?: 0

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onScope),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = icon.value
                if (bmp != null) {
                    Image(bmp, contentDescription = null, modifier = Modifier.size(36.dp))
                } else {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (scopeSize == 0) "作用域：全部应用" else "作用域：$scopeSize 个应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        " ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    )
                }
            }
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "打开模块应用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    onToggle(it)
                },
            )
        }
    }
}

/**
 * 作用域编辑弹窗。
 *
 * <p>空列表 = 全部应用，这是模块的默认值；关闭「全部应用」后按应用勾选，
 * 只有勾选的应用在启动时才会加载该模块。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSheet(
    module: InstalledModule,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val initial = remember(module.packageName) {
        (module.scope ?: emptyList<String>()).toList()
    }
    var all by remember(module.packageName) { mutableStateOf(initial.isEmpty()) }
    var selected by remember(module.packageName) { mutableStateOf(initial.toSet()) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(module.packageName) {
        apps = withContext(Dispatchers.IO) {
            try {
                AppRepo.loadCloned(ctx, Prefs.userId())
            } catch (t: Throwable) {
                emptyList()
            }
        }
        loading = false
    }

    val q = query.trim().lowercase()
    val filtered = remember(apps, q) {
        if (q.isEmpty()) apps else apps.filter {
            (it.label ?: "").lowercase().contains(q) ||
                (it.packageName ?: "").lowercase().contains(q)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                "作用域",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "只有勾选的应用启动时才会加载「${module.name ?: module.packageName}」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        all = !all
                        if (all) selected = emptySet()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("全部应用", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "默认值，包含以后新装的应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = all,
                    onCheckedChange = {
                        all = it
                        if (it) selected = emptySet()
                    },
                )
            }

            if (!all) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(6.dp))

                if (loading) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已选 ${selected.size} 个",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            selected = selected + filtered.mapNotNull { it.packageName }
                        }) { Text("全选") }
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.packageName ?: "" }) { app ->
                            ScopeAppRow(
                                app = app,
                                checked = app.packageName != null && selected.contains(app.packageName),
                                onCheckedChange = { on ->
                                    val pkg = app.packageName
                                    if (pkg != null) {
                                        selected = if (on) selected + pkg else selected - pkg
                                    }
                                },
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            if (q.isEmpty()) "当前空间还没有安装应用" else "没有匹配的应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onSave(if (all) emptyList() else selected.toList()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = all || selected.isNotEmpty(),
            ) {
                Text(if (all) "保存（全部应用）" else "保存（${selected.size} 个应用）")
            }
            if (!all) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "至少勾选一个应用；要让模块完全不生效，请关闭右侧开关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScopeAppRow(
    app: AppEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val icon = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                AppRepo.toBitmap(AppRepo.loadIcon(ctx, app), 72)?.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = icon.value
            if (bmp != null) {
                Image(bmp, contentDescription = null, modifier = Modifier.size(28.dp))
            } else {
                Text(
                    (app.label ?: "?").take(1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label ?: app.packageName ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                app.packageName ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 打开模块自带的 App。
 *
 * <p>像 Star 这类模块，注入进游戏的代码只负责起一条本地 socket；真正带悬浮菜单、
 * 开关和功能实现的是模块 APK 自己的 Activity。模块安装时进的是 USER_XPOSED
 * 这个独立用户，不会出现在空间的应用列表里，用户在空间里既看不到也打不开，
 * 表现就是「模块注入了、功能全无」。
 *
 * <p>这里把它按普通应用再装进当前空间（已装则跳过），然后正常启动。
 */
private fun openModuleApp(ctx: android.content.Context, module: InstalledModule): String? {
    val pkg = module.packageName ?: return "模块包名未知"
    return try {
        // 模块的界面一律跑在「模块空间」里，不再装进当前普通空间，
        // 这样模块 App 的账号/设置和游戏空间彻底隔离。
        val userId = Prefs.USER_MODULE
        // 注意：模块启用后 readUserState() 会对所有用户返回 installed=true，
        // 不能用 isInstalled 判断。数据目录真实存在才算装进过这个空间。
        val dataDir = com.hush.core.env.BEnvironment.getDataDir(pkg, userId)
        if (!dataDir.exists()) {
            val src = module.application?.sourceDir
            if (src.isNullOrEmpty()) return "找不到模块 APK"
            val r = HushCore.get().installPackageAsUser(File(src), userId)
            if (r == null || !r.success) {
                return "安装到模块空间失败：" + (r?.msg ?: "unknown")
            }
        }
        if (AppRepo.launch(ctx, pkg, userId)) null else "该模块没有可启动的界面"
    } catch (t: Throwable) {
        "打开失败：" + (t.message ?: t.javaClass.simpleName)
    }
}

/** 供主界面「模块」页跳转用。 */
internal fun openModuleManager(ctx: android.content.Context) {
    ctx.startActivity(
        android.content.Intent(ctx, XposedActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
