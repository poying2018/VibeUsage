package ai.vibecafe.usage.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 平板/宽屏判断：基于 Material3 WindowSizeClass 的窗口宽度类别。
 * 宽度 < 600dp 视为 COMPACT（手机），MEDIUM / EXPANDED 均算「宽屏」，
 * 宽屏下 Dashboard 采用双列布局、登录页限宽居中。
 *
 * 替代旧的 LocalConfiguration.screenWidthDp：WindowSizeClass 能感知 window 大小变化
 * （分屏、自由窗口、折叠屏展开），比静态配置更准确，且符合 Material 官方推荐。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun isWideScreen(): Boolean {
    val activity = LocalContext.current.findActivity() ?: return false
    return calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
}

/** 从任意 Context 回溯查找宿主 Activity（tailrec，避免栈溢出）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}