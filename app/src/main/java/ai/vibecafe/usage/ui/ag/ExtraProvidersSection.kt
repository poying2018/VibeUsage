package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
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

private val MiniMaxColor = Color(0xFFF0483E)
private val MutedColor = Color(0xFF9A9AAF)
private val ErrorColor = Color(0xFFFF5A5A)

/**
 * MiniMax 独立页面：整页展示接入与额度明细，按时间窗口（5 小时 / 每周）分组。
 * 凭据为 Token Plan API Key（sk-cp-...），粘贴一次即保存在本机。
 */
@Composable
fun ProviderPage(
    backdrop: Backdrop,
    viewModel: ExtraQuotaViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("MiniMax", MiniMaxColor, Icons.Filled.BubbleChart)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .glassCard(backdrop, cornerRadius = 20.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (!state.loggedIn) {
                LoginArea(state, backdrop, viewModel)
            } else {
                LoggedArea(state, viewModel)
            }
        }
    }
}

// ─── 未接入：粘贴 API Key ───

@Composable
private fun LoginArea(ps: ProviderState, backdrop: Backdrop, viewModel: ExtraQuotaViewModel) {
    var input by remember { mutableStateOf("") }

    Column {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; viewModel.clearError() },
            label = { Text("凭据") },
            placeholder = { Text("sk-cp-...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MiniMaxColor,
                unfocusedBorderColor = Color(0xFF3A3A4E),
                cursorColor = MiniMaxColor,
                focusedLabelColor = MiniMaxColor,
                unfocusedLabelColor = MutedColor,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
        Spacer(Modifier.height(10.dp))
        LiquidButton(
            onClick = { viewModel.login(input) },
            backdrop = backdrop,
            enabled = input.isNotBlank() && !ps.isLoading,
            surfaceColor = MiniMaxColor,
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            if (ps.isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("查询中…", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            } else {
                Text("接入", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        if (ps.error != null) {
            Text(ps.error!!, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            "国内：platform.minimaxi.com → 接口密钥；国际：platform.minimax.io → API Keys。粘贴 sk-cp- 开头的 Token Plan 密钥",
            color = Color(0xFF5A5A6E),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// ─── 已接入：状态行 + 分组明细 ───

@Composable
private fun LoggedArea(ps: ProviderState, viewModel: ExtraQuotaViewModel) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (ps.isLoading) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = MiniMaxColor, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                if (ps.account != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MiniMaxColor.copy(alpha = 0.16f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(ps.account!!, fontSize = 10.sp, color = MiniMaxColor, maxLines = 1)
                    }
                }
            }
            IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, "刷新", Modifier.size(16.dp), tint = MutedColor)
            }
            IconButton(onClick = { viewModel.logout() }, modifier = Modifier.size(30.dp)) {
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
                BarRow(bar, MiniMaxColor)
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
