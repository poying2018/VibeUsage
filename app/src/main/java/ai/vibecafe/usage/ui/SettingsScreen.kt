package ai.vibecafe.usage.ui
import ai.vibecafe.usage.budget.BudgetNotify
import ai.vibecafe.usage.core.GlassStyleStore
import ai.vibecafe.usage.core.ThemeMode
import ai.vibecafe.usage.data.CredentialBackup
import ai.vibecafe.usage.share.ShareCard
import ai.vibecafe.usage.ui.ag.ExtraQuotaViewModel
import ai.vibecafe.usage.ui.anim.fadeSlideIn
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.LiquidSlider
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.glass.glassTile
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.util.Locale

/**
 * 独立设置页（底部导航第 3 个 Tab）。
 *
 * 整页滚动布局，分组卡片式信息架构：外观 / 背景 / 液态玻璃 / 通用（更新·分享）/ 账号。
 * 每组一张全宽玻璃卡，行内项目带图标底座 + 标题 + 说明 + 右箭头，
 * 分组标题在卡外，错峰滑入。
 */
@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    showReset: Boolean,
    onCustomBackground: () -> Unit,
    onResetBackground: () -> Unit,
    onShareCard: () -> Unit,
    onLogout: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    appVersion: String
) {
    val p = LocalGlassPalette.current
    // 设置页要读写 prefs / 落文件，用自己的 Context 引用（与下面卡片内的局部 context 区分开）
    val appContext = LocalContext.current
    // 与 DashboardScreen 同一个 MainViewModel 实例（同一 Activity 作用域 + 同默认 key），
    // 用它的当前统计数据来生成「保存到相册」用的分享卡
    val vm: MainViewModel = viewModel()
    // 预算提醒阈值：与 BudgetNotify 共用同一份 prefs，改完立即生效
    var threshold by remember { mutableStateOf(BudgetNotify.threshold(appContext)) }
    var thresholdCustom by remember { mutableStateOf(BudgetNotify.hasCustomThreshold(appContext)) }
    var showThresholdDialog by remember { mutableStateOf(false) }
    // —— 凭据备份：状态挂在页面级，口令弹窗在 Column 之外也要用 ——
    val extraVm: ExtraQuotaViewModel = viewModel()
    // 口令只在流程进行中暂存：导出时用户输入一次，导入时输入一次。
    // 备份文件本身不含口令，导出成功后口令就只留在用户脑子里了。
    var exportPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var passphraseMode by remember { mutableStateOf<PassphraseMode?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            val text = CredentialBackup.export(appContext, exportPassphrase)
                ?: throw IllegalStateException("还没有已接入的凭据")
            appContext.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray())
            } ?: throw IllegalStateException("写不进所选位置")
        }.onSuccess {
            Toast.makeText(
                appContext,
                "已导出加密备份（口令请自行保管，遗失将无法恢复）",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure {
            Toast.makeText(appContext, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
        exportPassphrase = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            val text = appContext.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            } ?: throw IllegalStateException("读不到所选文件")
            // 口令加密的备份要问口令；老备份直接还原
            if (CredentialBackup.needsPassphrase(text)) {
                pendingImportText = text
                passphraseMode = PassphraseMode.IMPORT
                return@runCatching 0
            }
            val restored = CredentialBackup.import(appContext, text)
            extraVm.onCredsImported()
            restored
        }.onSuccess {
            if (it > 0) {
                Toast.makeText(appContext, "已恢复 $it 项凭据，正在刷新额度", Toast.LENGTH_LONG).show()
            }
        }.onFailure {
            Toast.makeText(appContext, "导入失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .fadeSlideIn(0),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // —— 页头：大标题 + 副标题（与仪表盘 Header 同级排版）——
        Column(Modifier.padding(top = 4.dp)) {
            Text(
                "设置",
                style = GlassText.Title,
                fontSize = 27.sp,
                color = p.InkHi
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "个性化 · 通用 · 账号",
                style = GlassText.Label,
                fontSize = 12.sp,
                color = p.InkMid
            )
        }
        // —— 外观 ——
        SectionLabel("外观")
        // 卡片把纯玻璃表面导出为 backdrop；卡内玻璃芯片取样「页面 + 卡片表面」组合层，
        // 折射/模糊到真实盖住的内容（exportedBackdrop 不含内容层，无自引用递归风险）
        val lookCardSurface = rememberLayerBackdrop()
        val chipBackdrop = rememberCombinedBackdrop(backdrop, lookCardSurface)
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(40)
                .glassCard(backdrop, cornerRadius = 26.dp, exportedBackdrop = lookCardSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBase(Icons.Filled.DarkMode)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("主题模式", style = GlassText.Body)
                    Text(
                        "当前：${themeMode.label}",
                        style = GlassText.Meta,
                        color = p.InkMid
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    ThemeChip(
                        backdrop = chipBackdrop,
                        mode = mode,
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        // —— 背景 ——
        SectionLabel("背景")
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(80)
                .glassCard(backdrop, cornerRadius = 26.dp)
        ) {
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.PhotoLibrary,
                title = "自定义背景",
                subtitle = "从相册选择一张图片铺满页面",
                onClick = onCustomBackground
            )
            if (showReset) {
                CardDivider()
                SettingRow(
                    backdrop = backdrop,
                    icon = Icons.Filled.RestartAlt,
                    title = "恢复默认背景",
                    subtitle = "清除当前背景图",
                    onClick = onResetBackground
                )
            }
        }
        // —— 液态玻璃（catalog 完整配方，全局实时生效）——
        SectionLabel("液态玻璃")
        // 滑杆/滑块是卡内嵌套玻璃：取样「页面 + 卡片表面」组合层（同外观卡）
        val styleCardSurface = rememberLayerBackdrop()
        val sliderBackdrop = rememberCombinedBackdrop(backdrop, styleCardSurface)
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(120)
                .glassCard(backdrop, cornerRadius = 26.dp, exportedBackdrop = styleCardSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBase(Icons.Filled.Tune)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("液态玻璃", style = GlassText.Body)
                    Text(
                        "折射 + 磨砂配方 · 全部玻璃面板实时生效",
                        style = GlassText.Meta,
                        color = p.InkMid
                    )
                }
            }
            val style = GlassStyleStore.style
            val context = LocalContext.current
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "亮度",
                valueText = String.format(Locale.US, "%.2f", style.brightness),
                value = style.brightness,
                valueRange = -0.5f..0.5f,
                onChange = { GlassStyleStore.set(context, style.copy(brightness = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "对比度",
                valueText = String.format(Locale.US, "%.2f", style.contrast),
                value = style.contrast,
                valueRange = 0.5f..1.5f,
                onChange = { GlassStyleStore.set(context, style.copy(contrast = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "饱和度",
                valueText = String.format(Locale.US, "%.2f", style.saturation),
                value = style.saturation,
                valueRange = 0f..2f,
                onChange = { GlassStyleStore.set(context, style.copy(saturation = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "模糊",
                valueText = "${style.blurCardDp.toInt()}dp",
                value = style.blurCardDp,
                valueRange = 0f..32f,
                onChange = { GlassStyleStore.set(context, style.copy(blurCardDp = it)) }
            )
            GlassStyleSliderRow(
                backdrop = sliderBackdrop,
                label = "白色叠加",
                valueText = "${(style.whiteOverlay * 100).toInt()}%",
                value = style.whiteOverlay,
                valueRange = 0f..0.5f,
                onChange = { GlassStyleStore.set(context, style.copy(whiteOverlay = it)) }
            )
            // 「恢复默认」是重置操作，之前紧跟第 5 个滑杆、同一列连着排，
            // 视觉上和调节滑杆混作一排。补一条发丝线把两者分开。
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                thickness = 1.dp,
                color = p.InkLo.copy(alpha = 0.28f)
            )
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.RestartAlt,
                title = "恢复默认",
                subtitle = "还原默认玻璃参数",
                showChevron = false,
                onClick = { GlassStyleStore.set(context, GlassStyleStore.Default) }
            )
        }
        // —— 通用 ——
        SectionLabel("通用")
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(160)
                .glassCard(backdrop, cornerRadius = 26.dp)
        ) {
            // 软件更新（随流程阶段切换内容）
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBase(Icons.Filled.SystemUpdate)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("软件更新", style = GlassText.Body)
                        Text(
                            "当前版本 $appVersion",
                            style = GlassText.Meta,
                            color = p.InkMid
                        )
                    }
                }
                when (update.status) {
                    UpdateStatus.IDLE -> {}
                    UpdateStatus.CHECKING -> UpdateHint("正在检查更新…", p.InkMid)
                    UpdateStatus.UP_TO_DATE -> {
                        UpdateHint(update.message ?: "已是最新版本", p.AccentInk)
                    }
                    UpdateStatus.AVAILABLE -> {
                        UpdateHint(
                            "发现新版本 ${update.version?.removePrefix("v")}",
                            p.InkHi
                        )
                    }
                    UpdateStatus.DOWNLOADING -> {
                        UpdateHint("正在下载… ${(update.progress * 100).toInt()}%", p.InkMid)
                        LinearProgressIndicator(
                            progress = { update.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = p.Accent,
                            trackColor = p.InkLo.copy(alpha = 0.5f)
                        )
                    }
                    UpdateStatus.DOWNLOADED -> {
                        UpdateHint(
                            "${update.version?.removePrefix("v")} 已下载",
                            p.AccentInk
                        )
                    }
                    // 发现新版本但该版本尚未发布带 APK 的 Release（只打了 tag）
                    UpdateStatus.NO_APK -> {
                        Text(
                            update.message ?: "发现新版本，安装包暂未发布",
                            style = GlassText.Meta,
                            color = p.InkMid,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    UpdateStatus.FAILED -> {
                        update.message?.let { UpdateHint(it, p.Down) }
                    }
                }
                when (update.status) {
                    UpdateStatus.IDLE,
                    UpdateStatus.UP_TO_DATE,
                    UpdateStatus.NO_APK,
                    UpdateStatus.FAILED ->
                        UpdateButton(
                            backdrop = backdrop,
                            label = when (update.status) {
                                UpdateStatus.UP_TO_DATE -> "重新检查"
                                UpdateStatus.FAILED -> "重试检查"
                                else -> "检查更新"
                            },
                            onClick = onCheckUpdate
                        )
                    UpdateStatus.AVAILABLE ->
                        UpdateButton(backdrop = backdrop, label = "下载更新", onClick = onDownloadUpdate, emphasis = true)
                    UpdateStatus.DOWNLOADED ->
                        UpdateButton(backdrop = backdrop, label = "安装更新", onClick = onInstallUpdate, emphasis = true)
                    else -> {}
                }
            }
            CardDivider()
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.Share,
                title = "分享用量卡",
                subtitle = "生成并分享当前用量卡片",
                onClick = onShareCard
            )
            CardDivider()
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.SaveAlt,
                title = "保存用量卡到相册",
                subtitle = "把用量卡图片存进系统相册",
                // 交给 ViewModel 执行：组合作用域的协程会在离开本页时被取消，
                // 那样用户点了保存立刻切 Tab，就什么提示都收不到
                onClick = { vm.saveShareCardToGallery() }
            )
            CardDivider()
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.Savings,
                title = "预算提醒阈值",
                subtitle = if (thresholdCustom) {
                    "超 ${formatThreshold(threshold)} 提醒"
                } else {
                    "未设置（默认 ${formatThreshold(BudgetNotify.DEFAULT_BUDGET_USD)}）"
                },
                onClick = { showThresholdDialog = true }
            )
        }
        // —— 凭据备份 ——
        SectionLabel("凭据备份")
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(180)
                .glassCard(backdrop, cornerRadius = 26.dp)
        ) {
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.FileDownload,
                title = "导出接入凭据",
                subtitle = "扩展供应商 + 反重力凭据用口令加密存为 JSON",
                showChevron = false,
                onClick = {
                    passphraseMode = PassphraseMode.EXPORT
                }
            )
            CardDivider()
            SettingRow(
                backdrop = backdrop,
                icon = Icons.Filled.FileUpload,
                title = "导入接入凭据",
                subtitle = "从加密备份恢复（兼容旧版明文 JSON），完成后自动刷新各面板",
                showChevron = false,
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            )        }
        // —— 账号 ——
        SectionLabel("账号")
        Column(
            Modifier
                .fillMaxWidth()
                .fadeSlideIn(200)
                .glassCard(backdrop, cornerRadius = 26.dp)
        ) {
            SettingRow(
                backdrop = backdrop,
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "退出登录",
                subtitle = "清除本机 API Key 并返回登录页",
                danger = true,
                showChevron = false,
                onClick = onLogout
            )
        }
        // —— 页脚 ——
        Text(
            "VibeUsage $appVersion · API 用量统计",
            style = GlassText.Meta,
            fontSize = 10.5.sp,
            color = p.InkLo,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .clickable(remember { MutableInteractionSource() }, null, onClick = {}),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
    // 预算提醒阈值编辑框（独立窗口，盖在设置页之上）
    if (showThresholdDialog) {
        ThresholdDialog(
            initial = threshold,
            onDismiss = { showThresholdDialog = false },
            onSave = { value ->
                BudgetNotify.setThreshold(appContext, value)
                threshold = value
                thresholdCustom = true
                showThresholdDialog = false
            },
            onResetDefault = {
                BudgetNotify.resetThreshold(appContext)
                threshold = BudgetNotify.DEFAULT_BUDGET_USD
                thresholdCustom = false
                showThresholdDialog = false
            }
        )
    }
    // 备份口令弹窗（导出时设置、导入时输入）
    passphraseMode?.let { mode ->
        PassphraseDialog(
            mode = mode,
            onDismiss = {
                passphraseMode = null
                pendingImportText = null
            },
            onConfirm = { pass ->
                passphraseMode = null
                when (mode) {
                    PassphraseMode.EXPORT -> {
                        exportPassphrase = pass
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                            .format(java.util.Date())
                        exportLauncher.launch("vibeusage-backup-$stamp.json")
                    }
                    PassphraseMode.IMPORT -> {
                        val text = pendingImportText
                        pendingImportText = null
                        if (text != null) {
                            runCatching {
                                val restored = CredentialBackup.import(appContext, text, pass)
                                extraVm.onCredsImported()
                                restored
                            }.onSuccess {
                                Toast.makeText(
                                    appContext,
                                    "已恢复 $it 项凭据，正在刷新额度",
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure {
                                Toast.makeText(
                                    appContext,
                                    "导入失败：${it.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        )
    }
}

/** 备份口令弹窗的两种用途。 */
private enum class PassphraseMode { EXPORT, IMPORT }

/**
 * 预算提醒阈值输入弹窗。
 *
 * 沿用设置页的玻璃卡片语言（圆角 + 半透明表面 + 细描边），底部「取消 / 恢复默认 / 确定」。
 * 金额两位小数；非法输入（<= 0 或 > 1,000,000）只提示、不保存。
 */
@Composable
private fun ThresholdDialog(
    initial: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    onResetDefault: () -> Unit
) {
    val p = LocalGlassPalette.current
    var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", initial)) }
    var error by remember { mutableStateOf<String?>(null) }
    val errText = error
    Dialog(
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                // 键盘弹出时把卡片顶上去（SDK 31+ 的 Dialog 窗口不会为 IME 自动 resize）
                .imePadding()
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(26.dp))
                .background(p.Page.copy(alpha = 0.98f))
                .border(1.dp, p.Rim, RoundedCornerShape(26.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("预算提醒阈值", style = GlassText.Title, color = p.InkHi)
            Text(
                "月度消耗预测超过该金额时发提醒",
                style = GlassText.Meta,
                color = p.InkMid
            )
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = filterAmountInput(it)
                    error = null
                },
                label = { Text("金额（USD）") },
                prefix = { Text("$", color = p.InkMid) },
                singleLine = true,
                isError = errText != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.Accent,
                    unfocusedBorderColor = p.Rim,
                    cursorColor = p.Accent,
                    focusedLabelColor = p.Accent,
                    unfocusedLabelColor = p.InkMid,
                    focusedTextColor = p.InkHi,
                    unfocusedTextColor = p.InkHi
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (errText != null) {
                Text(errText, style = GlassText.Meta, color = p.Down)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onResetDefault) {
                    Text(
                        "恢复默认 ${formatThreshold(BudgetNotify.DEFAULT_BUDGET_USD)}",
                        style = GlassText.Label,
                        color = p.InkMid
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("取消", style = GlassText.Label, color = p.InkMid)
                }
                TextButton(onClick = {
                    val value = text.trim().toDoubleOrNull()
                    when {
                        value == null -> error = "请输入有效金额"
                        value <= 0.0 -> error = "阈值必须大于 0"
                        value > BudgetNotify.MAX_BUDGET_USD -> error = "阈值不能超过 1,000,000"
                        else -> onSave(value)
                    }
                }) {
                    Text("确定", style = GlassText.Label, color = p.AccentInk)
                }
            }
        }
    }
}

/**
 * 备份口令弹窗。
 *
 * 导出时要设口令（并再输一遍确认 —— 口令打错一个字符，这份备份就永远解不开了），
 * 导入时只要输一次。
 */
@Composable
private fun PassphraseDialog(
    mode: PassphraseMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val p = LocalGlassPalette.current
    var text by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errText = error
    val exporting = mode == PassphraseMode.EXPORT
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .imePadding()
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(26.dp))
                .background(p.Page.copy(alpha = 0.98f))
                .border(1.dp, p.Rim, RoundedCornerShape(26.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (exporting) "设置备份口令" else "输入备份口令",
                style = GlassText.Title,
                color = p.InkHi
            )
            Text(
                if (exporting) {
                    "口令用于加密备份文件，不写入文件、也不会上传。遗失后备份无法恢复，请自行记牢。"
                } else {
                    "该备份由口令加密，请输入导出时设置的口令。"
                },
                style = GlassText.Meta,
                color = p.InkMid
            )
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                label = { Text("口令") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                isError = errText != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.Accent,
                    unfocusedBorderColor = p.Rim,
                    cursorColor = p.Accent,
                    focusedLabelColor = p.Accent,
                    unfocusedLabelColor = p.InkMid,
                    focusedTextColor = p.InkHi,
                    unfocusedTextColor = p.InkHi
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (exporting) {
                OutlinedTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = null
                    },
                    label = { Text("再输一遍") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = errText != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = p.Accent,
                        unfocusedBorderColor = p.Rim,
                        cursorColor = p.Accent,
                        focusedLabelColor = p.Accent,
                        unfocusedLabelColor = p.InkMid,
                        focusedTextColor = p.InkHi,
                        unfocusedTextColor = p.InkHi
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (errText != null) {
                Text(errText, style = GlassText.Meta, color = p.Down)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("取消", style = GlassText.Label, color = p.InkMid)
                }
                TextButton(onClick = {
                    val pass = text
                    when {
                        pass.length < CredentialBackup.MIN_PASSPHRASE_LEN ->
                            error = "口令至少 ${CredentialBackup.MIN_PASSPHRASE_LEN} 位"
                        exporting && pass != confirm -> error = "两次输入不一致"
                        else -> onConfirm(pass)
                    }
                }) {
                    Text("确定", style = GlassText.Label, color = p.AccentInk)
                }
            }
        }
    }
}

/** 金额展示：整数不带小数（$500），非整数保留两位（$12.50）。 */

private fun formatThreshold(value: Double): String =
    if (value.isFinite() && value == Math.floor(value)) {
        "$" + value.toLong()
    } else {
        "$" + String.format(Locale.US, "%.2f", value)
    }

/** 输入过滤：只留数字与一个小数点，小数点后最多两位。 */

private fun filterAmountInput(raw: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    var decimals = 0
    for (ch in raw) {
        if (ch.isDigit()) {
            if (dotSeen) {
                if (decimals >= 2) continue
                decimals++
            }
            sb.append(ch)
        } else if (ch == '.' && !dotSeen) {
            dotSeen = true
            sb.append(ch)
        }
    }
    return sb.toString()
}

/**
 * 分组标题：与概览页 SectionTitle 同一规格（accent 竖条 + 字距标签）。
 * 原来是光秃秃的 11sp 粗灰字，没有竖条也没有字距 —— 设置页、概览页、额度页
 * 三处分区标题长得不一样。
 */
@Composable
private fun SectionLabel(text: String) {
    val p = LocalGlassPalette.current
    Row(
        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 3.5.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.Accent)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            style = GlassText.Section,
            color = p.InkMid
        )
    }
}

/** 行首图标底座：小玻璃块 + 主题色图标。 */
@Composable
private fun IconBase(icon: ImageVector) {
    val p = LocalGlassPalette.current
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(p.AccentWash),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = p.AccentInk,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 设置行：图标 + 标题/说明 + 右箭头。
 * 独立页里行不需要自己的玻璃底（卡片已提供），保持层次干净。
 */
@Composable
private fun SettingRow(
    backdrop: Backdrop,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val p = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (danger) p.DownWash else p.AccentWash),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) p.Down else p.AccentInk,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = GlassText.Body,
                color = if (danger) p.Down else p.InkHi
            )
            subtitle?.let {
                Spacer(Modifier.height(1.dp))
                // 说明文案长短不一：单行 + Clip 会把长句硬切在卡片右缘（无省略号，
                // 像被裁掉一半的「仅」「完」）。允许折两行，超出才省略。
                Text(
                    it,
                    style = GlassText.Meta,
                    color = p.InkMid,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = p.InkLo,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 卡片内两行之间的细分隔线（左端对齐文字缩进）。 */
@Composable
private fun CardDivider() {
    val p = LocalGlassPalette.current
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = p.InkLo.copy(alpha = 0.18f)
    )
}

@Composable
private fun UpdateHint(text: String, color: Color) {
    Text(text, style = GlassText.Meta, color = color)
}

/** 更新动作按钮：液态玻璃胶囊；主要（下载/安装）叠青蓝渐变表面。 */
@Composable
private fun UpdateButton(
    backdrop: Backdrop,
    label: String,
    onClick: () -> Unit,
    emphasis: Boolean = false
) {
    val p = LocalGlassPalette.current
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        surfaceBrush = if (emphasis) {
            Brush.horizontalGradient(
                listOf(p.Accent, Color(0xFF4E7BFF)),
                startX = 0f,
                endX = 900f
            )
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = GlassText.Chip,
            color = if (emphasis) Color.White else p.AccentInk
        )
    }
}

@Composable
private fun ThemeChip(
    backdrop: Backdrop,
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(34.dp)
            .glassTile(
                backdrop,
                cornerRadius = 12.dp,
                tint = if (selected) p.AccentWash else null
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, p.AccentRim, shape)
                } else {
                    Modifier
                }
            )
            .clickable(interaction, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            mode.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) p.AccentInk else p.InkMid,
            maxLines = 1
        )
    }
}

/** 液态玻璃参数滑杆行：左侧标签 + 液态滑杆 + 右侧当前值。 */
@Composable
private fun GlassStyleSliderRow(
    backdrop: Backdrop,
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val p = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = GlassText.Label, color = p.InkMid)
            Spacer(Modifier.weight(1f))
            Text(valueText, style = GlassText.NumericSmall, color = p.InkHi)
        }
        LiquidSlider(
            value = { value.coerceIn(valueRange.start, valueRange.endInclusive) },
            onValueChange = onChange,
            valueRange = valueRange,
            visibilityThreshold = 0.01f,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
