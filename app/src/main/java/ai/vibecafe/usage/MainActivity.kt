package ai.vibecafe.usage

import ai.vibecafe.usage.core.ApiKeyStore
import ai.vibecafe.usage.ui.DashboardScreen
import ai.vibecafe.usage.ui.MainViewModel
import ai.vibecafe.usage.ui.glass.GlassBackground
import ai.vibecafe.usage.ui.glass.glassCard
import ai.vibecafe.usage.ui.glass.rememberPageBackdrop
import ai.vibecafe.usage.ui.theme.Glass
import ai.vibecafe.usage.ui.theme.GlassText
import ai.vibecafe.usage.ui.theme.HanSansTypography
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(typography = HanSansTypography) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }

    // 首次进入或退出登录后重新拿到 key 时，拉取数据
    LaunchedEffect(apiKey) {
        if (apiKey.isNotEmpty()) vm.loadData(apiKey)
    }

    if (apiKey.isEmpty()) {
        LoginScreen(onLogin = { key ->
            ApiKeyStore.save(context, key)
            apiKey = key
        })
    } else {
        DashboardScreen(
            state = state,
            onSelectRange = vm::setTimeRange,
            onRefresh = { vm.loadData(apiKey) },
            onLogout = {
                ApiKeyStore.clear(context)
                apiKey = ""
            }
        )
    }
}

@Composable
private fun LoginScreen(onLogin: (String) -> Unit) {
    val backdrop = rememberPageBackdrop()
    var key by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(0.86f)
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("VibeUsage v2.5.1", style = GlassText.Title)
                Text("输入 VibeCafe API Key 以加载用量数据", style = GlassText.Label)
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = { Text("API Key", color = Glass.InkMid) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Password
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Glass.InkHi,
                        unfocusedTextColor = Glass.InkHi,
                        focusedContainerColor = Color.White.copy(alpha = 0.5f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.42f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Glass.Accent
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(backdrop, cornerRadius = 16.dp)
                        .clickable(enabled = key.isNotBlank()) { onLogin(key.trim()) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "登录",
                        style = GlassText.Chip,
                        color = Glass.InkStrong,
                        modifier = Modifier.padding(vertical = 13.dp)
                    )
                }
            }
        }
    }
}
