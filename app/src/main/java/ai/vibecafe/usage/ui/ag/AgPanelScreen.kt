package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.vibecafe.usage.data.ag.AgQuotaApi
import ai.vibecafe.usage.data.ag.AgQuotaApi.QuotaBucket
import ai.vibecafe.usage.data.quota.AgGoogleOAuth
import ai.vibecafe.usage.ui.glass.LiquidButton
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.theme.LocalGlassPalette
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

/** 反重力主题色（蓝紫渐变）。 */
private val AgBlue = Color(0xFF4A7DFF)
private val AgPurple = Color(0xFF9B6CFF)
private val AgAmber = Color(0xFFFFB020)

/**
 * 反重力（Google Antigravity）额度面板 —— 液态玻璃 + 5h/每周配额桶。
 * 粘贴桌面版 Antigravity.Tools 导出的账号 JSON 或 refresh_token 登录。
 */
@Composable
fun AgPanelScreen(
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    viewModel: AgPanelViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!state.isLoggedIn) {
            AgLoginCard(
                backdrop = backdrop,
                isLoading = state.isLoading,
                error = state.error,
                onLogin = { viewModel.login(it) },
                onClearError = { viewModel.clearError() },
                onOAuthError = { viewModel.setOAuthError(it) }
            )
        } else {
            AgLoggedInContent(state, backdrop, onRefresh = { viewModel.refresh() }, onLogout = { viewModel.logout() })
        }
    }
}

// ─── 登录卡 ───

@Composable
private fun AgLoginCard(
    backdrop: Backdrop,
    isLoading: Boolean,
    error: String?,
    onLogin: (String) -> Unit,
    onClearError: () -> Unit,
    onOAuthError: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalGlassPalette.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .glassCard(backdrop, cornerRadius = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo 徽章
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(AgBlue, AgPurple),
                            start = androidx.compose.ui.geometry.Offset.Zero,
                            end = androidx.compose.ui.geometry.Offset(160f, 160f)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.RocketLaunch,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(14.dp))

            Text(
                text = "反重力额度",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = palette.InkHi
            )
            Text(
                text = "Google Antigravity 配额查询",
                fontSize = 13.sp,
                color = palette.InkMid,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // 一键授权：浏览器 Google 登录，loopback 捕获授权码，凭据自动保存
            LiquidButton(
                onClick = {
                    if (oauthBusy || isLoading) return@LiquidButton
                    oauthBusy = true
                    onClearError()
                    scope.launch {
                        try {
                            val l = AgGoogleOAuth.login(context)
                            onLogin(
                                org.json.JSONObject().apply {
                                    put("refresh_token", l.refreshToken)
                                    l.email?.let { put("email", it) }
                                }.toString()
                            )
                        } catch (e: Exception) {
                            onOAuthError("授权失败：${e.message?.take(140) ?: "未知错误"}")
                        } finally {
                            oauthBusy = false
                        }
                    }
                },
                backdrop = backdrop,
                enabled = !oauthBusy && !isLoading,
                surfaceBrush = Brush.horizontalGradient(
                    listOf(AgBlue, AgPurple),
                    startX = 0f,
                    endX = 620f
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (oauthBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("等待浏览器授权…", fontSize = 15.sp, color = Color.White)
                } else {
                    Icon(
                        imageVector = Icons.Filled.RocketLaunch,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Google 一键授权", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }

            // 错误提示
            if (error != null) {
                Text(
                    error,
                    color = palette.Down,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // 手动粘贴（降级方式）
            TextButton(onClick = { showManual = !showManual }) {
                Text(
                    if (showManual) "收起手动输入" else "手动粘贴账号 JSON / refresh_token",
                    color = palette.InkMid,
                    fontSize = 12.sp
                )
            }

            if (showManual) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; onClearError() },
                    label = { Text("账号 JSON 或 refresh_token") },
                    placeholder = { Text("{\"refresh_token\": \"1//...\"}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AgBlue,
                        unfocusedBorderColor = palette.Rim,
                        cursorColor = AgBlue,
                        focusedLabelColor = AgBlue,
                        unfocusedLabelColor = palette.InkMid,
                        focusedTextColor = palette.InkHi,
                        unfocusedTextColor = palette.InkHi
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(Modifier.height(12.dp))

                LiquidButton(
                    onClick = { onLogin(input) },
                    backdrop = backdrop,
                    enabled = input.isNotBlank() && !isLoading,
                    surfaceColor = AgBlue,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("查询额度", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }

                Text(
                    "粘贴 Antigravity.Tools 导出的账号 JSON（.antigravity_tools\\accounts\\*.json），或直接粘贴 refresh_token",
                    color = palette.InkLo,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

// ─── 已登录视图 ───

@Composable
private fun AgLoggedInContent(
    state: AgPanelState,
    backdrop: Backdrop,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalGlassPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // ── 标题栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(AgBlue, AgPurple),
                                start = androidx.compose.ui.geometry.Offset.Zero,
                                end = androidx.compose.ui.geometry.Offset(100f, 100f)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.RocketLaunch,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "反重力额度",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.InkHi
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = AgBlue,
                        strokeWidth = 2.dp
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = palette.InkMid,
                        modifier = Modifier.size(18.dp)
                    )
                }
                TextButton(onClick = onLogout) {
                    Icon(
                        imageVector = Icons.Filled.ExitToApp,
                        contentDescription = "退出",
                        tint = palette.InkMid,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("退出", color = palette.InkMid, fontSize = 12.sp)
                }
            }
        }

        // ── 账号卡 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(backdrop, cornerRadius = 20.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.email ?: "Antigravity 账号",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.InkHi,
                        maxLines = 1
                    )
                    Text(
                        if (state.updatedAt > 0) "更新于 " + java.text.SimpleDateFormat(
                            "HH:mm:ss", java.util.Locale.getDefault()
                        ).format(java.util.Date(state.updatedAt)) else "尚未获取额度",
                        fontSize = 11.sp,
                        color = palette.InkMid,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (state.tier != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AgBlue.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            state.tier!!,
                            fontSize = 11.sp,
                            color = AgBlue,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // ── 错误提示 ──
        if (state.error != null) {
            Text(
                state.error!!,
                color = palette.Down,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 5h 滚动额度 ──
        BucketsCard(
            title = "5 小时滚动额度",
            buckets = state.buckets5h,
            backdrop = backdrop
        )

        Spacer(Modifier.height(12.dp))

        // ── 每周额度 ──
        if (state.bucketsWeekly.isNotEmpty()) {
            BucketsCard(
                title = "每周额度",
                buckets = state.bucketsWeekly,
                backdrop = backdrop
            )
        }
    }
}

@Composable
private fun BucketsCard(
    title: String,
    buckets: List<QuotaBucket>,
    backdrop: Backdrop
) {
    if (buckets.isEmpty()) return
    val palette = LocalGlassPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(backdrop, cornerRadius = 20.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.InkMid
            )
            Spacer(Modifier.height(12.dp))
            buckets.forEachIndexed { idx, bucket ->
                if (idx > 0) Spacer(Modifier.height(14.dp))
                BucketRow(bucket)
            }
        }
    }
}

@Composable
private fun BucketRow(bucket: QuotaBucket) {
    val palette = LocalGlassPalette.current
    val percent = bucket.percentRemaining
    val barColor = when {
        percent >= 50 -> AgBlue
        percent >= 20 -> AgAmber
        else -> palette.Down
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                bucket.label,
                fontSize = 14.sp,
                color = palette.InkHi,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$percent%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = barColor
            )
        }
        Spacer(Modifier.height(7.dp))
        // 进度条：圆角轨道 + 剩余填充
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.InkHi.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(barColor)
            )
        }
        val resetText = if (!bucket.isWeekly) bucket.resetTimeIso?.let { ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset(it) } else null
        if (resetText != null) {
            Text(
                resetText,
                fontSize = 11.sp,
                color = palette.InkMid,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

/** 剩余毫秒 → 倒计时文本。 */
