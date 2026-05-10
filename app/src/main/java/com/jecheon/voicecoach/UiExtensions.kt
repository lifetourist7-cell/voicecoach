package com.jecheon.voicecoach

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar

/**
 * 시스템 폰트 스케일을 무시하고 1.0x 로 고정한 Context 반환.
 *
 * 사용자가 시스템 설정에서 "글씨 크기" 를 1.5~2.0x 로 키운 경우
 * sp 단위 텍스트가 모두 같은 비율로 커져 우리 카드/세그먼트/버튼 레이아웃이 깨짐.
 * 이 helper 가 그걸 막음 — 우리 앱 안에서는 디자인 의도대로 정확한 크기로 렌더링.
 *
 * 호출 패턴 — 각 Activity 에 추가:
 * ```
 * override fun attachBaseContext(newBase: Context) {
 *     super.attachBaseContext(newBase.withFixedFontScale())
 * }
 * ```
 */
fun Context.withFixedFontScale(scale: Float = 1.0f): Context {
    val config = Configuration(resources.configuration)
    if (config.fontScale == scale) return this
    config.fontScale = scale
    return createConfigurationContext(config)
}

/**
 * 모든 Activity 가 동일한 edge-to-edge / 시스템 바 처리를 받도록 하는 단축 함수.
 *
 * - 시스템 바 (status / navigation) 뒤로 컨텐츠 확장 (`decorFitsSystemWindows = false`)
 * - 다크 테마이므로 시스템 바 아이콘은 밝은 톤 (light icons on dark)
 * - 루트 뷰에 inset 만큼 padding 추가 — 컨텐츠가 시스템 바에 가리지 않게
 *
 * `setContentView` 이후에 호출.
 */
fun AppCompatActivity.applyEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
    val root = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(top = bars.top, bottom = bars.bottom)
        insets
    }
}

/**
 * Toast 대체 — Material Snackbar.
 *
 * Toast 는 시스템 컴포넌트라 다크 테마/폰트와 어긋나 amateur 한 느낌이 강함.
 * Snackbar 는 우리 테마 색을 따르고, action 버튼도 붙일 수 있음.
 *
 * 사용법:
 * - 단순 메시지: `showMessage("자동 종료")`
 * - 액션 포함: `showMessage("권한이 필요합니다", "설정" to { startActivity(...) })`
 */
fun AppCompatActivity.showMessage(
    message: String,
    action: Pair<String, () -> Unit>? = null,
    long: Boolean = false
) {
    val rootView = findViewById<View>(android.R.id.content)
    val duration = if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
    val snackbar = Snackbar.make(rootView, message, duration)
    if (action != null) {
        snackbar.setAction(action.first) { action.second() }
    }
    snackbar.show()
}

/**
 * 길게 누르면 가속 자동 반복하는 동작.
 *
 * - ACTION_DOWN: action() 즉시 1회 + 햅틱
 * - 400ms 후부터 반복 시작 (250ms 간격)
 * - 누르고 있는 시간에 따라 점진 가속:
 *   - 0~800ms: 250ms 간격 (~4 Hz)
 *   - 800~1500ms: 150ms 간격 (~6.7 Hz)
 *   - 1500~2200ms: 100ms 간격 (10 Hz)
 *   - 2200ms+: 50ms 간격 (= **20 Hz, 최대 속도**)
 * - ACTION_UP / CANCEL: 즉시 중단
 *
 * 사용처: stepper +/- 버튼처럼 한 번 클릭으로 1단위, 길게 누르면 빠르게 변경하고 싶을 때.
 *
 * setOnClickListener 를 대체함 — 이 함수 적용한 View 에서는 onClick 별도 등록 불필요.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.setOnAutoRepeatAction(action: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var pressStart = 0L
    lateinit var runnable: Runnable
    runnable = Runnable {
        action()
        val elapsed = SystemClock.uptimeMillis() - pressStart
        val nextDelay = when {
            elapsed < 800 -> 250L
            elapsed < 1500 -> 150L
            elapsed < 2200 -> 100L
            else -> 50L  // 20 Hz cap
        }
        handler.postDelayed(runnable, nextDelay)
    }
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                action()
                pressStart = SystemClock.uptimeMillis()
                handler.postDelayed(runnable, 400L)
                v.isPressed = true
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(runnable)
                v.isPressed = false
                true
            }
            else -> false
        }
    }
}
