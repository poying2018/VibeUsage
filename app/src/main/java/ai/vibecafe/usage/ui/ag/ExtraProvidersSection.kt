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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.data.quota.ClaudeOAuth
import ai.vibecafe.usage.data.quota.CodexOAuth
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import com.kyant.backdrop.Backdrop
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 三平台品牌色。 */
private val CodexColor = Color(0xFF10A37F)
private val ClaudeColor = Color(0xFFD97757)
private val MiniMaxColor = Color(0xFFF0483E)
private val MutedColor = Color(0xFF9A9AAF)
private val ErrorColor = Color(0xFFFF5A5A)

/** 「额度」页可切换的平台（反重力为独立面板，不在此列）。 */
enum class ExtraProvider(val title: String, val color: Color, val icon: ImageVector) {
    CODEX("Codex", CodexColor, Icons.Filled.Terminal),
    CLAUDE("Claude Code", ClaudeColor, Icons.Filled.AutoAwesome),
    MINIMAX("MiniMax", MiniMaxColor, Icons.Filled.BubbleChart),
}

/**
 * 单平台独立页面：整页只展示一个平台的接入与额度明细，
 * 按时间窗口（5 小时 / 每周 / 模型细分）分组。
 */
@Composable
fun ProviderPage(
    provider: ExtraProvider,
    backdrop: Backdrop,
    viewModel: ExtraQuotaViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ps = when (provider) {
        ExtraProvider.CODEX -> state.codex
        ExtraProvider.CLAUDE -> state.claude
        ExtraProvider.MINIMAX -> state.minimax
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(provider.title, provider.color, provider.icon)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .glassCard(backdrop, cornerRadius = 20.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (!ps.loggedIn) {
                ProviderLoginArea(provider, ps, backdrop, viewModel)
            } else {
                ProviderLoggedArea(provider, ps, viewModel)
            }
        }
    }
}

// ─── 未接入：一键授权 / 手动粘贴 ───

@Composable
private fun ProviderLoginArea(
    provider: ExtraProvider,
    ps: ProviderState,
    backdrop: Backdrop,
    viewModel: ExtraQuotaViewModel
) {
    var input by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(provider == ExtraProvider.MINIMAX) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val key = provider.name.lowercase()

    Column {
        if (provider != ExtraProvider.MINIMAX) {
            // ── 一键授权：浏览器完成官方登录，凭据自动保存 ──
            LiquidButton(
                onClick = {
                    if (oauthBusy) return@LiquidButton
                    oauthBusy = true
                    viewModel.clearError(key)
                    scope.launch {
                        try {
                            when (provider) {
                                ExtraProvider.CODEX -> {
                                    val t = CodexOAuth.login(context)
                                    viewModel.loginCodex(
                                        JSONObject().apply {
                                            put("tokens", JSONObject().apply {
                                                put("access_token", t.accessToken)
                                                t.accountId?.let { put("account_id", it) }
                                                t.refreshToken?.let { put("refresh_token", it) }
                                            })
                                        }.toString()
                                    )
                                }
                                ExtraProvider.CLAUDE -> {
                                    val t = ClaudeOAuth.login(context)
                                    viewModel.loginClaude(
                                        JSONObject().apply {
                                            put("claudeAiOauth", JSONObject().apply {
                                                put("accessToken", t.accessToken)
                                                t.refreshToken?.let { put("refreshToken", it) }
                                            })
                                        }.toString()
                                    )
                                }
                                else -> {}
                            }
                        } catch (e: Exception) {
                            viewModel.setProviderError(key, "授权失败：${e.message?.take(140) ?: "未知错误"}")
                        } finally {
                            oauthBusy = false
                        }
                    }
                },
                backdrop = backdrop,
                enabled = !oauthBusy,
                surfaceColor = provider.color,
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                if (oauthBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("等待浏览器授权…", fontSize = 14.sp, color = Color.White)
                } else {
                    Text("一键授权登录", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
            if (ps.error != null) {
                Text(ps.error!!, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            TextButton(onClick = { showManual = !showManual }) {
                Text(
                    if (showManual) "收起手动输入" else "手动粘贴凭据",
                    color = MutedColor, fontSize = 12.sp
                )
            }
        }

        if (showManual) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; viewModel.clearError(key) },
                label = { Text("凭据") },
                placeholder = {
                    Text(
                        when (provider) {
                            ExtraProvider.CODEX -> "{\"tokens\": {\"access_token\": \"...\"}}"
                            ExtraProvider.CLAUDE -> "{\"claudeAiOauth\": {\"accessToken\": \"...\"}}"
                            ExtraProvider.MINIMAX -> "sk-cp-..."
                        },
                        maxLines = 1
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = provider.color,
                    unfocusedBorderColor = Color(0xFF3A3A4E),
                    cursorColor = provider.color,
                    focusedLabelColor = provider.color,
                    unfocusedLabelColor = MutedColor,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )
            Spacer(Modifier.height(10.dp))
            LiquidButton(
                onClick = {
                    when (provider) {
                        ExtraProvider.CODEX -> viewModel.loginCodex(input)
                        ExtraProvider.CLAUDE -> viewModel.loginClaude(input)
                        ExtraProvider.MINIMAX -> viewModel.loginMiniMax(input)
                    }
                },
                backdrop = backdrop,
                enabled = input.isNotBlank() && !ps.isLoading,
                surfaceColor = provider.color,
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Text("接入", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
            if (ps.error != null && provider == ExtraProvider.MINIMAX) {
                Text(ps.error!!, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Text(
            when (provider) {
                ExtraProvider.CODEX -> "跳转 ChatGPT 官方登录（需 Plus/Pro 订阅），凭据只保存在本机"
                ExtraProvider.CLAUDE -> "跳转 Claude 官方登录（需 Pro/Max 订阅），凭据只保存在本机"
                ExtraProvider.MINIMAX -> "国内：platform.minimaxi.com → 接口密钥；国际：platform.minimax.io → API Keys。粘贴 sk-cp- 开头的 Token Plan 密钥"
            },
            color = Color(0xFF5A5A6E),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// ─── 已接入：状态行 + 分组明细 ───

@Composable
private fun ProviderLoggedArea(
    provider: ExtraProvider,
    ps: ProviderState,
    viewModel: ExtraQuotaViewModel
) {
    val key = provider.name.lowercase()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (ps.isLoading) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = provider.color, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                if (ps.account != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(provider.color.copy(alpha = 0.16f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(ps.account!!, fontSize = 10.sp, color = provider.color, maxLines = 1)
                    }
                }
            }
            IconButton(onClick = {
                when (provider) {
                    ExtraProvider.CODEX -> viewModel.refreshCodex()
                    ExtraProvider.CLAUDE -> viewModel.refreshClaude()
                    ExtraProvider.MINIMAX -> viewModel.refreshMiniMax()
                }
            }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, "刷新", Modifier.size(16.dp), tint = MutedColor)
            }
            IconButton(onClick = { viewModel.logout(key) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Logout, "解绑", Modifier.size(15.dp), tint = MutedColor)
            }
        }

        if (ps.error != null) {
            Text(
                ps.error!!,
                color = ErrorColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
        }

        ps.groups.forEach { (groupTitle, bars) ->
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
                BarRow(bar, provider.color)
            }
        }

        if (ps.groups.isEmpty() && !ps.isLoading && ps.error == null) {
            Text(
                "暂无额度数据",
                color = MutedColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
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
