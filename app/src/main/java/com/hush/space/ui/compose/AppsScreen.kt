package com.hush.space.ui.compose

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hush.HushCore
import com.hush.entity.pm.InstallResult
import com.hush.space.core.AppEntry
import com.hush.space.core.AppRepo
import com.hush.space.core.IconStore
import com.hush.space.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 网格里的一条：把 Drawable 提前转成 ImageBitmap，避免在滚动时反复读 APK。 */
private class AppItem(val entry: AppEntry, val icon: ImageBitmap?)

private fun loadItem(ctx: Context, e: AppEntry): AppItem {
    val bmp = try {
        AppRepo.loadIcon(ctx, e)?.let { AppRepo.toBitmap(it, 144)?.asImageBitmap() }
    } catch (t: Throwable) {
        null
    }
    return AppItem(e, bmp)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    userId: Int,
    refreshKey: Int,
    onPickApk: () -> Unit,
    onPickIcon: (String) -> Unit,
    onOpenInfo: (AppEntry) -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var cloned by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var local by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }

    var selected by remember { mutableStateOf<AppEntry?>(null) }
    var installName by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<AppEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun refresh() {
        reload++
    }

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(userId, refreshKey, reload) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            val c = try {
                AppRepo.loadCloned(ctx, userId).map { loadItem(ctx, it) }
            } catch (t: Throwable) {
                emptyList<AppItem>()
            }
            val l = try {
                AppRepo.loadLocal(ctx).map { loadItem(ctx, it) }
            } catch (t: Throwable) {
                emptyList<AppItem>()
            }
            c to l
        }
        cloned = result.first
        local = result.second
        loading = false
    }

    val clonedSet = remember(cloned) { cloned.map { it.entry.packageName }.toSet() }
    val source = if (tab == 0) cloned else local
    val q = query.trim().lowercase()
    val filtered = remember(source, q) {
        if (q.isEmpty()) source
        else source.filter {
            it.entry.label?.lowercase()?.contains(q) == true ||
                it.entry.packageName?.lowercase()?.contains(q) == true
        }
    }

    fun install(entry: AppEntry) {
        installName = entry.label
        scope.launch {
            val r: InstallResult? = withContext(Dispatchers.IO) {
                try {
                    AppRepo.installFromInstalledApp(entry.packageName, userId)
                } catch (t: Throwable) {
                    null
                }
            }
            installName = null
            if (r != null && r.success) {
                toast("已安装：${entry.label}")
                refresh()
                onChanged()
            } else {
                toast("安装失败：${r?.msg ?: "未知错误"}")
            }
        }
    }

    /**
     * 点图标启动。HMS Core / 华为账号 / 荣耀服务本来就没有桌面入口
     * （官方 APK 里没有 LAUNCHER activity），直接调 launch 必然失败，
     * 以前统一提示「请确认应用已安装到当前空间」，看起来像装坏了。
     */
    fun openOrHint(entry: AppEntry) {
        val pkg = entry.packageName
        if (com.hush.core.HmsCore.isHuaweiFamily(pkg)) {
            if (pkg == com.hush.core.HmsCore.HMS_PKG && AppRepo.openHmsAccount(userId)) return
            toast("这是后台服务，已装在「${Prefs.spaceName(ctx, userId)}」里，没有桌面界面；华为账号会在应用内自动调用")
            return
        }
        if (!AppRepo.launch(ctx, pkg, userId)) {
            toast("启动失败，请确认应用已安装到当前空间")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                placeholder = { Text("搜索应用") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
            )

            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("我的应用") }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("本机应用") }
            }

            Spacer(Modifier.height(6.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filtered.isEmpty()) {
                EmptyApps(tab, q.isNotEmpty(), onPickApk)
            } else {
                val normalApps = filtered.filter { !it.entry.module }
                val moduleApps = filtered.filter { it.entry.module }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 96.dp),
                ) {
                    items(normalApps, key = { it.entry.packageName ?: it.entry.label ?: "" }) { item ->
                        AppCell(
                            item = item,
                            installed = item.entry.cloned || clonedSet.contains(item.entry.packageName),
                            onClick = {
                                val e = item.entry
                                if (e.cloned || clonedSet.contains(e.packageName)) {
                                    openOrHint(e)
                                } else {
                                    install(e)
                                }
                            },
                            onLongClick = { selected = item.entry },
                        )
                    }
                    if (moduleApps.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            GridSectionHeader("模块应用", moduleApps.size)
                        }
                        items(moduleApps, key = { it.entry.packageName ?: it.entry.label ?: "" }) { item ->
                            AppCell(
                                item = item,
                                installed = true,
                                onClick = {
                                    val pkg = item.entry.packageName
                                    if (pkg != null) {
                                        scope.launch(Dispatchers.IO) {
                                            // 模块启用后对所有用户都显示“已安装”，
                                            // 真正装没装要看数据目录是否存在。
                                            val dir = com.hush.core.env.BEnvironment
                                                .getDataDir(pkg, userId)
                                            if (!dir.exists()) {
                                                val apk = item.entry.apkPath
                                                if (apk != null) {
                                                    try {
                                                        HushCore.get().installPackageAsUser(
                                                            java.io.File(apk), userId
                                                        )
                                                    } catch (t: Throwable) {
                                                    }
                                                }
                                            }
                                            val ok = AppRepo.launch(ctx, pkg, userId)
                                            if (!ok) {
                                                withContext(Dispatchers.Main) {
                                                    toast("启动失败，请先在模块页点 ▶ 打开一次")
                                                }
                                            }
                                        }
                                    }
                                },
                                onLongClick = { selected = item.entry },
                            )
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onPickApk,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("导入 APK") },
        )
    }

    if (selected != null) {
        val e = selected!!
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = sheetState,
        ) {
            AppSheetContent(
                entry = e,
                onLaunch = {
                    selected = null
                    // HMS Core / 华为账号 / 荣耀服务都是纯后台服务，本来就没有
                    // 可启动的界面；openOrHint 会给出对应说明。
                    openOrHint(e)
                },
                onInfo = {
                    selected = null
                    onOpenInfo(e)
                },
                onIcon = {
                    selected = null
                    onPickIcon(e.packageName)
                },
                onRename = {
                    selected = null
                    renameTarget = e
                    renameText = IconStore.label(ctx, e.packageName) ?: e.label ?: ""
                },
                onShortcut = {
                    selected = null
                    val ok = AppRepo.createShortcut(ctx, e, userId)
                    toast(if (ok) "已发送快捷方式请求" else "创建快捷方式失败")
                },
                onStop = {
                    selected = null
                    scope.launch(Dispatchers.IO) { AppRepo.stop(e.packageName, userId) }
                    toast("已停止")
                },
                onClear = {
                    selected = null
                    confirm = "清除「${e.label}」的全部数据？此操作不可撤销。" to {
                        scope.launch(Dispatchers.IO) { AppRepo.clearData(e.packageName, userId) }
                        toast("数据已清除")
                    }
                },
                onUninstall = {
                    selected = null
                    confirm = "确定要从当前空间卸载「${e.label}」吗？" to {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AppRepo.uninstall(e.packageName, userId)
                                IconStore.clear(ctx, e.packageName)
                            }
                            toast("已卸载")
                            refresh()
                            onChanged()
                        }
                    }
                },
            )
        }
    }

    if (installName != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("正在安装") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(14.dp))
                    Text(installName ?: "")
                }
            },
        )
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("显示名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = renameTarget!!
                    IconStore.setLabel(ctx, e.packageName, renameText.trim())
                    AppRepo.invalidateIcon(e.packageName)
                    renameTarget = null
                    refresh()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val e = renameTarget!!
                    IconStore.setLabel(ctx, e.packageName, null)
                    AppRepo.invalidateIcon(e.packageName)
                    renameTarget = null
                    refresh()
                }) { Text("恢复默认") }
            },
        )
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("请确认") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    action()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            },
        )
    }
}

/** 网格内的分组标题（模块应用单独成组，不和普通应用混排）。 */
@Composable
private fun GridSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Xposed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(item: AppItem, installed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val icon = item.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = item.entry.label,
                    modifier = Modifier.size(46.dp),
                )
            } else {
                Text(
                    text = (item.entry.label ?: "?").take(1),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (item.entry.module || item.entry.systemBridge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(
                            if (item.entry.systemBridge) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topStart = 6.dp, bottomEnd = 8.dp),
                        )
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (item.entry.systemBridge) "HMS" else "模块",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = item.entry.label ?: item.entry.packageName ?: "",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (installed) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyApps(tab: Int, searching: Boolean, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = when {
                searching -> "没有匹配的应用"
                tab == 0 -> "还没有添加应用"
                else -> "没有找到可克隆的应用"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (tab == 0 && !searching) "导入一个 APK，或从「本机应用」里克隆" else "换个关键词试试",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tab == 0 && !searching) {
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onPick) { Text("导入 APK") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheetContent(
    entry: AppEntry,
    onLaunch: () -> Unit,
    onInfo: () -> Unit,
    onIcon: () -> Unit,
    onRename: () -> Unit,
    onShortcut: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.label ?: "", style = MaterialTheme.typography.titleLarge)
                Text(
                    entry.packageName ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SheetRow(Icons.Default.PlayArrow, "启动应用", onLaunch)
        SheetRow(Icons.Default.Info, "应用信息", onInfo)
        SheetRow(Icons.Default.Palette, "更换图标", onIcon)
        SheetRow(Icons.Default.OpenInNew, "创建桌面快捷方式", onShortcut)
        SheetRow(Icons.Default.Stop, "强制停止", onStop)
        SheetRow(Icons.Default.Clear, "清除数据", onClear)
        SheetRow(Icons.Default.Delete, "卸载", onUninstall, danger = true)
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    )
}
