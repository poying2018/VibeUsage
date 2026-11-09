package ai.vibecafe.usage.ui.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局玻璃运动状态（极简）：列表滚动期间冻结极光时钟。
 *
 * 极光背景每 50ms 推进一帧，每次推进都会让 LayerBackdrop 重新录制，
 * 屏幕上所有玻璃面随之重跑 blur / lens / colorControls —— 滚动时这部分
 * GPU 开销叠加在滚动本身之上（模拟器实测 90 分位帧耗时 34ms、慢绘制 21 帧）。
 * 滚动中内容在动，极光静止不可感知；停止滚动后从冻结相位无缝恢复。
 */
object GlassMotion {
    var scrolling by mutableStateOf(false)
        internal set
}
