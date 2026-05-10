package com.jecheon.voicecoach

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 몰입 모드용 심박수 그래프.
 *
 * - X축: 가장 최근(우측 끝) 으로부터 [windowSec] 초 만큼의 시간 창. 기본 60초.
 *   매 frame 현재 시각 기준으로 슬라이딩.
 * - Y축: 40 ~ 120 BPM 고정.
 * - 핀치 줌으로 [windowSec] 5~3600 초 (1시간) 사이 조정 — 긴 세션 전체 뷰 가능.
 * - 더블탭으로 기본값 (60초) 복귀.
 *
 * Padding 모델:
 *   View 의 raw width/height 를 그대로 쓰지 않고 contentRect 에서 그림.
 *   leftPad — y축 라벨 ("120" 등) 폭 + 여유
 *   rightPad — stroke cap 잘림 방지
 *   topPad — strokeWidth/2 + 여유 (위쪽 spike 잘림 방지)
 *   bottomPad — strokeWidth/2 + windowSec 라벨 영역
 *
 * 이전 버전은 padding 없이 0..w / 0..h 로 그려서 stroke 가 좌측/상단으로 잘리거나 라벨이
 * grid 와 겹쳐 보였음.
 */
class HrGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val samples = ArrayList<Pair<Long, Int>>()
    private var windowSec = 60
    private val minBpm = 40f
    private val maxBpm = 120f
    /** 최소 가능 windowSec — 너무 짧으면 샘플 부족으로 의미 없음. */
    private val minWindowSec = 5
    /** 최대 가능 windowSec — 1시간 세션 전체 한 화면에 보기 위함. */
    private val maxWindowSec = 3600

    private val linePaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = 0x33FFFFFF.toInt() // 흰색 20% alpha
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = 0x88FFFFFF.toInt()
        textSize = 28f
        isAntiAlias = true
    }
    private val windowLabelPaint = Paint().apply {
        color = 0x66FFFFFF.toInt()
        textSize = 26f
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }

    /** 매 onDraw 에서 width/height 가 변할 수 있어 매번 contentRect 재계산. */
    private val contentRect = RectF()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val factor = d.scaleFactor
                // 핀치-아웃(확대) → 시간창 좁아짐 (작은 windowSec)
                // 핀치-인(축소)  → 시간창 넓어짐 (큰 windowSec)
                windowSec = (windowSec / factor).toInt().coerceIn(minWindowSec, maxWindowSec)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                windowSec = 60
                invalidate()
                return true
            }
            override fun onDown(e: MotionEvent): Boolean = true
        }
    )

    /** 외부에서 주기적으로 호출. samples 는 (elapsedRealtime ms, bpm) 튜플 리스트. */
    fun setSamples(list: List<Pair<Long, Int>>) {
        samples.clear()
        samples.addAll(list)
        invalidate()
    }

    /** 외부 (예: 기록 상세) 에서 windowSec 를 직접 지정 — 전체 세션 보기 등. */
    fun setWindowSec(sec: Int) {
        windowSec = sec.coerceIn(minWindowSec, maxWindowSec)
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 매 frame 슬라이딩 (시간 축이 부드럽게 흐름)
        postOnAnimation(animTick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(animTick)
    }

    private val animTick = object : Runnable {
        override fun run() {
            invalidate()
            if (isAttachedToWindow) postOnAnimation(this)
        }
    }

    /**
     * View 의 width/height + padding 으로부터 그림 영역 (contentRect) 계산.
     *
     * leftPad   = "120" 라벨 폭 + 12px (label 과 grid 라인 사이 시각적 간격)
     * rightPad  = strokeWidth/2 + 8px  (stroke cap 잘림 방지 + windowSec 라벨 패딩)
     * topPad    = strokeWidth/2 + 6px  (위쪽 line 잘림 방지)
     * bottomPad = strokeWidth/2 + 22px (아래쪽 line 잘림 방지 + windowSec 라벨 영역)
     */
    private fun updateContentRect() {
        val w = width.toFloat()
        val h = height.toFloat()
        val labelMaxWidth = labelPaint.measureText("120")
        val strokeHalf = linePaint.strokeWidth / 2f
        val leftPad = labelMaxWidth + 12f + paddingLeft
        val rightPad = strokeHalf + 8f + paddingRight
        val topPad = strokeHalf + 6f + paddingTop
        val bottomPad = strokeHalf + 22f + paddingBottom
        contentRect.set(
            leftPad,
            topPad,
            max(leftPad + 1f, w - rightPad),
            max(topPad + 1f, h - bottomPad)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        updateContentRect()
        val cLeft = contentRect.left
        val cTop = contentRect.top
        val cRight = contentRect.right
        val cBottom = contentRect.bottom
        val cWidth = cRight - cLeft
        val cHeight = cBottom - cTop
        if (cWidth <= 0f || cHeight <= 0f) return

        val now = SystemClock.elapsedRealtime()
        val windowMs = windowSec * 1000L

        // ── 가로 그리드 (40 / 60 / 80 / 100 / 120) ──
        val gridLines = listOf(40, 60, 80, 100, 120)
        for (bpm in gridLines) {
            val y = cBottom - (bpm - minBpm) / (maxBpm - minBpm) * cHeight
            // grid 라인 — contentRect 안에서만 그림 (좌측 라벨 영역 침범 안 함)
            canvas.drawLine(cLeft, y, cRight, y, gridPaint)
            // label — grid 라인 왼쪽 padding 영역에 표시
            val labelText = "$bpm"
            val labelWidth = labelPaint.measureText(labelText)
            // grid 라인 바로 위 6px 위에 라벨 (두 그리드 사이로 떨어지는 위치)
            canvas.drawText(labelText, cLeft - labelWidth - 4f, y - 6f, labelPaint)
        }

        // ── 현재 windowSec 라벨 (우측 하단, contentRect 기준) ──
        val tag = if (windowSec >= 60) "${windowSec / 60}m${if (windowSec % 60 != 0) " ${windowSec % 60}s" else ""}" else "${windowSec}s"
        canvas.drawText(tag, cRight, cBottom + 18f, windowLabelPaint)

        // ── HR 라인 ──
        // 시간창 안의 샘플만 추출 + 시간 순으로 정렬
        val visible = samples
            .filter { now - it.first <= windowMs && now - it.first >= 0L }
            .sortedBy { it.first }
        if (visible.size < 2) return

        // ── Downsampling ─────────────────────────────────────────────
        // visible 샘플이 contentRect width 픽셀 수보다 훨씬 많으면 그릴 path 가 과도하게 무거움
        // (긴 세션 전체 뷰일 때 60분 = 3600 샘플). x-bucket 별 min/max 두 점만 그려 외형 보존.
        // bucket 폭 = 1px 기준. 즉 1px 당 최대 2 점 (min, max).
        val pxPerSec = cWidth / windowSec.toFloat()
        val bucketCount = max(1, cWidth.toInt())
        val needsDownsample = visible.size > bucketCount * 2

        val path = Path()
        if (!needsDownsample) {
            var first = true
            for ((ts, bpm) in visible) {
                val tFromRight = (now - ts).toFloat()  // 0 ~ windowMs
                val x = cRight - (tFromRight / windowMs) * cWidth
                val yClamped = bpm.toFloat().coerceIn(minBpm, maxBpm)
                val y = cBottom - (yClamped - minBpm) / (maxBpm - minBpm) * cHeight
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
        } else {
            // bucket 별로 min, max 한 쌍씩 그려 시각적 envelope 유지
            // 버킷 인덱스: 화면 왼쪽 = 0, 오른쪽 = bucketCount-1
            data class Bucket(var minBpm: Float = Float.MAX_VALUE, var maxBpm: Float = Float.MIN_VALUE, var minTs: Long = 0L, var maxTs: Long = 0L)
            val buckets = arrayOfNulls<Bucket>(bucketCount)
            for ((ts, bpm) in visible) {
                val tFromRight = (now - ts).toFloat()
                val x = cRight - (tFromRight / windowMs) * cWidth
                val idx = ((x - cLeft) / cWidth * (bucketCount - 1)).toInt().coerceIn(0, bucketCount - 1)
                val b = buckets[idx] ?: Bucket().also { buckets[idx] = it }
                val bpmF = bpm.toFloat().coerceIn(minBpm, maxBpm)
                if (bpmF < b.minBpm) { b.minBpm = bpmF; b.minTs = ts }
                if (bpmF > b.maxBpm) { b.maxBpm = bpmF; b.maxTs = ts }
            }
            var first = true
            for (i in 0 until bucketCount) {
                val b = buckets[i] ?: continue
                // bucket 내 두 점 모두 그려 min-max envelope 유지
                val xCenter = cLeft + (i + 0.5f) / bucketCount * cWidth
                val yMin = cBottom - (b.minBpm - minBpm) / (maxBpm - minBpm) * cHeight
                val yMax = cBottom - (b.maxBpm - minBpm) / (maxBpm - minBpm) * cHeight
                if (first) { path.moveTo(xCenter, yMax); first = false } else path.lineTo(xCenter, yMax)
                if (b.minBpm != b.maxBpm) path.lineTo(xCenter, yMin)
            }
        }
        canvas.drawPath(path, linePaint)
    }
}
