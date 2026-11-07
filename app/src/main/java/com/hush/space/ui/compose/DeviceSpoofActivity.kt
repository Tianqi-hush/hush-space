package com.hush.space.ui.compose

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hush.space.R
import com.hush.space.core.DeviceProfile
import com.hush.space.core.Prefs
import com.hush.space.core.SpoofManager

/** 设备伪装编辑页（Compose 版），按空间保存；extra_spoof_global=true 时编辑全局默认。 */
class DeviceSpoofActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val global = intent.getBooleanExtra("extra_spoof_global", false)
        val userId = if (global) SpoofManager.GLOBAL else Prefs.userId()
        setContent {
            HushThemeFromPrefs {
                SpoofScreen(userId = userId, onBack = { finish() })
            }
        }
    }
}

private data class SpoofPreset(
    val label: String,
    val brand: String,
    val model: String,
    val manufacturer: String,
    val device: String,
    val serial: String,
    val fingerprint: String,
)

private val SpoofPresets = listOf(
    SpoofPreset(
        "小米 14", "Xiaomi", "23127PN0CC", "Xiaomi", "houji", "8C1X9A2F7D",
        "Xiaomi/houji/houji:14/UKQ1.230804.001/V816.0.4.0:user/release-keys",
    ),
    SpoofPreset(
        "三星 S23", "samsung", "SM-S911B", "samsung", "dm3q", "RF8W92KDF3X",
        "samsung/dm3q/dm3q:14/UP1A.231005.007/S911BXXU3BWLM:user/release-keys",
    ),
    SpoofPreset(
        "OPPO Find X6", "OPPO", "PGFM10", "OPPO", "OP5D2D", "OP7A3C55D9",
        "OPPO/PGFM10/OP5D2D:14/UKQ1.231003.002/1707371509:user/release-keys",
    ),
    SpoofPreset(
        "华为 Mate 60 Pro", "HUAWEI", "ALN-AL00", "HUAWEI", "ALN", "5NF7N24609001234",
        "HUAWEI/ALN-AL00/HWALN:12/HUAWEIALN-AL00/102.0.0.1:user/release-keys",
    ),
    SpoofPreset(
        "荣耀 Magic6 Pro", "HONOR", "BVL-AN16", "HONOR", "BVL", "6PG7N24611005678",
        "HONOR/BVL-AN16/HWBVL:14/HONORBVL-AN16/8.0.0.1:user/release-keys",
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpoofScreen(userId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember { SpoofManager.load(ctx, userId) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var brand by remember { mutableStateOf(initial.brand) }
    var model by remember { mutableStateOf(initial.model) }
    var manufacturer by remember { mutableStateOf(initial.manufacturer) }
    var device by remember { mutableStateOf(initial.device) }
    var serial by remember { mutableStateOf(initial.serial) }
    var fingerprint by remember { mutableStateOf(initial.fingerprint) }
    var preset by remember { mutableStateOf(-1) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    HushPage(
        title = stringResource(R.string.spoof_title),
        subtitle = stringResource(
            R.string.spoof_target,
            if (userId == SpoofManager.GLOBAL) "全部空间（默认）" else Prefs.spaceName(ctx, userId)
        ),
        onBack = onBack,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.spoof_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            HushCard {
                HushRow(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.spoof_enable),
                    desc = stringResource(R.string.spoof_enable_desc),
                    trailing = {
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    },
                    onClick = { enabled = !enabled },
                )
            }

            SectionTitle(stringResource(R.string.spoof_preset))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpoofPresets.forEachIndexed { i, p ->
                    FilterChip(
                        selected = preset == i,
                        onClick = {
                            preset = i
                            brand = p.brand
                            model = p.model
                            manufacturer = p.manufacturer
                            device = p.device
                            serial = p.serial
                            fingerprint = p.fingerprint
                            enabled = true
                        },
                        label = { Text(p.label) },
                    )
                }
            }

            SectionTitle("设备信息")

            SpoofField(stringResource(R.string.spoof_brand), brand) { brand = it }
            SpoofField(stringResource(R.string.spoof_model), model) { model = it }
            SpoofField(stringResource(R.string.spoof_manufacturer), manufacturer) { manufacturer = it }
            SpoofField(stringResource(R.string.spoof_device), device) { device = it }
            SpoofField(stringResource(R.string.spoof_serial), serial) { serial = it }
            SpoofField(
                stringResource(R.string.spoof_fingerprint),
                fingerprint,
                singleLine = false,
            ) { fingerprint = it }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val p = DeviceProfile()
                    p.enabled = enabled
                    p.brand = brand.trim()
                    p.model = model.trim()
                    p.manufacturer = manufacturer.trim()
                    p.device = device.trim()
                    p.serial = serial.trim()
                    p.fingerprint = fingerprint.trim()
                    SpoofManager.save(ctx, userId, p)
                    toast(ctx.getString(R.string.spoof_saved))
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.spoof_save))
            }

            OutlinedButton(
                onClick = {
                    val p = DeviceProfile()
                    SpoofManager.save(ctx, userId, p)
                    enabled = false
                    brand = ""
                    model = ""
                    manufacturer = ""
                    device = ""
                    serial = ""
                    fingerprint = ""
                    preset = -1
                    toast(ctx.getString(R.string.spoof_reset))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.spoof_reset))
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SpoofField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    )
}
