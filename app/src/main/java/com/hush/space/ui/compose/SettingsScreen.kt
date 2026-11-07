package com.hush.space.ui.compose

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hush.HushCore
import com.hush.space.BuildConfig
import com.hush.space.core.Prefs
import com.hush.space.core.SpoofManager
import androidx.compose.material3.SwitchDefaults

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: Int,
    presetId: Int,
    customSeed: Int,
    dynamicColor: Boolean,
    onThemeMode: (Int) -> Unit,
    onPreset: (Int) -> Unit,
    onSeed: (Int) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onOpenSpoof: (Boolean) -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenGuide: () -> Unit,
    onToast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var hex by remember { mutableStateOf(String.format("#%06X", 0xFFFFFF and customSeed)) }
    val userId = Prefs.userId()
    val spaceLabel = Prefs.spaceName(ctx, userId)
    val initial = remember(userId) { safeResolve(userId) }
    var hideRoot by remember(userId) { mutableStateOf(initial.hideRoot == true) }
    var hideXposed by remember(userId) { mutableStateOf(initial.hideXposed != false) }
    var daemon by remember(userId) { mutableStateOf(initial.daemon != false) }
    var language by remember(userId) { mutableStateOf(initial.language) }
    var region by remember(userId) { mutableStateOf(initial.region) }
    var timezone by remember(userId) { mutableStateOf(initial.timezone) }
    var envGlobal by remember(userId) { mutableStateOf(!hasEnvOverride(userId)) }
    var spaceGlobal by remember(userId) { mutableStateOf(!hasSpaceOverride(userId)) }
    var picker by remember { mutableStateOf<String?>(null) }
    var gms by remember { mutableStateOf(safeGms(userId)) }
    var gmsBusy by remember { mutableStateOf(false) }
    var hms by remember { mutableStateOf(safeHms(userId)) }
    var hmsBusy by remember { mutableStateOf(false) }
    var hmsVer by remember { mutableStateOf(safeHmsVersion(userId)) }
    var hmsBundledVer by remember { mutableStateOf(safeBundledHmsVersion()) }
    // 设置页直接显示全局配置文件的真实内容，用户改完能立刻看到磁盘上是什么，
    // 不用再猜「到底存没存」。
    var cfgOnDisk by remember { mutableStateOf("") }
    var cfgPath by remember { mutableStateOf("") }
    var cfgTime by remember { mutableStateOf("") }
    var spoofSummary by remember { mutableStateOf("") }

    // 保存后 / 每次进入设置页都从磁盘重新读一遍，UI 显示的永远是真正落盘的值。
    // 旧实现只在 remember 里读一次，写入失败时界面还是新值，看起来「保存了」，
    // 下次进来又变回旧值，用户只会觉得配置根本没生效。
    val reloadState: () -> Unit = {
        val s = safeResolve(userId)
        hideRoot = s.hideRoot == true
        hideXposed = s.hideXposed != false
        daemon = s.daemon != false
        language = s.language
        region = s.region
        timezone = s.timezone
        envGlobal = !hasEnvOverride(userId)
        spaceGlobal = !hasSpaceOverride(userId)
        hms = safeHms(userId)
        hmsVer = safeHmsVersion(userId)
        hmsBundledVer = safeBundledHmsVersion()
        spoofSummary = try {
            if (spaceGlobal) SpoofManager.summary(ctx, SpoofManager.GLOBAL)
            else SpoofManager.summaryOwn(ctx, userId)
        } catch (t: Throwable) {
            "未设置"
        }
        cfgPath = try {
            com.hush.core.env.SpaceSettingsStore.globalFile().absolutePath
        } catch (t: Throwable) {
            "不可用"
        }
        cfgTime = try {
            val t = com.hush.core.env.SpaceSettingsStore.lastSavedAt()
            if (t <= 0) "" else java.text.SimpleDateFormat(
                "MM-dd HH:mm:ss", java.util.Locale.CHINA
            ).format(java.util.Date(t))
        } catch (t: Throwable) {
            ""
        }
        cfgOnDisk = try {
            val dump = com.hush.core.env.SpaceSettingsStore.describeGlobal()
            val nl = dump.indexOf('\n')
            if (nl < 0) dump else dump.substring(nl + 1)
        } catch (t: Throwable) {
            "读取失败"
        }
    }
    LaunchedEffect(userId) { reloadState() }

    // 从「设备伪装」页返回时 Activity 不会重建，只重组是拿不到新值的。
    // 监听 ON_RESUME，每次回到设置页都重新读盘。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- 外观 ----------------
        item {
            SectionTitle("外观")
            SettingCard {
                Text(
                    "主题模式",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        Prefs.THEME_SYSTEM to "跟随系统",
                        Prefs.THEME_LIGHT to "浅色",
                        Prefs.THEME_DARK to "深色",
                    )
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = {
                                onThemeMode(mode)
                                AppCompatDelegate.setDefaultNightMode(
                                    when (mode) {
                                        Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                                        Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                                    }
                                )
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SwitchLine(
                    icon = Icons.Default.DarkMode,
                    title = "动态取色",
                    desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        "从系统壁纸提取配色，忽略下方预设"
                    else "需要 Android 12 及以上",
                    checked = dynamicColor,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    onChange = onDynamic,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "预设配色",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HushPresets.forEach { preset ->
                        val selected = !dynamicColor && presetId == preset.id
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    onPreset(preset.id)
                                    if (dynamicColor) onDynamic(false)
                                }
                                .padding(2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(preset.seed, CircleShape)
                                    .then(
                                        if (selected) Modifier.border(
                                            3.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                                        ) else Modifier
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                preset.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "自定义颜色",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(parseHex(hex) ?: MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { v ->
                            hex = v.take(7)
                            parseHex(hex)?.let { onSeed(it.toArgb()) }
                        },
                        singleLine = true,
                        label = { Text("HEX") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        trailingIcon = {
                            TextButton(onClick = {
                                val c = parseHex(hex)
                                if (c == null) onToast("颜色格式应为 #RRGGBB")
                                else {
                                    onSeed(c.toArgb())
                                    onToast("已应用自定义颜色")
                                }
                            }) { Text("应用") }
                        },
                    )
                }
                Text(
                    "输入 #RRGGBB，例如 #4F46E5。改动即时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ---------------- 环境 ----------------
        item {
            SectionTitle("环境")
            SettingCard {
                Text(
                    "作用范围",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ScopeRow(
                    all = envGlobal,
                    spaceLabel = spaceLabel,
                    onChange = { all ->
                        val e = safeResolve(userId)
                        val o = safeOverride(userId)
                        if (all) {
                            // 切到「全部空间」不能简单地把覆盖项删掉：那样当前空间
                            // 刚配的值会瞬间回落到旧的全局默认值，看起来就是「配置没了」。
                            // 正确做法是先把当前生效值写进全局，再清空覆盖项。
                            val g = safeGlobal()
                            g.hideRoot = e.hideRoot
                            g.hideXposed = e.hideXposed
                            g.daemon = e.daemon
                            saveGlobal(g)
                            o.hideRoot = null
                            o.hideXposed = null
                            o.daemon = null
                            o.envScoped = null
                        } else {
                            o.hideRoot = e.hideRoot
                            o.hideXposed = e.hideXposed
                            o.daemon = e.daemon
                            // 2.6.10：即使用户还没改任何值（三项都等于全局），
                            // 也要记住「选过仅本空间」。以前写入的内容全是 null，
                            // isEmpty() 直接把文件删了，开关当场弹回「全部空间」。
                            o.envScoped = true
                        }
                        val ok = saveOverride(userId, o)
                        reloadState()
                        onToast(
                            when {
                                !ok -> "保存失败：数据目录不可写"
                                all -> "已写入全部空间：这些值现在是所有空间的默认值"
                                else -> "已改为仅对「$spaceLabel」单独生效"
                            }
                        )
                    },
                )
                Text(
                    if (envGlobal) "修改后所有空间共用，未单独配置的空间都跟随这里。"
                    else "只影响「$spaceLabel」，其它空间保持各自配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SwitchLine(
                    icon = Icons.Default.Shield,
                    title = "隐藏 Root",
                    desc = "对空间内应用隐藏 su 与 Magisk 痕迹",
                    checked = hideRoot,
                    onChange = { on ->
                        if (envGlobal) {
                            val g = safeGlobal()
                            g.hideRoot = on
                            if (saveGlobal(g)) HushCore.setHideRoot(on)
                        } else {
                            val o = safeOverride(userId)
                            o.hideRoot = on
                            saveOverride(userId, o)
                        }
                        reloadState()
                        onToast(if (hideRoot == on) "已保存，重新启动空间内应用后生效"
                        else "保存失败：数据目录不可写")
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SwitchLine(
                    icon = Icons.Default.Lock,
                    title = "隐藏 Xposed",
                    desc = "隐藏模块框架痕迹",
                    checked = hideXposed,
                    onChange = { on ->
                        if (envGlobal) {
                            val g = safeGlobal()
                            g.hideXposed = on
                            if (saveGlobal(g)) HushCore.setHideXposed(on)
                        } else {
                            val o = safeOverride(userId)
                            o.hideXposed = on
                            saveOverride(userId, o)
                        }
                        reloadState()
                        onToast(if (hideXposed == on) "已保存，重新启动空间内应用后生效"
                        else "保存失败：数据目录不可写")
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SwitchLine(
                    icon = Icons.Default.Lock,
                    title = "守护服务",
                    desc = if (envGlobal) "保持虚拟进程存活，减少被系统回收"
                    else "守护服务是宿主级能力，始终按全局设置运行",
                    checked = daemon,
                    enabled = envGlobal,
                    onChange = { on ->
                        if (envGlobal) {
                            val g = safeGlobal()
                            g.daemon = on
                            if (saveGlobal(g)) HushCore.setEnableDaemonService(on)
                        }
                        reloadState()
                        onToast(if (daemon == on) "已保存" else "保存失败：数据目录不可写")
                    },
                )
            }
        }

        // ---------------- 当前空间 ----------------
        item {
            SectionTitle("当前空间")
            SettingCard {
                Text(
                    "作用范围",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ScopeRow(
                    all = spaceGlobal,
                    spaceLabel = spaceLabel,
                    onChange = { all ->
                        val e = safeResolve(userId)
                        val o = safeOverride(userId)
                        if (all) {
                            // 同上：先把当前值写进全局，再清覆盖项，避免值被「清空」。
                            val g = safeGlobal()
                            g.language = e.language
                            g.region = e.region
                            g.timezone = e.timezone
                            saveGlobal(g)
                            o.language = null
                            o.region = null
                            o.timezone = null
                            o.areaScoped = null
                        } else {
                            o.language = e.language
                            o.region = e.region
                            o.timezone = e.timezone
                            // 2.6.10：全程「跟随系统」时这三个字段都是 null，
                            // 只靠值判断会被当成空配置删掉，切换就看起来没生效。
                            o.areaScoped = true
                        }
                        val ok = saveOverride(userId, o)
                        reloadState()
                        onToast(
                            when {
                                !ok -> "保存失败：数据目录不可写"
                                all -> "已写入全部空间：区域/语言/时区成为所有空间的默认值"
                                else -> "已改为仅对「$spaceLabel」单独生效"
                            }
                        )
                    },
                )
                Text(
                    if (spaceGlobal) "机型、区域、语言、时区作为全部空间的默认值，单独配置过的空间不受影响。"
                    else "只影响「$spaceLabel」，重启该空间内的应用后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PathLine(
                    "华为 / 荣耀服务",
                    if (hms) "HMS Core ${hmsVer.ifEmpty { "" }} · 在「$spaceLabel」内独立运行"
                    else "未安装 · 在下方开关一键装进当前空间",
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                NavLine(
                    icon = Icons.Default.Smartphone,
                    title = "设备伪装",
                    desc = if (spaceGlobal) "全部空间默认：$spoofSummary"
                    else "「$spaceLabel」当前：$spoofSummary",
                    onClick = { onOpenSpoof(spaceGlobal) },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                NavLine(
                    icon = Icons.Default.Public,
                    title = "区域",
                    desc = optionLabel(REGION_OPTIONS, region),
                    onClick = { picker = "region" },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                NavLine(
                    icon = Icons.Default.Language,
                    title = "语言",
                    desc = optionLabel(LANGUAGE_OPTIONS, language),
                    onClick = { picker = "language" },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                NavLine(
                    icon = Icons.Default.Schedule,
                    title = "时区",
                    desc = optionLabel(TIMEZONE_OPTIONS, timezone),
                    onClick = { picker = "timezone" },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SwitchLine(
                    icon = Icons.Default.Palette,
                    title = "Google 服务",
                    desc = when {
                        spaceGlobal -> "仅支持单个空间安装，切换到「仅 $spaceLabel」后可操作"
                        gmsBusy -> "正在处理…"
                        else -> "为「$spaceLabel」安装 GMS"
                    },
                    checked = gms,
                    enabled = !spaceGlobal && !gmsBusy,
                    onChange = { want ->
                        gmsBusy = true
                        Thread {
                            var ok = false
                            var msg: String? = null
                            try {
                                if (want) {
                                    if (!HushCore.get().isSupportGms) {
                                        msg = "当前设备不支持安装 GMS"
                                    } else {
                                        val r = HushCore.get().installGms(userId)
                                        ok = r != null && r.success
                                        if (r != null && r.msg != null) msg = r.msg
                                    }
                                } else {
                                    ok = HushCore.get().uninstallGms(userId)
                                }
                            } catch (t: Throwable) {
                                msg = t.message
                            }
                            val success = ok
                            val note = msg
                            gmsBusy = false
                            if (success) {
                                gms = want
                                onToast(if (want) "Google 服务已就绪" else "Google 服务已移除")
                            } else {
                                onToast("操作失败：${note ?: "未知错误"}")
                            }
                        }.start()
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SwitchLine(
                    icon = Icons.Default.Shield,
                    title = "华为 HMS Core",
                    desc = when {
                        spaceGlobal -> "仅支持单个空间安装，切换到「仅 $spaceLabel」后可操作"
                        hmsBusy -> "正在处理…"
                        hms -> "已安装 ${hmsVer.ifEmpty { "未知版本" }}" +
                                (if (hmsBundledVer.isNotEmpty()) " · 内置 $hmsBundledVer" else "")
                        else -> if (hmsBundledVer.isEmpty()) "内置安装包缺失，请重新安装本应用"
                        else "内置官方 $hmsBundledVer，装进「$spaceLabel」独立运行"
                    },
                    checked = hms,
                    enabled = !spaceGlobal && !hmsBusy && (hms || hmsBundledVer.isNotEmpty()),
                    onChange = { want ->
                        hmsBusy = true
                        Thread {
                            var ok = false
                            var msg: String? = null
                            try {
                                if (want) {
                                    val r = HushCore.get().installHms(userId)
                                    ok = r != null && r.success
                                    if (r != null && r.msg != null) msg = r.msg
                                } else {
                                    ok = HushCore.get().uninstallHms(userId)
                                }
                            } catch (t: Throwable) {
                                msg = t.message
                            }
                            val success = ok
                            val note = msg
                            hmsBusy = false
                            hms = safeHms(userId)
                            hmsVer = safeHmsVersion(userId)
                            if (success) {
                                onToast(
                                    if (want) "HMS Core 已装进当前空间，空间内应用已重启，重新打开即可登录"
                                    else "HMS Core 已移除，空间内应用已重启"
                                )
                            } else {
                                onToast("操作失败：${note ?: "未知错误"}")
                            }
                        }.start()
                    },
                )
            }
        }

        // ---------------- 支持 ----------------
        item {
            SectionTitle("支持")
            SettingCard {
                NavLine(
                    icon = Icons.Default.BugReport,
                    title = "反馈与建议",
                    desc = "描述问题，可附设备与应用信息",
                    onClick = onOpenFeedback,
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                NavLine(
                    icon = Icons.Default.HelpOutline,
                    title = "功能引导",
                    desc = "重新查看多空间、伪装与模块说明",
                    onClick = onOpenGuide,
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                NavLine(
                    icon = Icons.Default.BugReport,
                    title = "导出诊断日志",
                    desc = "复制最新日志到 Download/HushSpace/export，直接发这个文件",
                    onClick = {
                        Thread {
                            val p = com.hush.space.util.DiagExport.export(ctx)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onToast(p)
                            }
                        }.start()
                    },
                )
            }
        }

        // ---------------- 存储 ----------------
        item {
            SectionTitle("存储")
            SettingCard {
                val dataRoot = remember {
                    runCatching { com.hush.core.env.BEnvironment.getDataRoot().absolutePath }
                        .getOrDefault("不可用")
                }
                val external = remember {
                    runCatching { com.hush.core.env.BEnvironment.isDataRootExternal() }
                        .getOrDefault(false)
                }
                val execRoot = remember {
                    runCatching { com.hush.core.env.BEnvironment.getVBoxRoot().absolutePath }
                        .getOrDefault("不可用")
                }
                val sdRoot = remember(userId) {
                    runCatching {
                        com.hush.core.env.BEnvironment
                            .getExternalStorageDirectory(Prefs.userId()).absolutePath
                    }.getOrDefault("不可用")
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "数据位置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (external) "外部 · 免权限" else "内部 · 外部不可用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (external) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PathLine("数据目录（配置 / 图标 / 空间设置）", dataRoot)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PathLine("程序目录（APK / so，必须内部）", execRoot)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PathLine("虚拟 SD 卡", sdRoot)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PathLine(
                    "全局配置文件",
                    cfgPath + if (cfgTime.isEmpty()) " · 尚未写入" else " · 修改于 $cfgTime",
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PathLine("全局配置内容（磁盘原文）", cfgOnDisk)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PathLine(
                    "诊断日志",
                    remember {
                        runCatching { com.hush.utils.Diag.file()?.absolutePath }
                            .getOrNull() ?: "尚未初始化"
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "数据目录位于 App 专属外部空间，不需要存储权限，卸载时随应用一起清除。" +
                        "APK 与 so 必须留在内部存储：/storage/emulated 是 noexec 挂载，从那里加载会直接失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------- 关于 ----------------
        item {
            SectionTitle("关于")
            SettingCard {
                InfoLine("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                InfoLine("运行引擎", "HushCore")
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                InfoLine("架构", abiLabel())
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                InfoLine("应用包名", ctx.packageName)
            }
        }
    }

    // 区域/语言/时区选择弹窗
    val current = when (picker) {
            "region" -> region
            "language" -> language
            "timezone" -> timezone
            else -> null
        }
        val options = when (picker) {
            "region" -> REGION_OPTIONS
            "language" -> LANGUAGE_OPTIONS
            "timezone" -> TIMEZONE_OPTIONS
            else -> emptyList()
        }
        if (picker != null) {
            val title = when (picker) {
                "region" -> "区域"
                "language" -> "语言"
                else -> "时区"
            }
            PickerDialog(
                title = title,
                options = options,
                current = current,
                onDismiss = { picker = null },
                onPick = { value ->
                    when (picker) {
                        "region" -> region = value
                        "language" -> language = value
                        else -> timezone = value
                    }
                    val saved = if (spaceGlobal) {
                        val g = safeGlobal()
                        when (picker) {
                            "region" -> g.region = value
                            "language" -> g.language = value
                            else -> g.timezone = value
                        }
                        saveGlobal(g)
                    } else {
                        val o = safeOverride(userId)
                        when (picker) {
                            "region" -> o.region = value
                            "language" -> o.language = value
                            else -> o.timezone = value
                        }
                        saveOverride(userId, o)
                    }
                    picker = null
                    reloadState()
                    // 不无条件说「已保存」：写盘失败时也要让用户当场看见。
                    onToast(
                        if (!saved) "保存失败：数据目录不可写"
                        else if (spaceGlobal) "已保存到全部空间，重启空间内应用后生效"
                        else "已保存到「$spaceLabel」，重启空间内应用后生效"
                    )
                },
            )
        }
}

// ---------------- 空间配置工具 ----------------

/** 下拉选项：值（null = 跟随系统）与显示名。 */
private val LANGUAGE_OPTIONS = listOf(
    null to "跟随系统",
    "zh" to "简体中文",
    "zh-Hant" to "繁體中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "de" to "Deutsch",
    "fr" to "Français",
    "es" to "Español",
)

private val REGION_OPTIONS = listOf(
    null to "跟随系统",
    "CN" to "中国大陆",
    "HK" to "中国香港",
    "TW" to "中国台湾",
    "MO" to "中国澳门",
    "US" to "美国",
    "JP" to "日本",
    "KR" to "韩国",
    "GB" to "英国",
    "DE" to "德国",
)

private val TIMEZONE_OPTIONS = listOf(
    null to "跟随系统",
    "Asia/Shanghai" to "北京 · UTC+8",
    "Asia/Hong_Kong" to "中国香港 · UTC+8",
    "Asia/Taipei" to "中国台北 · UTC+8",
    "Asia/Tokyo" to "东京 · UTC+9",
    "Asia/Seoul" to "首尔 · UTC+9",
    "Europe/London" to "伦敦 · UTC+0",
    "Europe/Berlin" to "柏林 · UTC+1",
    "America/New_York" to "纽约 · UTC-5",
    "America/Los_Angeles" to "洛杉矶 · UTC-8",
    "UTC" to "协调世界时 UTC",
)

private fun optionLabel(options: List<Pair<String?, String>>, value: String?): String =
    options.firstOrNull { it.first == value }?.second ?: (value ?: "跟随系统")

private fun safeResolve(userId: Int): com.hush.entity.SpaceSettings = try {
    HushCore.get().resolveSpaceSettings(userId)
} catch (t: Throwable) {
    com.hush.entity.SpaceSettings()
}

private fun safeGlobal(): com.hush.entity.SpaceSettings = try {
    HushCore.get().getGlobalSpaceSettings()
} catch (t: Throwable) {
    com.hush.entity.SpaceSettings()
}

private fun safeOverride(userId: Int): com.hush.entity.SpaceSettings = try {
    HushCore.get().getSpaceSettings(userId) ?: com.hush.entity.SpaceSettings()
} catch (t: Throwable) {
    com.hush.entity.SpaceSettings()
}

private fun saveGlobal(s: com.hush.entity.SpaceSettings): Boolean = try {
    HushCore.get().setGlobalSpaceSettings(s)
} catch (t: Throwable) {
    false
}

private fun saveOverride(userId: Int, s: com.hush.entity.SpaceSettings): Boolean = try {
    if (s.isEmpty) {
        HushCore.get().clearSpaceSettings(userId)
        true
    } else {
        HushCore.get().setSpaceSettings(userId, s)
    }
} catch (t: Throwable) {
    false
}

private fun hasEnvOverride(userId: Int): Boolean {
    val o = safeOverride(userId)
    // 作用域标记优先：用户点过「仅本空间」就该一直停在这个状态，
    // 不能因为他暂时没改值（三项都等于全局）就自动跳回「全部空间」。
    return o.envScoped != null || o.hideRoot != null || o.hideXposed != null || o.daemon != null
}

private fun hasSpaceOverride(userId: Int): Boolean {
    val o = safeOverride(userId)
    return o.areaScoped != null || o.language != null || o.region != null || o.timezone != null
}

@Composable
private fun ScopeRow(all: Boolean, spaceLabel: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = all,
            onClick = { if (!all) onChange(true) },
            label = { Text("全部空间") },
        )
        FilterChip(
            selected = !all,
            onClick = { if (all) onChange(false) },
            label = { Text("仅 $spaceLabel") },
        )
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String?, String>>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.height(360.dp)) {
                items(options, key = { it.first ?: "__system__" }) { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (value == current) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun safeGms(uid: Int): Boolean = try {
    HushCore.get().isInstallGms(uid)
} catch (t: Throwable) {
    false
}

private fun safeHms(uid: Int): Boolean = try {
    HushCore.get().isInstallHms(uid)
} catch (t: Throwable) {
    false
}

private fun safeHmsVersion(uid: Int): String = try {
    HushCore.get().getInstalledHmsVersion(uid)
} catch (t: Throwable) {
    ""
}

private fun safeBundledHmsVersion(): String = try {
    HushCore.get().bundledHmsVersion
} catch (t: Throwable) {
    ""
}

private fun abiLabel(): String {
    val abis = Build.SUPPORTED_ABIS ?: return "-"
    return abis.take(2).joinToString(" / ")
}

private fun parseHex(v: String): Color? {
    val s = v.trim().removePrefix("#")
    if (s.length != 6) return null
    return try {
        Color(0xFF000000 or s.toLong(16))
    } catch (t: Throwable) {
        null
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun SwitchLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(),
        )
    }
}

@Composable
private fun NavLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.ColorLens,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 路径行：标题在上、完整路径在下，长路径不会被右侧挤掉。 */
@Composable
private fun PathLine(label: String, path: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}


