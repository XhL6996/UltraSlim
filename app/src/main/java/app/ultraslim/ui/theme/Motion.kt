package app.ultraslim.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * 统一动效规范：全 App 的时长、缓动与弹簧都从这里取，不再散落魔法数。
 * 原则：手势跟随零延迟；布局形变必动画；高频值（进度、时间）不动。
 */
object Motion {
    /** 小型内容切换：按钮状态、图片 crossfade。 */
    const val Fast = 150

    /** 常规布局形变：折叠面板、操作栏、列表项。 */
    const val Standard = 250

    /** 屏幕级过渡：导航切换（与 AppNavHost 的 TRANSITION_MS 一致）。 */
    const val Emphasized = 320

    /** 标准缓动：进出场与形变。 */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 退场用：先快后慢，让位给进场的元素。 */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 线性：极少用（指示器类）。 */
    val Linear = LinearEasing

    /** 列表项位置移动的轻弹簧（Modifier.animateItem 的 placementSpec）：不过冲，快速落位。 */
    fun itemPlacement() = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 取消手势后的回弹（如预测性返回）：轻微弹性，不夸张。 */
    fun settleBack() = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> fast() = tween<T>(Fast, easing = StandardEasing)

    fun <T> standard() = tween<T>(Standard, easing = StandardEasing)

    fun <T> emphasized() = tween<T>(Emphasized, easing = StandardEasing)

    fun <T> exit(durationMillis: Int = Standard) = tween<T>(durationMillis, easing = ExitEasing)
}
