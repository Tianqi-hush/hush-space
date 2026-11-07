package com.hush.space.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hush.space.BuildConfig
import com.hush.space.R
import com.hush.space.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 反馈页（Compose 版）：本地生成一份带环境信息的文本，用户自行复制或分享。
 *
 * 不内置任何上报接口，避免默认联网。
 */
class FeedbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HushThemeFromPrefs { FeedbackScreen(onBack = { finish() }) }
        }
    }
}

private val Types = listOf("问题", "建议", "兼容性", "其他")

@Composable
private fun FeedbackScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var attachEnv by remember { mutableStateOf(true) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun buildBody(): String? {
        val body = text.trim()
        if (body.isEmpty()) {
            toast(ctx.getString(R.string.feedback_empty))
            return null
        }
        val sb = StringBuilder()
        sb.append("【类型】").append(Types[type]).append('\n')
        sb.append("【内容】").append(body).append('\n')
        if (attachEnv) sb.append('\n').append(envInfo(ctx))
        return sb.toString()
    }

    HushPage(
        title = stringResource(R.string.feedback_title),
        onBack = onBack,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.feedback_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Types.forEachIndexed { i, label ->
                    FilterChip(
                        selected = type == i,
                        onClick = { type = i },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.feedback_hint)) },
                minLines = 7,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            HushCard {
                HushRow(
                    title = stringResource(R.string.feedback_attach_env),
                    desc = "版本、系统、机型与当前空间",
                    trailing = {
                        Switch(checked = attachEnv, onCheckedChange = { attachEnv = it })
                    },
                    onClick = { attachEnv = !attachEnv },
                )
            }

            Spacer(Modifier.height(22.dp))

            Button(
                onClick = {
                    val body = buildBody() ?: return@Button
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("feedback", body))
                    toast(ctx.getString(R.string.copy_done))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text(stringResource(R.string.feedback_copy))
            }

            OutlinedButton(
                onClick = {
                    val body = buildBody() ?: return@OutlinedButton
                    val i = Intent(Intent.ACTION_SEND).apply {
                        this.type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.feedback_mail_subject))
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    try {
                        ctx.startActivity(
                            Intent.createChooser(i, ctx.getString(R.string.feedback_share))
                        )
                    } catch (t: Throwable) {
                        toast(ctx.getString(R.string.feedback_share_fail))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(top = 10.dp),
            ) {
                Text(stringResource(R.string.feedback_share))
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun envInfo(ctx: Context): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
    return "----- 环境信息 -----\n" +
            "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Brand: ${Build.BRAND} / ${Build.MANUFACTURER}\n" +
            "Model: ${Build.MODEL}\n" +
            "ABI: ${if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "-"}\n" +
            "Space: ${Prefs.spaceName(ctx, Prefs.userId())} (id=${Prefs.userId()})\n" +
            "Time: $time"
}
