package com.hush.space.ui.compose

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hush.HushCore
import com.hush.space.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SpaceRow(
    val id: Int,
    val name: String,
    val appCount: Int,
    val current: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(
    currentUserId: Int,
    onSwitched: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<SpaceRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SpaceRow?>(null) }

    LaunchedEffect(currentUserId, reload) {
        loading = true
        rows = withContext(Dispatchers.IO) {
            val list = mutableListOf<SpaceRow>()
            // 先看模块空间里到底有没有东西。引擎的 user.conf 里始终存在
            // id=-4（模块本体）和 id=99（模块 App 空间），如果直接按
            // getUsers() 铺列表，用户会看到「什么都没装却多出两个空间」。
            val moduleCount = try {
                HushCore.getBPackageManager()
                    .getInstalledPackagesAsUser(Prefs.USER_MODULE).size
            } catch (t: Throwable) {
                0
            }
            // 当前空间如果是模块空间、但模块空间已经空了，回退到主空间，
            // 否则用户会停在一条列表里根本看不到的「幽灵空间」上。
            if (Prefs.userId() == Prefs.USER_MODULE && moduleCount == 0) {
                Prefs.setUserId(0)
            }
            try {
                val users = HushCore.get().getUsers()
                if (users != null) {
                    for (u in users) {
                        // 模块空间没有内容时直接不显示
                        if (u.id == Prefs.USER_MODULE && moduleCount == 0) continue
                        val count = try {
                            HushCore.getBPackageManager()
                                .getInstalledPackagesAsUser(u.id).size
                        } catch (t: Throwable) {
                            0
                        }
                        list.add(
                            SpaceRow(
                                id = u.id,
                                name = Prefs.spaceName(ctx, u.id),
                                appCount = count,
                                current = u.id == Prefs.userId(),
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                // 引擎未就绪时退化为单条主空间，界面不至于空白
            }
            // 模块空间只是模块 App 的运行容器。没有装任何模块应用时它不该
            // 占一行，否则用户只会看到「凭空多出来一个删不掉的空间」。
            if (moduleCount > 0 && list.none { it.id == Prefs.USER_MODULE }) {
                list.add(
                    SpaceRow(
                        id = Prefs.USER_MODULE,
                        name = "模块空间",
                        appCount = moduleCount,
                        current = Prefs.userId() == Prefs.USER_MODULE,
                    )
                )
            }
            if (list.isEmpty()) {
                list.add(
                    SpaceRow(
                        id = 0,
                        name = Prefs.spaceName(ctx, 0),
                        appCount = 0,
                        current = Prefs.userId() == 0,
                    )
                )
            }
            list.sortedBy { if (it.id == Prefs.USER_MODULE) Int.MIN_VALUE else it.id }
        }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = "每个空间拥有独立的存储、账号与运行环境，可并行运行同一个应用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
                items(rows, key = { it.id }) { row ->
                    Card(
                        onClick = {
                            if (row.current) return@Card
                            if (row.id == Prefs.USER_MODULE) {
                                try {
                                    HushCore.get().createUser(Prefs.USER_MODULE)
                                } catch (t: Throwable) {
                                }
                            }
                            Prefs.setUserId(row.id)
                            onToast("已切换到 ${row.name}")
                            onSwitched()
                        },
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (row.current) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .background(
                                        if (row.current) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = if (row.current) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = row.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (row.current) {
                                        Spacer(Modifier.size(6.dp))
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "当前空间",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    text = "${row.appCount} 个应用" +
                                        (if (row.current) " · 当前空间" else "") +
                                        (if (row.id == Prefs.USER_MODULE) " · 模块专用"
                                        else if (row.id == 0) " · 主空间" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (row.id != 0) {
                                IconButton(onClick = { pendingDelete = row }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除空间",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = {
                newName = ""
                creating = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("新建空间") },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建空间") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("空间名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    val name = newName.trim()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                // 只递增普通空间号，跳过 99（模块空间专用），
                                // 否则新建出来的空间会跑到 100，用户既看不到也删不掉。
                                val used = rows.map { it.id }.toHashSet()
                                var next = 1
                                while (used.contains(next) || next == Prefs.USER_MODULE) next++
                                HushCore.get().createUser(next)
                                Prefs.setSpaceName(
                                    next,
                                    name.ifEmpty { ctx.getString(com.hush.space.R.string.space_default_name, next) }
                                )
                                true
                            } catch (t: Throwable) {
                                false
                            }
                        }
                        if (ok) {
                            onToast("空间已创建")
                            reload++
                        } else {
                            onToast("创建失败")
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除空间") },
            text = {
                Text(
                    if (row.id == Prefs.USER_MODULE) "清空「模块空间」里安装的模块应用？模块本体不会被删除。"
                    else "删除「${row.name}」及其全部应用数据？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                if (row.id == Prefs.USER_MODULE) {
                                    // 模块空间不能真正 deleteUser（会连带删掉模块本体），
                                    // 这里只清掉装进去的模块 App 数据。
                                    val pkgs = try {
                                        HushCore.getBPackageManager()
                                            .getInstalledPackagesAsUser(Prefs.USER_MODULE)
                                    } catch (t: Throwable) {
                                        null
                                    }
                                    pkgs?.forEach {
                                        try {
                                            HushCore.get()
                                                .uninstallPackageAsUser(it.packageName, Prefs.USER_MODULE)
                                        } catch (t: Throwable) {
                                        }
                                    }
                                } else {
                                    HushCore.get().deleteUser(row.id)
                                }
                            } catch (t: Throwable) {
                                // 引擎侧失败也要清掉本地记录，否则会留下幽灵条目
                            }
                            Prefs.clearSpaceName(row.id)
                            if (Prefs.userId() == row.id) Prefs.setUserId(0)
                        }
                        onToast("空间已删除")
                        onSwitched()
                        reload++
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(onOpenManager: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Xposed 模块",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "为空间内的应用加载模块。导入模块 APK 后，可逐个应用控制是否生效。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Card(
            onClick = onOpenManager,
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Layers, contentDescription = null)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("模块管理", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "导入、启用或移除模块",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "打开",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenManager() },
                )
            }
        }

        Text(
            "提示：部分应用会检测模块框架。若目标应用启动异常，可在设置中开启「隐藏 Xposed」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
