package com.inkreader.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「仿真」翻页（折页覆盖）的绘制与手势。
 *
 * 这里刻意**不走 `HorizontalPager`**：折页需要「旧页压在新页上面」，
 * 而 pager 的绘制顺序是页号递增、页号大的在上，改位移做不出来（详见 Models.kt 的注释）。
 * 本文件的做法是自己搭两层：
 *
 * ① 「露出页」正常铺在最下面（就是翻页后要显示的那一页）；
 * ② 「承载页」在它的 `drawWithContent` 里按折线做裁剪 —— 没折走的部分照常画正文，
 *    折走的部分只画纸背（不画镜像正文，避免透字），再叠折痕与投影。
 *
 * 绘制顺序由 Compose 的声明式叠层保证，不依赖任何 z-order hack；也不做位图回读
 * （1260×2800 一页 ARGB_8888 约 14 MB），拖动期间只改一个浮点进度，不碰会话页状态。
 *
 * 第一版是稳定的二维折页：竖直折线 + 两片裁剪 + 折痕高光 + 投影 + 背面纸色暗化。
 * 若要升级三维卷曲，只需要替换 [drawFold] 内部。
 */

/** 折页用色：全部由主题底色派生，不硬编码 */
internal data class FoldColors(
    /** 折过去的纸背底色（比正文底色略暗/略亮） */
    val backSide: Color,
    /** 纸背进一步暗化，靠近折线一侧更明显 */
    val backShade: Color,
    /** 折痕高光（窄窄一道纸亮色） */
    val crease: Color,
    /** 折页投在露出页上的阴影 */
    val shadow: Color
)

/** 按当前正文底色派生折页配色；深色主题反向取值，避免「暗化」在夜间看不出来 */
internal fun foldColors(background: Color): FoldColors {
    val dark = background.luminance() < 0.3f
    return if (dark) {
        FoldColors(
            backSide = lerp(background, Color.White, 0.06f),
            backShade = Color.White.copy(alpha = 0.06f),
            crease = Color.White.copy(alpha = 0.12f),
            shadow = Color.Black.copy(alpha = 0.34f)
        )
    } else {
        FoldColors(
            backSide = lerp(background, Color.Black, 0.05f),
            backShade = Color.Black.copy(alpha = 0.10f),
            crease = Color.White.copy(alpha = 0.55f),
            shadow = Color.Black.copy(alpha = 0.16f)
        )
    }
}

/**
 * 折页状态机。
 *
 * 同一时刻只有一个方向在动；[busy] 为真时忽略新的手势按下（对应「动画过程中锁定重复触发」），
 * 但会把「快速再甩一次」的意图记进 [queuedDir]，等当前这次收尾后串联一次。
 */
internal class CurlState(private val scope: CoroutineScope) {

    /** 0=空闲；+1=往后翻（下一页）；-1=往前翻（上一页） */
    var dir by mutableIntStateOf(0)
        private set

    /** 折页进度 0..1（0 = 当前页完整可见，1 = 当前页完全折走、露出页铺满） */
    var progress by mutableFloatStateOf(0f)
        private set

    /** 拖动中 / 动画中 / 提交等待中：期间忽略新的手势按下 */
    var busy by mutableStateOf(false)
        private set

    /**
     * 提交后「本该显示的新页」。
     * 跨章提交时页数据是异步换上去的，这段时间先把这一页直接当承载页画出来，
     * 免得闪回旧页；等会话真正切到这一页后再由宿主调 [finishCommit] 清掉。
     *
     * 必须是可观察状态：宿主靠它判断「目标页就位没有」，普通 var 不会触发重组，
     * 会导致提交后 [busy] 一直收不了尾 —— 表现就是「翻一页之后再也翻不动了」。
     */
    var pendingTarget by mutableStateOf<PagerEntry?>(null)

    private var queuedDir = 0
    private var job: Job? = null

    /** 由宿主注入：能否往 dir 方向翻（边界判断） */
    var canTurn: (Int) -> Boolean = { false }

    /** 由宿主注入：提交一页（内部会走 session.onPagerSettled，并置好 pendingTarget） */
    var onCommit: (Int) -> Unit = {}

    /** 布局/模式变化、离开组合时把在飞动画与状态全部清掉 */
    fun cancel() {
        job?.cancel()
        job = null
        queuedDir = 0
        dir = 0
        progress = 0f
        busy = false
        pendingTarget = null
    }

    /** 起拖：方向已定，进度交给 [dragBy] 跟手 */
    fun beginDrag(d: Int) {
        job?.cancel()
        job = null
        dir = d
        progress = 0f
        busy = true
    }

    /** 跟手推进：deltaPx 已按方向换算成正的「翻动量」 */
    fun dragBy(deltaPx: Float, widthPx: Float) {
        if (widthPx <= 0f) return
        progress = (progress + deltaPx / widthPx).coerceIn(0f, 1f)
    }

    /** 松手：按距离/速度决定完成翻页还是回弹 */
    fun endDrag(commit: Boolean) {
        job = scope.launch {
            if (commit) {
                animate(
                    initialValue = progress,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                ) { value, _ -> progress = value }
                onCommit(dir)
                // onCommit 正常会置好 pendingTarget，等目标页就位；没有则直接收尾
                if (pendingTarget == null) finishCommit()
            } else {
                animate(
                    initialValue = progress,
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
                ) { value, _ -> progress = value }
                reset()
            }
        }
    }

    /** 点击左右边缘 / 音量键：程序化翻一页，复用同一条 0→1 折页几何 */
    fun animateTurn(d: Int) {
        if (busy) {
            queuedDir = d
            return
        }
        if (!canTurn(d)) return
        dir = d
        busy = true
        progress = 0f
        job = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            ) { value, _ -> progress = value }
            onCommit(d)
            if (pendingTarget == null) finishCommit()
        }
    }

    /** 动画还没收尾时记下「快速再甩一次」的意图，收尾后串联 */
    fun queueTurn(d: Int) {
        if (d != 0) queuedDir = d
    }

    /** 目标页已经就位：清掉折页状态，并串联掉排队中的那一次翻页 */
    fun finishCommit() {
        pendingTarget = null
        reset()
    }

    private fun reset() {
        dir = 0
        progress = 0f
        busy = false
        val q = queuedDir
        queuedDir = 0
        if (q != 0 && canTurn(q)) animateTurn(q)
    }
}

/** 跟随当前组合的生命周期创建折页状态 */
@Composable
internal fun rememberCurlState(): CurlState {
    val scope = rememberCoroutineScope()
    return remember(scope) { CurlState(scope) }
}

/**
 * 翻页的两层叠放：下面 [reveal]（翻页后要露出的那一页），上面 [content]（承载页）。
 *
 * [cover] 决定承载页怎么离开：true=「覆盖」（整页平移出去，露出下面那页），
 * false=「仿真」（按折线折过去，纸背透字）。两种共用同一套叠层、手势和提交逻辑。
 *
 * [direction] / [progress] 都传**取值函数**而不是值：拖动时进度每帧都在变，
 * 若在组合期读取就会每帧重组整棵子树（含两整页正文），这正是之前「翻起来卡卡的」的原因。
 * 放进 [drawWithContent] 里读，就只让**绘制**失效，组合与测量完全不动。
 *
 * [reveal] 永远参与组合（不按方向挂条件），这样起拖那一帧不会才首次组合整页正文；
 * 它自身靠「画不画」的开关在空闲时完全不产生绘制开销。
 */
@Composable
internal fun CurlHost(
    direction: () -> Int,
    progress: () -> Float,
    cover: Boolean,
    colors: FoldColors,
    modifier: Modifier = Modifier,
    reveal: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier.fillMaxSize()) {
        reveal()
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val pr = progress()
                    val d = direction()
                    if (d == 0 || pr <= 0f) {
                        drawContent()
                    } else if (cover) {
                        drawCover(pr, forward = d > 0) { this@drawWithContent.drawContent() }
                    } else {
                        drawFold(pr, colors, forward = d > 0) { this@drawWithContent.drawContent() }
                    }
                }
        ) {
            content()
        }
    }
}

/**
 * 「覆盖」几何：目标页固定铺在底层，承载页（旧页）整页平移离场 ——
 * 像把上面那张纸抽走，下一页早已铺在下面。
 *
 * 前翻（露出下一页）旧页向左移出；后翻（露出上一页）向右移出。
 * 这里**不做变形、不画背面、不加折痕与重阴影**（那是仿真的事）：
 * 覆盖要的是干净、快速、无闪烁。
 */
private fun DrawScope.drawCover(
    progress: Float,
    forward: Boolean,
    drawPage: DrawScope.() -> Unit
) {
    val w = size.width
    if (w <= 0f) {
        drawPage()
        return
    }
    translate(left = (if (forward) -w else w) * progress) { drawPage() }
}

/**
 * 折页几何。
 *
 * 折线位置：前翻（[forward]=true，露出下一页）`foldX = W·(1−progress)`，自右向左移；
 * 后翻（露出上一页）`foldX = W·progress`，自左向右移 —— 后翻不是把整套绘制镜像，
 * 而是只把折线方向反过来，否则正文会跟着被镜像成反字。
 *
 * · A 片：还平铺在原处的正文，永远在「露出页的反面」那一侧，正向绘制；
 * · B 片：折过去的纸背，绕折线**镜像**画出旧页正文，再压一层半透明纸色 —— 即「透字」，
 *   能看到要翻走的旧页文字，但被纸色压暗；
 * · 折痕：折线上窄高光；投影：落在露出的那一侧。
 *
 * 升级三维卷曲时只改这里（换成路径曲线裁剪或 RuntimeShader 曲面/光照），调用点不变。
 */
private fun DrawScope.drawFold(
    progress: Float,
    colors: FoldColors,
    forward: Boolean,
    drawPage: DrawScope.() -> Unit
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) {
        drawPage()
        return
    }
    val foldX = (if (forward) w * (1f - progress) else w * progress).coerceIn(0f, w)

    // A 片：还没折走的正文。前翻留在折线左侧，后翻留在折线右侧。
    if (forward) {
        clipRect(left = 0f, top = 0f, right = foldX, bottom = h) { drawPage() }
    } else {
        clipRect(left = foldX, top = 0f, right = w, bottom = h) { drawPage() }
    }

    // B 片：折过去的纸背。绕折线镜像画一遍正文，再压半透明纸色 —— 透字。
    val flapLeft = if (forward) (2f * foldX - w).coerceAtLeast(0f) else foldX
    val flapRight = if (forward) foldX else (2f * foldX).coerceAtMost(w)
    if (flapRight > flapLeft) {
        clipRect(left = flapLeft, top = 0f, right = flapRight, bottom = h) {
            withTransform({ scale(-1f, 1f, pivot = Offset(foldX, 0f)) }) { drawPage() }
            // 纸色半透明覆盖：镜像正文还看得见，但被压暗成「隔着一张纸」的感觉
            drawRect(
                color = colors.backSide.copy(alpha = 0.60f),
                topLeft = Offset(flapLeft, 0f),
                size = Size(flapRight - flapLeft, h)
            )
            // 越靠外侧边缘越暗，做出纸背的弧度感
            drawRect(
                brush = if (forward) {
                    Brush.horizontalGradient(
                        colors = listOf(colors.backShade, Color.Transparent),
                        startX = flapLeft,
                        endX = flapRight.coerceAtLeast(flapLeft + 1f)
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, colors.backShade),
                        startX = flapLeft,
                        endX = flapRight.coerceAtLeast(flapLeft + 1f)
                    )
                },
                topLeft = Offset(flapLeft, 0f),
                size = Size(flapRight - flapLeft, h)
            )
        }
    }

    // 折痕高光
    val creaseW = 6.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, colors.crease, Color.Transparent),
            startX = foldX - creaseW,
            endX = foldX + creaseW
        ),
        topLeft = Offset(foldX - creaseW, 0f),
        size = Size(creaseW * 2f, h)
    )

    // 投影：落在露出页上（折线朝向露出页的那一侧）
    val shadowW = 18.dp.toPx()
    val shadowLeft = if (forward) foldX else (foldX - shadowW).coerceAtLeast(0f)
    val shadowRight = if (forward) (foldX + shadowW).coerceAtMost(w) else foldX
    if (shadowRight > shadowLeft) {
        drawRect(
            brush = Brush.horizontalGradient(
                colors = if (forward) {
                    listOf(colors.shadow, Color.Transparent)
                } else {
                    listOf(Color.Transparent, colors.shadow)
                },
                startX = shadowLeft,
                endX = shadowRight.coerceAtLeast(shadowLeft + 1f)
            ),
            topLeft = Offset(shadowLeft, 0f),
            size = Size(shadowRight - shadowLeft, h)
        )
    }
}

/**
 * 横向拖动翻页。
 *
 * 只在「横向位移占优」时认领（`awaitHorizontalTouchSlopOrCancellation`），
 * 因此外层不与 [PullablePage] 的纵向手势、正文里的长按选区/点击冲突；
 * [enabled] 为假（有选区）时完全不挂手势。
 */
internal fun Modifier.foldDragGesture(
    state: CurlState,
    widthPx: () -> Float,
    enabled: Boolean
): Modifier = if (!enabled) this else this.pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val change = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
            ?: return@awaitEachGesture
        // 方向只按「按下点 → 过 slop 点」的横向位移判定：手指往左 = 翻下一页。
        // 不用 overSlop —— 它的符号在不同 Compose 版本里不稳定，会把方向判反，
        // 表现为「拖了却不动 / 完全没动画」。
        val slopDx = change.position.x - down.position.x
        if (slopDx == 0f) return@awaitEachGesture
        val d = if (slopDx < 0f) 1 else -1
        if (!state.canTurn(d)) return@awaitEachGesture
        if (state.busy) {
            // 动画还没收尾：记下意图，结束后串联一次
            state.queueTurn(d)
            return@awaitEachGesture
        }
        state.beginDrag(d)
        val w = widthPx()
        // 起拖前已经走过的位移，等量折算成进度
        state.dragBy(kotlin.math.abs(slopDx), w)
        drag(change.id) { ch ->
            val dx = ch.positionChange().x
            tracker.addPosition(ch.uptimeMillis, ch.position)
            ch.consume()
            state.dragBy(if (d > 0) -dx else dx, w)
            true
        }
        val vx = tracker.calculateVelocity().x
        val commit = state.progress > 0.5f ||
            (d > 0 && vx < -700f) || (d < 0 && vx > 700f)
        state.endDrag(commit)
    }
}