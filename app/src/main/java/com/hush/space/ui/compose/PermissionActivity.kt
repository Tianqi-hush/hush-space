package com.hush.space.ui.compose

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hush.space.R
import com.hush.space.core.Prefs

/** 首次启动的权限引导（Compose 版）。 */
class PermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HushThemeFromPrefs { PermissionScreen(onDone = { openMain() }) }
        }
    }

    private fun openMain() {
        Prefs.setPermissionAsked()
        startActivity(Intent(this, HushMainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

@Composable
private fun PermissionScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    // 从系统设置返回时权限可能已变，每次 resume 重新算一遍。
    var tick by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 拒绝也不拦：通知只是锦上添花，权限页本身可以直接跳过。
        tick++
        onDone()
    }

    val storageOk = remember(tick) { hasStorage(ctx) }
    val notifyOk = remember(tick) { hasNotify(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 44.dp, bottom = 26.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(18.dp))
        PageIntro(
            title = stringResource(R.string.perm_title),
            desc = stringResource(R.string.perm_desc),
        )

        Spacer(Modifier.height(22.dp))

        HushCard {
            PermRow(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.perm_storage),
                desc = stringResource(R.string.perm_storage_desc),
                granted = storageOk,
            )
            PermRow(
                icon = Icons.Default.InstallMobile,
                title = stringResource(R.string.perm_install),
                desc = stringResource(R.string.perm_install_desc),
                granted = true,
            )
            PermRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.perm_notify),
                desc = stringResource(R.string.perm_notify_desc),
                granted = notifyOk,
            )
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                // 存储权限完全可选：数据默认落在 App 专属外部目录
                // （Android/data/<pkg>/files/space），系统按包名授权，
                // 不需要 READ/WRITE_EXTERNAL_STORAGE，更不需要「所有文件访问」。
                // 这里只顺手要一下通知权限，用户不想给也不拦。
                val perms = buildList {
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (perms.isEmpty()) onDone() else permLauncher.launch(perms.toTypedArray())
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.perm_go))
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Text(stringResource(R.string.perm_skip))
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "权限只用于读取本地安装包与展示运行状态，不会上传任何内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermRow(icon: ImageVector, title: String, desc: String, granted: Boolean) {
    HushRow(
        icon = icon,
        title = title,
        desc = desc,
        trailing = {
            Text(
                text = if (granted) stringResource(R.string.perm_granted) else "待授权",
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun hasStorage(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
    return if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }
}

private fun hasNotify(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}


