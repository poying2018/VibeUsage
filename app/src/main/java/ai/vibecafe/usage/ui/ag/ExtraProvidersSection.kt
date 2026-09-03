package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import com.kyant.backdrop.Backdrop
import java.util.Locale

/** 三平台品牌色。 */
private val CodexColor = Color(0xFF10A37F)
private val ClaudeColor = Color(0xFFD97757)
private val MiniMaxColor = Color(0xFFF0483E)
private val MutedColor = Color(0xFF9A9AAF)
private val ErrorColor = Color(0xFFFF5A5A)

/**
 * Codex / Claude Code / MiniMax 额度分区，显示在「额度」页反重力面板下方。
 * 与概览页同构：每个平台一个区块标题（accent 竖条 + 大写标签），
 * 区块内按时间窗口（5 小时 / 每周 / 模型细分）分组展示明细。
 */
@Composable
fun ExtraProvidersSection(
    backdrop: Backdrop,
    viewModel: ExtraQuotaViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("CODEX", CodexColor, Icons.Filled.Terminal)
        ProviderCard(
            hint = "粘贴 ~/.codex/auth.json 内容（ChatGPT 登录态）",
            placeholder = "{\"tokens\": {\"access_token\": \"...\"}}",
            color = CodexColor,
            providerState = state.codex,
            backdrop = backdrop,
            onLogin = { viewModel.loginCodex(it) },
            onRefresh = { viewModel.refreshCodex() },
            onLogout = { viewModel.logout("codex") },
            onClearError = { viewModel.clearError("codex") }
        )

        Spacer(Modifier.height(18.dp))
        SectionHeader("CLAUDE CODE", ClaudeColor, Icons.Filled.AutoAwesome)
        ProviderCard(
            hint = "粘贴 ~/.claude/.credentials.json 内容（OAuth 凭据）",
            placeholder = "{\"claudeAiOauth\": {\"accessToken\": \"...\"}}",
            color = ClaudeColor,
            providerState = state.claude,
            backdrop = backdrop,
            onLogin = { viewModel.loginClaude(it) },
            onRefresh = { viewModel.refreshClaude() },
            onLogout = { viewModel.logout("claude") },
            onClearError = { viewModel.clearError("claude") }
        )

        Spacer(Modifier.height(18.dp))
        SectionHeader("MINIMAX", MiniMaxColor, Icons.Filled.BubbleChart)
        ProviderCard(
            hint = "粘贴 Token Plan API Key（platform.minimax.io 生成，sk-cp- 开头）",
            placeholder = "sk-cp-...",
            color = MiniMaxColor,
            providerState = state.minimax,
            backdrop = backdrop,
            onLogin = { viewModel.loginMiniMax(it) },
            onRefresh = { viewModel.refreshMiniMax() },
            onLogout = { viewModel.logout("minimax") },
            onClearError = { viewModel.clearError("minimax") }
        )
    }
}

/** 与概览页 SectionTitle 同款：accent 竖条 + 大写小字标签 + 平台色圆点。 */
@Composable
private fun SectionHeader(text: String, color: Color, icon: ImageVector) {
    val palette = LocalGlassPalette.current
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 3.5.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.Accent)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text.uppercase(Locale.getDefault()),
            style = GlassText.Section,
            color = palette.InkHi
        )
        Spacer(Modifier.width(8.dp))
        Icon(icon, null, Modifier.size(13.dp), tint = color)
    }
}

@Composable
private fun ProviderCard(
    hint: String,
    placeholder: String,
    color: Color,
    providerState: ProviderState,
    backdrop: Backdrop,
    onLogin: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onClearError: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .glassCard(backdrop, cornerRadius = 20.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            if (!providerState.loggedIn) {
                // ── 未接入：凭据输入 ──
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; onClearError() },
                    label = { Text("凭据") },
                    placeholder = { Text(placeholder, maxLines = 1) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = color,
                        unfocusedBorderColor = Color(0xFF3A3A4E),
                        cursorColor = color,
                        focusedLabelColor = color,
                        unfocusedLabelColor = MutedColor,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                if (providerState.error != null) {
                    Text(
                        providerState.error!!,
                        color = ErrorColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                LiquidButton(
                    onClick = { onLogin(input) },
                    backdrop = backdrop,
                    enabled = input.isNotBlank() && !providerState.isLoading,
                    surfaceColor = color,
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Text("接入", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                Text(
                    hint,
                    color = Color(0xFF5A5A6E),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                // ── 已接入：状态行 + 分组明细 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (providerState.isLoading) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (providerState.account != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(color.copy(alpha = 0.16f))
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                Text(providerState.account!!, fontSize = 10.sp, color = color, maxLines = 1)
                            }
                        }
                    }
                    IconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Refresh, "刷新", Modifier.size(16.dp), tint = MutedColor)
                    }
                    IconButton(onClick = onLogout, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Logout, "解绑", Modifier.size(15.dp), tint = MutedColor)
                    }
                }

                if (providerState.error != null) {
                    Text(
                        providerState.error!!,
                        color = ErrorColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                    )
                }

                providerState.groups.forEach { (groupTitle, bars) ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        groupTitle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MutedColor
                    )
                    Spacer(Modifier.height(8.dp))
                    bars.forEachIndexed { idx, bar ->
                        if (idx > 0) Spacer(Modifier.height(12.dp))
                        BarRow(bar, color)
                    }
                }

                if (providerState.groups.isEmpty() && !providerState.isLoading && providerState.error == null) {
                    Text(
                        "暂无额度数据",
                        color = MutedColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BarRow(bar: Bar, color: Color) {
    val percent = bar.percentRemaining.coerceIn(0, 100)
    val barColor = when {
        percent >= 50 -> color
        percent >= 20 -> Color(0xFFFFB020)
        else -> Color(0xFFFF5A5A)
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    bar.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1
                )
                bar.counts?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 11.sp, color = MutedColor, maxLines = 1)
                }
            }
            Text(
                buildString {
                    append("$percent%")
                    bar.usedPercent?.let { append(" · 已用 $it%") }
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = barColor
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(barColor)
            )
        }
        if (bar.reset != null) {
            Text(
                bar.reset,
                fontSize = 10.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
