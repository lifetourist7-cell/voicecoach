package com.jecheon.voicecoach

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * 몰입 모드용 심박수 그래프.
 *
 * - X축: 가장 최근(우측 끝) 으로부터 [windowSec] 초 만큼의 시간 창. 기본 60초.
 *   매 frame 현재 시각 기준으로 슬라이딩.
 * - Y축: 40 ~ 120 BPM 고정.
 * - 핀치 줌으로 [windowSec] 5~120초 사이 조정.
 * - 더블탭으로 기본값(60초) 복귀.
 */
class HrGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val samples = ArrayList<Pair<Long, Int>>()
    private var windowSec = 60
    private val minBpm = 40f
    private val maxBpm = 120f

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

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val factor = d.scaleFactor
                // 핀치-아웃(확대) → 시간창 좁아짐 (작은 windowSec)
                // 핀치-인(축소)  → 시간창 넓어짐 (큰 windowSec)
                windowSec = (windowSec / factor).toInt().coerceIn(5, 120)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = SystemClock.elapsedRealtime()
        val windowMs = windowSec * 1000L

        // ── 가로 그리드 (40 / 60 / 80 / 100 / 120) ──
        val gridLines = listOf(40, 60, 80, 100, 120)
        for (bpm in gridLines) {
            val y = h - (bpm - minBpm) / (maxBpm - minBpm) * h
            canvas.drawLine(0f, y, w, y, gridPaint)
            canvas.drawText("$bpm", 6f, y - 6f, labelPaint)
        }

        // ── 현재 windowSec 라벨 (우측 하단) ──
        val tag = "${windowSec}s"
        canvas.drawText(tag, w - 6f, h - 8f, windowLabelPaint)

        // ── HR 라인 ──
        // 시간창 안의 샘플만 추출 + 시간 순으로 정렬
        val visible = samples
            .filter { now - it.first <= windowMs && now - it.first >= 0L }
            .sortedBy { it.first }
        if (visible.size < 2) return

        val path = Path()
        var first = true
        for ((ts, bpm) in visible) {
            val tFromRight = (now - ts).toFloat()  // 0 ~ windowMs
            val x = w - (tFromRight / windowMs) * w
            val yClamped = bpm.toFloat().coerceIn(minBpm, maxBpm)
            val y = h - (yClamped - minBpm) / (maxBpm - minBpm) * h
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
