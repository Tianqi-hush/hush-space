package com.hush.space.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hush.HushCore
import com.hush.space.R
import com.hush.space.core.AppEntry
import com.hush.space.core.AppRepo
import com.hush.space.core.IconStore
import com.hush.space.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 应用详情（Compose 版）。 */
class AppInfoActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_USER_ID = "user_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val userId = intent.getIntExtra(EXTRA_USER_ID, Prefs.userId())

        if (pkg == null) {
            finish()
            return
        }

        setContent {
            HushThemeFromPrefs {
                AppInfoScreen(
                    packageName = pkg,
                    userId = userId,
                    onBack = { finish() },
                )
            }
        }
    }
}

private data class InfoRow(val label: String, val value: String)

@Composable
private fun AppInfoScreen(packageName: String, userId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var entry by remember { mutableStateOf(AppEntry(packageName).also { it.cloned = true }) }
    var version by remember { mutableStateOf("-") }
    var sizeText by remember { mutableStateOf("-") }
    var targetSdk by remember { mutableStateOf("-") }
    var running by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf(IconStore.label(ctx, packageName) ?: packageName) }
    var refresh by remember { mutableStateOf(0) }

    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var iconTick by remember { mutableStateOf(0) }

    // 从裁剪页返回时重读图标与运行状态，否则换了图标还要手动退出重进。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                iconTick++
                refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 包信息读取要跨进程，放 IO；读不到就退回列表页。
    produceState(Unit, packageName, refresh) {
        val info: PackageInfo? = withContext(Dispatchers.IO) {
            try {
                HushCore.getBPackageManager().getPackageInfo(packageName, 0, userId)
            } catch (t: Throwable) {
                null
            }
        }
        if (info == null) return@produceState
        val ai = info.applicationInfo
        version = info.versionName ?: "-"
        targetSdk = ai?.targetSdkVersion?.toString() ?: "-"
        val src = ai?.sourceDir
        val len = if (src != null) File(src).length() else 0L
        sizeText = if (len > 0) AppRepo.formatSize(len) else "-"
        entry = AppEntry(packageName).also {
            it.cloned = true
            it.versionName = version
            it.apkPath = src
            it.iconRes = ai?.icon ?: 0
            it.labelRes = ai?.labelRes ?: 0
            it.size = len
        }
    }

    produceState(Unit, packageName, userId, refresh) {
        running = withContext(Dispatchers.IO) {
            try {
                AppRepo.isRunning(packageName, userId)
            } catch (t: Throwable) {
                false
            }
        }
    }

    // AppEntry 的 equals 只比包名，拿它当 key 换图标时不会重算，所以用显式的 tick。
    val icon = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, iconTick, refresh) {
        value = withContext(Dispatchers.IO) {
            try {
                val d = AppRepo.loadIcon(ctx, entry)
                AppRepo.toBitmap(d, 144)?.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    val iconLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
            // 某些来源不授予持久权限，裁剪仍可继续
        }
        val i = Intent(ctx, IconCropActivity::class.java)
        i.putExtra(IconCropActivity.EXTRA_PACKAGE, packageName)
        i.putExtra(IconCropActivity.EXTRA_IMAGE, uri.toString())
        ctx.startActivity(i)
    }

    HushPage(
        title = displayName,
        subtitle = packageName,
        onBack = onBack,
        actions = {
            androidx.compose.material3.IconButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("package", packageName))
                toast(ctx.getString(R.string.copy_done))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制包名")
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            // ---- 头部 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = icon.value
                    if (bmp != null) {
                        Image(bmp, contentDescription = null, modifier = Modifier.size(52.dp))
                    } else {
                        Icon(
                            Icons.Default.Widgets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- 信息 ----
            HushCard {
                InfoLine(stringResource(R.string.info_version), version)
                InfoLine(stringResource(R.string.info_space), Prefs.spaceName(ctx, userId))
                InfoLine(stringResource(R.string.info_size), sizeText)
                InfoLine(stringResource(R.string.info_target), targetSdk)
                InfoLine(
                    stringResource(R.string.info_state),
                    stringResource(if (running) R.string.state_running else R.string.state_stopped),
                )
            }

            Spacer(Modifier.height(18.dp))

            // ---- 常用操作 ----
            HushCard {
                ActionRow(Icons.Default.PlayArrow, stringResource(R.string.act_launch)) {
                    val ok = AppRepo.launch(ctx, packageName, userId)
                    if (!ok) toast(ctx.getString(R.string.launch_fail))
                }
                ActionRow(Icons.Default.Widgets, stringResource(R.string.act_shortcut)) {
                    val ok = AppRepo.createShortcut(ctx, entry, userId)
                    toast(ctx.getString(if (ok) R.string.shortcut_done else R.string.shortcut_fail))
                }
                ActionRow(Icons.Default.Image, stringResource(R.string.act_change_icon)) {
                    iconLauncher.launch(arrayOf("image/*"))
                }
                ActionRow(Icons.Default.Edit, stringResource(R.string.act_rename)) {
                    renameText = displayName
                    renaming = true
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- 危险操作 ----
            // 确认文案在进入 lambda 前先取好：onClick 不是 @Composable，里面不能调 stringResource。
            val stopMsg = stringResource(R.string.confirm_stop, displayName)
            val clearMsg = stringResource(R.string.confirm_clear, displayName)
            val uninstallMsg = stringResource(R.string.confirm_uninstall, displayName)

            HushCard {
                ActionRow(Icons.Default.Stop, stringResource(R.string.act_stop)) {
                    confirm = stopMsg to {
                        scope.launch {
                            withContext(Dispatchers.IO) { AppRepo.stop(packageName, userId) }
                            toast(ctx.getString(R.string.stop_done))
                            refresh++
                        }
                    }
                }
                ActionRow(Icons.Default.Delete, stringResource(R.string.act_clear)) {
                    confirm = clearMsg to {
                        scope.launch {
                            withContext(Dispatchers.IO) { AppRepo.clearData(packageName, userId) }
                            toast(ctx.getString(R.string.clear_done))
                        }
                    }
                }
                ActionRow(
                    Icons.Default.Delete,
                    stringResource(R.string.act_uninstall),
                    danger = true,
                ) {
                    confirm = uninstallMsg to {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AppRepo.uninstall(packageName, userId)
                                IconStore.clear(ctx, packageName)
                            }
                            toast(ctx.getString(R.string.uninstall_done))
                            onBack()
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // ---- 重命名 ----
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.act_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    IconStore.setLabel(ctx, packageName, renameText.trim())
                    AppRepo.invalidateIcon(packageName)
                    displayName = IconStore.label(ctx, packageName) ?: packageName
                    refresh++
                    iconTick++
                    renaming = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    IconStore.setLabel(ctx, packageName, null)
                    AppRepo.invalidateIcon(packageName)
                    displayName = IconStore.label(ctx, packageName) ?: packageName
                    refresh++
                    iconTick++
                    renaming = false
                }) { Text(stringResource(R.string.rename_reset)) }
            },
        )
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    action()
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    HushRow(
        icon = icon,
        iconTint = tint,
        title = text,
        onClick = onClick,
        trailing = {
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}
