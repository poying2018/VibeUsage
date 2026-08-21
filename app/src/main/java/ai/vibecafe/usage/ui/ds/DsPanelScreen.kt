package ai.vibecafe.usage.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.vibecafe.usage.data.ds.DsPanelState
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.glass.glassRow
import com.kyant.backdrop.Backdrop

/** DS+ Milky 主题色。 */
private val DsAccent = Color(0xFFE94560)
private val DsCyan = Color(0xFF00D2FF)
private val DsPurple = Color(0xFF7C6BFF)
private val DsGold = Color(0xFFFFC107)
private val DsGreen = Color(0xFF2ED573)

/**
 * DS+ Milky 风格面板 —— 液态玻璃 + 大余额卡片。
 * 输入 DeepSeek API Key 查询余额（官方接口）。
 */
@Composable
fun DsPanelScreen(
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    viewModel: DsPanelViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!state.isLoggedIn) {
            DsLoginCard(
                backdrop = backdrop,
                isLoading = state.isLoading,
                error = state.error,
                onLogin = { viewModel.login(it) },
                onClearError = { viewModel.clearError() }
            )
        } else {
            DsDataCard(
                state = state,
                backdrop = backdrop,
                onLogout = { viewModel.logout() }
            )
        }
    }
}

// ─────────────────────────── 登录卡片 ───────────────────────────

@Composable
private fun DsLoginCard(
    backdrop: Backdrop,
    isLoading: Boolean,
    error: String?,
    onLogin: (String) -> Unit,
    onClearError: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

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
                            listOf(DsCyan, DsPurple),
                            start = androidx.compose.ui.geometry.Offset.Zero,
                            end = androidx.compose.ui.geometry.Offset(160f, 160f)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(14.dp))

            Text(
                text = "DS余额监控",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = DsAccent
            )
            Text(
                text = "DeepSeek 余额查询",
                fontSize = 13.sp,
                color = Color(0xFF8A8A9A),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // API Key 输入
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; onClearError() },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (apiKey.isNotBlank()) onLogin(apiKey) }
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DsAccent,
                    unfocusedBorderColor = Color(0xFF3A3A4E),
                    cursorColor = DsAccent,
                    focusedLabelColor = DsAccent,
                    unfocusedLabelColor = Color(0xFF8A8A9A),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp)
            )

            // 显示/隐藏 Key
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(
                        if (showKey) "隐藏" else "显示",
                        fontSize = 12.sp,
                        color = Color(0xFF8A8A9A)
                    )
                }
            }

            // 错误提示
            if (error != null) {
                Text(
                    text = error,
                    color = DsAccent,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // 查询按钮
            Button(
                onClick = { onLogin(apiKey) },
                enabled = apiKey.isNotBlank() && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DsAccent
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("查询余额", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            // 提示
            Text(
                "在 platform.deepseek.com → API Keys 获取",
                color = Color(0xFF5A5A6E),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

// ─────────────────────────── 数据面板 ───────────────────────────

@Composable
private fun DsDataCard(
    state: DsPanelState,
    backdrop: Backdrop,
    onLogout: () -> Unit
) {
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
                                listOf(DsCyan, DsPurple),
                                start = androidx.compose.ui.geometry.Offset.Zero,
                                end = androidx.compose.ui.geometry.Offset(100f, 100f)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountBalanceWallet,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "DS余额监控",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = DsAccent,
                        strokeWidth = 2.dp
                    )
                }
                TextButton(onClick = onLogout) {
                    Icon(
                        imageVector = Icons.Filled.ExitToApp,
                        contentDescription = "退出",
                        tint = Color(0xFF8A8A9A),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("退出", color = Color(0xFF8A8A9A), fontSize = 12.sp)
                }
            }
        }

        // ── Hero 大余额卡 ──
        HeroBalanceCard(state, backdrop)

        Spacer(Modifier.height(14.dp))

        // ── 充值 / 赠送 细分 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SubBalanceCard(
                title = "充值余额",
                value = state.toppedUpBalance,
                currency = state.currency,
                icon = Icons.Filled.AddCircle,
                tint = DsCyan,
                backdrop = backdrop,
                modifier = Modifier.weight(1f)
            )
            SubBalanceCard(
                title = "赠送余额",
                value = state.grantedBalance,
                currency = state.currency,
                icon = Icons.Filled.CardGiftcard,
                tint = DsGold,
                backdrop = backdrop,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── 可用模型列表 ──
        ModelsCard(state, backdrop)

        Spacer(Modifier.height(14.dp))

        // ── 账户状态 + Key ──
        AccountInfoCard(state, backdrop)
    }
}

/** 可用模型列表卡片。 */
@Composable
private fun ModelsCard(state: DsPanelState, backdrop: Backdrop) {
    val models = state.models
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassRow(backdrop, cornerRadius = 20.dp)
            .padding(16.dp)
    ) {
        Column {
            Text("可用模型", color = Color(0xFF8A8A9A), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))

            if (models.isNullOrEmpty()) {
                Text(
                    if (state.isLoading) "加载中…" else "暂无模型信息",
                    color = Color(0xFF6A6A80),
                    fontSize = 13.sp
                )
            } else {
                models.forEachIndexed { index, model ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = Color(0xFF2A2A3E)
                        )
                    }
                    ModelRow(model)
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ai.vibecafe.usage.data.ds.ModelInfo) {
    val id = model.id ?: "未知模型"
    // 根据模型名区分风格
    val isPro = id.contains("pro", ignoreCase = true)
    val tint = if (isPro) DsPurple else DsCyan
    val tag = if (isPro) "PRO" else "FLASH"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(tint.copy(alpha = 0.25f), tint.copy(alpha = 0.08f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                tag,
                color = tint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                id,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                model.ownedBy ?: "DeepSeek",
                color = Color(0xFF6A6A80),
                fontSize = 11.sp
            )
        }
        Text(
            if (isPro) "强大" else "快速",
            color = Color(0xFF6A6A80),
            fontSize = 11.sp
        )
    }
}

/** 大余额 Hero 卡：渐变背景 + 大号数字 + 状态徽章。 */
@Composable
private fun HeroBalanceCard(state: DsPanelState, backdrop: Backdrop) {
    val available = state.isAvailable == true
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        DsAccent.copy(alpha = 0.85f),
                        DsPurple.copy(alpha = 0.9f)
                    ),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(600f, 300f)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "总余额",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.balance ?: "--",
                color = Color.White,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = state.currency ?: "CNY",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))

            // 状态徽章
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (available) Color(0xFF8FF7B0) else Color(0xFFFFD27A),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (available) "账户可用" else "账户不可用",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 细分卡片：充值 / 赠送。 */
@Composable
private fun SubBalanceCard(
    title: String,
    value: String?,
    currency: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .glassRow(backdrop, cornerRadius = 20.dp)
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    color = Color(0xFF8A8A9A),
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = value ?: "--",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = currency ?: "CNY",
                color = Color(0xFF6A6A80),
                fontSize = 12.sp
            )
        }
    }
}

/** 账户信息卡。 */
@Composable
private fun AccountInfoCard(state: DsPanelState, backdrop: Backdrop) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassRow(backdrop, cornerRadius = 20.dp)
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("账户状态", color = Color(0xFF8A8A9A), fontSize = 13.sp)
                Text(
                    if (state.isAvailable == true) "可用" else "不可用",
                    color = if (state.isAvailable == true) DsGreen else DsAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            state.apiKey?.let { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("当前 Key", color = Color(0xFF8A8A9A), fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${key.take(6)}...${key.takeLast(4)}",
                        color = Color(0xFF6A6A80),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}