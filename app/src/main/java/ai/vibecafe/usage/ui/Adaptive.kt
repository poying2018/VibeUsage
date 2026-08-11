package ai.vibecafe.usage.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 平板/宽屏判断：窗口宽度 ≥ 600dp 视为宽屏（Material 折叠→展开断点）。
 * 宽屏下 Dashboard 采用双列布局，登录页限宽居中。
 */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
