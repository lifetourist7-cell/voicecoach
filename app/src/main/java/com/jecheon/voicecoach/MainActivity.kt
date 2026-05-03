package com.jecheon.voicecoach

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import androidx.transition.Fade
import androidx.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var hrService: HRForegroundService? = null
    private var isBound = false
    private lateinit var tvHeartRate: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvStatus: TextView
    private lateinit var statusProgress: View
    private lateinit var statusErrorIcon: View
    private lateinit var btnHR: ToggleButton
    private lateinit var btnPace: ToggleButton
    private lateinit var btnCadence: ToggleButton
    private lateinit var tvCadence: TextView
    private lateinit var cadencePanel: LinearLayout
    private lateinit var etInterval: EditText
    private lateinit var btnStart: Button
    private var isRunning = false
    private lateinit var spinnerLanguage: Spinner
    private lateinit var btnMetronome: ToggleButton
    private lateinit var etMetronomeBpm: EditText
    private lateinit var btnCoach: ToggleButton
    private lateinit var coachRow: LinearLayout
    private lateinit var tvTrainingSection: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnMedRetry: Button
    private lateinit var modeSelector: RadioGroup
    private lateinit var metronomePanel: LinearLayout
    private lateinit var golfPanel: LinearLayout
    private lateinit var btnGolfSwing: ToggleButton
    private lateinit var cbFlashLight: CheckBox
    private lateinit var cbFlashScreen: CheckBox
    private lateinit var flashOverlay: View
    private lateinit var dimOverlay: View
    private var flashCameraId: String? = null
    private var dimRunnable: Runnable? = null
    private var dimActive: Boolean = false
    private val dimDelayMs = 10_000L

    // 몰입 모드 활성 중에는 "뒤로" 를 앱 종료가 아닌 모드 해제로 사용
    private val focusBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitFocusMode()
        }
    }
    private lateinit var meditationPanel: LinearLayout
    private lateinit var dataCard: LinearLayout
    private lateinit var pacePanel: LinearLayout
    private lateinit var meditationDataCard: LinearLayout
    private lateinit var seekMeditationHrAvg: SeekBar
    private lateinit var tvMedHrAvgLabel: TextView
    private lateinit var tvMedHrInstant: TextView
    private lateinit var tvMedHrAvg: TextView
    private lateinit var tvMedPrevMinute: TextView
    private lateinit var tvMedCurrMinute: TextView
    private lateinit var tvMedStatus: TextView
    private lateinit var tvMedTimer: TextView
    private lateinit var btnFocusMode: TextView
    private lateinit var focusOverlay: FrameLayout
    private lateinit var tvFocusHrInstant: TextView
    private lateinit var tvFocusHrAvg: TextView
    private lateinit var tvFocusHrAvgLabel: TextView
    private lateinit var tvFocusPrev: TextView
    private lateinit var tvFocusCurr: TextView
    // 몰입 모드 호흡 phase 게이지 (메인 카드와 동시에 애니메이션)
    // 몰입 모드 자동 종료 타이머
    private lateinit var tvAutoStopLabel: TextView
    private lateinit var btnAutoStopMinus: Button
    private lateinit var btnAutoStopPlus: Button
    /** elapsedRealtime() 기준 종료 예정 시각. null = 비활성. */
    private var autoStopTargetMs: Long? = null

    private lateinit var focusBreathPhaseContainer: LinearLayout
    private lateinit var tvFocusBreathPhase: TextView
    private lateinit var focusHrGraph: HrGraphView
    private lateinit var focusBreathWhiteBar: View
    private lateinit var focusBreathBlueBar: View
    private var focusBreathWhiteAnim: ObjectAnimator? = null
    private var focusBreathBlueAnim: ObjectAnimator? = null
    private lateinit var btnBreathStart: ToggleButton
    // 백색 소음
    private lateinit var noisePresetGroup: RadioGroup
    private lateinit var rbNoiseWhite: RadioButton
    private lateinit var rbNoiseBrown: RadioButton
    private lateinit var rbNoiseCustom: RadioButton
    private lateinit var btnNoiseStart: ToggleButton
    // 호흡 프리셋 라디오 버튼 (수동 그룹) + 초 카운트 체크박스
    private lateinit var rbPresetBox: RadioButton
    private lateinit var rbPreset478: RadioButton
    private lateinit var rbPresetCoherent: RadioButton
    private lateinit var rbPresetLongExhale: RadioButton
    private lateinit var rbPresetCustom: RadioButton
    private lateinit var cbBreathMetro: CheckBox
    // 호흡 Phase 표시 + 양방향 게이지 (흰선/파란선)
    private lateinit var breathPhaseContainer: LinearLayout
    private lateinit var tvBreathPhase: TextView
    private lateinit var tvBreathPhaseRemaining: TextView
    private lateinit var tvFocusBreathPhaseRemaining: TextView
    private var phaseStartMs: Long = 0L
    private var phaseDurationSec: Int = 0
    private lateinit var breathWhiteBar: View
    private lateinit var breathBlueBar: View
    private var breathWhiteAnim: ObjectAnimator? = null
    private var breathBlueAnim: ObjectAnimator? = null
    private var lastBreathPhaseLabel: String? = null
    private var metronomePulseAnim: ObjectAnimator? = null
    private var breathPulseAnim: ObjectAnimator? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val sessionTimerRunnable = object : Runnable {
        override fun run() {
            val sec = hrService?.getSessionElapsedSec() ?: 0L
            tvMedTimer.text = formatElapsed(sec)
            // 몰입 모드 노출 중일 때만 그래프에 최신 샘플 공급 (핀치/슬라이딩은 view 가 자체 frame 으로)
            if (focusOverlay.visibility == View.VISIBLE) {
                hrService?.getHrSamples()?.let { focusHrGraph.setSamples(it) }
            }
            // 자동 종료 타이머 갱신 + 만기 트리거
            updateAutoStopLabel()
            autoStopTargetMs?.let { target ->
                if (SystemClock.elapsedRealtime() >= target) {
                    triggerAutoStop()
                    return  // 종료 진행 중 — 다음 tick 예약 안 함
                }
            }
            uiHandler.postDelayed(this, 1000L)
        }
    }

    /** +/- 버튼이 부르는 1분 단위 조정. 0초 이하로 떨어지면 해제. */
    private fun adjustAutoStop(deltaMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val current = autoStopTargetMs
        val newTarget = if (current == null) now + deltaMs else current + deltaMs
        autoStopTargetMs = if (newTarget - now > 0L) newTarget else null
        updateAutoStopLabel()
    }

    private fun updateAutoStopLabel() {
        val target = autoStopTargetMs
        val now = SystemClock.elapsedRealtime()
        if (target == null || target <= now) {
            tvAutoStopLabel.text = "−분 −초 뒤 자동 앱 종료"
        } else {
            val remainingSec = (target - now) / 1000L
            val mm = remainingSec / 60
            val ss = remainingSec % 60
            tvAutoStopLabel.text = "${mm}분 ${ss}초 뒤 자동 앱 종료"
        }
    }

    private fun triggerAutoStop() {
        autoStopTargetMs = null
        updateAutoStopLabel()
        showMessage("자동 종료")
        stopHRService()
        finishAffinity()
    }

    /** 호흡 phase 의 남은 시간을 0.1초 단위로 갱신 (100ms 주기). 메인/몰입 양쪽 텍스트. */
    private val phaseRemainingRunnable = object : Runnable {
        override fun run() {
            if (phaseStartMs > 0L && phaseDurationSec > 0) {
                val elapsedMs = SystemClock.elapsedRealtime() - phaseStartMs
                val remainingMs = (phaseDurationSec * 1000L - elapsedMs).coerceAtLeast(0L)
                val text = String.format("%.1f초", remainingMs / 1000.0)
                tvBreathPhaseRemaining.text = text
                tvFocusBreathPhaseRemaining.text = text
            }
            uiHandler.postDelayed(this, 100L)
        }
    }

    private fun startPhaseRemainingTicker() {
        uiHandler.removeCallbacks(phaseRemainingRunnable)
        uiHandler.post(phaseRemainingRunnable)
    }

    private fun stopPhaseRemainingTicker() {
        uiHandler.removeCallbacks(phaseRemainingRunnable)
        phaseStartMs = 0L
        phaseDurationSec = 0
        tvBreathPhaseRemaining.text = ""
        tvFocusBreathPhaseRemaining.text = ""
    }

    private fun formatElapsed(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private var lastStartToggleMs = 0L
    private val startDebounceMs = 800L
    private var suppressListeners = false

    companion object {
        // requestCode 충돌 방지 — 각각 다른 값으로 분리.
        // (이전엔 REQ_ACTIVITY_RECOGNITION 도 1001 이라 cadence 권한 결과가 운동 시작 권한 결과로
        //  오인되어 startHRService 가 호출되는 버그가 있었음)
        private const val REQ_HR_PERMISSIONS = 1001
        private const val REQ_ACTIVITY_RECOGNITION = 1002
        private const val REQ_CAMERA_TORCH = 1003
        private const val REQ_BT_ENABLE = 2001
    }

    private val intervalApplyRunnable = Runnable {
        val v = etInterval.text.toString().toIntOrNull() ?: return@Runnable
        if (v !in 1..600) return@Runnable
        pushOptionsToService()
    }
    private val metroApplyRunnable = Runnable {
        val v = etMetronomeBpm.text.toString().toIntOrNull() ?: return@Runnable
        if (v !in 30..400) return@Runnable
        applyMetronomeIfOn()
    }

    private val languages = listOf(
        Pair("한국어", Locale.KOREAN),
        Pair("English (US)", Locale.US),
        Pair("English (UK)", Locale.UK)
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as HRForegroundService.LocalBinder
            hrService = localBinder.getService()
            isBound = true

            hrService?.setCallback { hr: Int, pace: String, status: String ->
                runOnUiThread {
                    tvHeartRate.text = if (hr > 0) "$hr" else "--"
                    tvPace.text = if (pace.isNotEmpty()) pace else "--'--\""
                    // 케이던스 — Service 에서 직접 가져옴 (callback 인자엔 없음)
                    if (btnCadence.isChecked) {
                        val cad = hrService?.getCurrentCadence() ?: 0
                        tvCadence.text = if (cad > 0) "$cad" else "--"
                    }
                    tvStatus.text = status
                    // ── State 감지 → spinner / error icon / retry 버튼 노출 결정
                    // 에러: "찾지 못", "실패", "오류", "꺼짐" 등
                    // 로딩: "연결 중", "스캔", "찾는 중" 등
                    // 정상: 그 외 (수신 중)
                    val isError = listOf("찾지 못", "실패", "오류", "꺼짐").any { status.contains(it) }
                    val isLoading = !isError && hr <= 0 &&
                        listOf("연결 중", "스캔", "찾는", "대기").any { status.contains(it) }
                    // 시스템 바 / 상태 아이콘 전환을 fade 로 부드럽게
                    (tvStatus.parent as? android.view.ViewGroup)?.let { parent ->
                        TransitionManager.beginDelayedTransition(parent, Fade().apply { duration = 200 })
                    }
                    statusErrorIcon.visibility = if (isError) View.VISIBLE else View.GONE
                    statusProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
                    val retryVis = if (isError) View.VISIBLE else View.GONE
                    btnRetry.visibility = retryVis
                    btnMedRetry.visibility = retryVis
                    // Meditation 전용 카드도 실시간 동기화
                    if (meditationDataCard.visibility == View.VISIBLE) {
                        updateMeditationUi(hr, status)
                    }
                }
            }
            // 호흡 phase 변경 알림 → 텍스트 갱신 + 게이지 애니메이션
            hrService?.setBreathPhaseCallback { label, durationSec ->
                runOnUiThread { onBreathPhaseChanged(label, durationSec) }
            }
            // 코치 임계값 이탈 → HR 숫자 영역 flash
            hrService?.setCoachAlertCallback { upper ->
                runOnUiThread { flashCoachAlert(upper) }
            }
            // 메트로놈 beat → 시각 피드백 (골프 시각 피드백 체크박스 연동)
            hrService?.setBeatCallback { isImpact ->
                runOnUiThread { onMetronomeBeat(isImpact) }
            }
            // 세션 타이머 시작 (Meditation 카드에서만 노출)
            uiHandler.removeCallbacks(sessionTimerRunnable)
            uiHandler.post(sessionTimerRunnable)

            // 서비스 → UI 및 UI → 서비스 동기화
            hrService?.let {
                val serviceMetronomeRunning = it.isMetronomeRunning()
                val mode = when (modeSelector.checkedRadioButtonId) {
                    R.id.modeGolf -> "golf"
                    R.id.modeMeditation -> "meditation"
                    else -> "running"
                }
                if (serviceMetronomeRunning) {
                    // 서비스 메트로놈이 이미 돌고 있으면 UI 를 거기에 맞춤 (앱 재진입 등)
                    suppressListeners = true
                    when (mode) {
                        "golf" -> btnGolfSwing.isChecked = true
                        "running" -> btnMetronome.isChecked = true
                        // meditation 에선 메트로놈을 쓰지 않음
                    }
                    suppressListeners = false
                } else {
                    // 서비스는 꺼져있는데 UI 가 원하는 상태면 시작
                    applyMetronomeIfOn()
                }

                tvHeartRate.text = if (it.getCurrentHR() > 0) "${it.getCurrentHR()}" else "--"
                val p = it.getCurrentPace()
                tvPace.text = if (p.isNotEmpty()) p else "--'--\""
            }
            // 기타 옵션(HR/PACE/코치 등) 반영
            pushOptionsToService()
            setRunningState(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            hrService = null
        }
    }

    /** 시스템 폰트 스케일 무시 — 항상 1.0x 로 렌더링. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withFixedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen 은 super.onCreate 전에 install — Android 12+ 에서 시스템 스플래시,
        // 그 이하에서는 backport 로 동일 효과 (검정 배경 + 로고)
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 첫 실행 — setContentView 전에 OnboardingActivity 로 즉시 redirect (Main 화면 flash 방지)
        val firstLaunchPrefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        if (!firstLaunchPrefs.getBoolean("first_launch_done", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)  // fade 없이 즉시 전환 (스플래시 → 온보딩)
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        applyEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, focusBackCallback)

        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvPace = findViewById(R.id.tvPace)
        tvStatus = findViewById(R.id.tvStatus)
        statusProgress = findViewById(R.id.statusProgress)
        statusErrorIcon = findViewById(R.id.statusErrorIcon)
        btnHR = findViewById(R.id.btnHR)
        btnPace = findViewById(R.id.btnPace)
        btnCadence = findViewById(R.id.btnCadence)
        tvCadence = findViewById(R.id.tvCadence)
        cadencePanel = findViewById(R.id.cadencePanel)
        etInterval = findViewById(R.id.etInterval)
        btnStart = findViewById(R.id.btnStart)
        val btnHelp = findViewById<ImageButton>(R.id.btnHelp)
        btnHelp.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        btnMetronome = findViewById(R.id.btnMetronome)
        etMetronomeBpm = findViewById(R.id.etMetronomeBpm)
        btnCoach = findViewById(R.id.btnCoach)
        coachRow = findViewById(R.id.coachRow)
        tvTrainingSection = findViewById(R.id.tvTrainingSection)
        btnRetry = findViewById(R.id.btnRetry)
        btnMedRetry = findViewById(R.id.btnMedRetry)
        val retryListener = View.OnClickListener {
            stopHRService()
            requestPermissionsAndStart()
        }
        btnRetry.setOnClickListener(retryListener)
        btnMedRetry.setOnClickListener(retryListener)
        findViewById<View>(R.id.btnCoachSettings).setOnClickListener {
            startActivity(Intent(this, CoachSettingsActivity::class.java))
        }

        // 모드 선택 + 각 모드 패널
        modeSelector = findViewById(R.id.modeSelector)
        metronomePanel = findViewById(R.id.metronomePanel)
        golfPanel = findViewById(R.id.golfPanel)
        btnGolfSwing = findViewById(R.id.btnGolfSwing)
        cbFlashLight = findViewById(R.id.cbFlashLight)
        cbFlashScreen = findViewById(R.id.cbFlashScreen)
        flashOverlay = findViewById(R.id.flashOverlay)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimOverlay.setOnClickListener { onDimTouched() }
        meditationPanel = findViewById(R.id.meditationPanel)
        dataCard = findViewById(R.id.dataCard)
        pacePanel = findViewById(R.id.pacePanel)
        meditationDataCard = findViewById(R.id.meditationDataCard)
        seekMeditationHrAvg = findViewById(R.id.seekMeditationHrAvg)
        tvMedHrAvgLabel = findViewById(R.id.tvMedHrAvgLabel)
        tvMedHrInstant = findViewById(R.id.tvMedHrInstant)
        tvMedHrAvg = findViewById(R.id.tvMedHrAvg)
        tvMedPrevMinute = findViewById(R.id.tvMedPrevMinute)
        tvMedCurrMinute = findViewById(R.id.tvMedCurrMinute)
        tvMedStatus = findViewById(R.id.tvMedStatus)
        tvMedTimer = findViewById(R.id.tvMedTimer)
        btnFocusMode = findViewById(R.id.btnFocusMode)
        focusOverlay = findViewById(R.id.focusOverlay)
        tvFocusHrInstant = findViewById(R.id.tvFocusHrInstant)
        tvFocusHrAvg = findViewById(R.id.tvFocusHrAvg)
        tvFocusHrAvgLabel = findViewById(R.id.tvFocusHrAvgLabel)
        tvFocusPrev = findViewById(R.id.tvFocusPrev)
        tvFocusCurr = findViewById(R.id.tvFocusCurr)
        tvAutoStopLabel = findViewById(R.id.tvAutoStopLabel)
        btnAutoStopMinus = findViewById(R.id.btnAutoStopMinus)
        btnAutoStopPlus = findViewById(R.id.btnAutoStopPlus)
        btnAutoStopPlus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            adjustAutoStop(60_000L)
        }
        btnAutoStopMinus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            adjustAutoStop(-60_000L)
        }
        focusBreathPhaseContainer = findViewById(R.id.focusBreathPhaseContainer)
        tvFocusBreathPhase = findViewById(R.id.tvFocusBreathPhase)
        tvFocusBreathPhaseRemaining = findViewById(R.id.tvFocusBreathPhaseRemaining)
        focusHrGraph = findViewById(R.id.focusHrGraph)
        focusBreathWhiteBar = findViewById(R.id.focusBreathWhiteBar)
        focusBreathBlueBar = findViewById(R.id.focusBreathBlueBar)

        btnFocusMode.setOnClickListener { enterFocusMode() }
        btnFocusMode.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            AlertDialog.Builder(this)
                .setTitle("집중 모드 추가 옵션")
                .setMessage(
                    "호흡 적응 후 사운드만 자동으로 끄고 싶다면\n" +
                    "설정에서 'N분 후 사운드 자동 음소거' 를 켜세요.\n\n" +
                    "· 기본은 꺼져 있어 평소엔 사운드가 그대로 유지됩니다.\n" +
                    "· 켜두면 집중 모드 진입 + 세션 N분 경과 시 호흡 가이드와 심박수 음성이 자동으로 꺼지고, 시각/화면만 남습니다."
                )
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("닫기", null)
                .show()
            true
        }
        // focusOverlay 자체 클릭 → 종료 동작은 제거 (그래프의 핀치/더블탭 인터랙션과 충돌)
        // 종료는 시스템 뒤로가기 (focusBackCallback) 만 사용.
        btnBreathStart = findViewById(R.id.btnBreathStart)
        noisePresetGroup = findViewById(R.id.noisePresetGroup)
        rbNoiseWhite = findViewById(R.id.rbNoiseWhite)
        rbNoiseBrown = findViewById(R.id.rbNoiseBrown)
        rbNoiseCustom = findViewById(R.id.rbNoiseCustom)
        btnNoiseStart = findViewById(R.id.btnNoiseStart)
        findViewById<View>(R.id.btnNoiseEdit).setOnClickListener { showNoiseDialog() }
        rbPresetBox = findViewById(R.id.rbPresetBox)
        rbPreset478 = findViewById(R.id.rbPreset478)
        rbPresetCoherent = findViewById(R.id.rbPresetCoherent)
        rbPresetLongExhale = findViewById(R.id.rbPresetLongExhale)
        rbPresetCustom = findViewById(R.id.rbPresetCustom)
        findViewById<View>(R.id.btnCustomEdit).setOnClickListener { showCustomBreathDialog() }
        cbBreathMetro = findViewById(R.id.cbBreathMetro)
        breathPhaseContainer = findViewById(R.id.breathPhaseContainer)
        tvBreathPhase = findViewById(R.id.tvBreathPhase)
        tvBreathPhaseRemaining = findViewById(R.id.tvBreathPhaseRemaining)
        breathWhiteBar = findViewById(R.id.breathWhiteBar)
        breathBlueBar = findViewById(R.id.breathBlueBar)

        // 4개 bar 모두 layout 시점마다 pivotX 를 정확히 중앙으로 재설정.
        // visibility GONE → VISIBLE 전환 직후에도 width 가 잡히는 즉시 반영되어
        // 양방향 scaleX 애니메이션이 한쪽으로 쏠리지 않게 보장.
        val pivotCenterListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = v.width / 2f
        }
        breathWhiteBar.addOnLayoutChangeListener(pivotCenterListener)
        breathBlueBar.addOnLayoutChangeListener(pivotCenterListener)
        focusBreathWhiteBar.addOnLayoutChangeListener(pivotCenterListener)
        focusBreathBlueBar.addOnLayoutChangeListener(pivotCenterListener)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        btnStart.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastStartToggleMs < startDebounceMs) return@setOnClickListener
            lastStartToggleMs = now
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (isRunning) {
                stopHRService()
            } else {
                requestPermissionsAndStart()
            }
        }

        findViewById<Button>(R.id.btnBpmPlus).setOnClickListener {
            val cur = etMetronomeBpm.text.toString().toIntOrNull() ?: 180
            etMetronomeBpm.setText((cur + 1).coerceAtMost(400).toString())
            applyMetronomeIfOn()
        }
        findViewById<Button>(R.id.btnBpmMinus).setOnClickListener {
            val cur = etMetronomeBpm.text.toString().toIntOrNull() ?: 180
            etMetronomeBpm.setText((cur - 1).coerceAtLeast(1).toString())
            applyMetronomeIfOn()
        }

        // 토글 변경 → 즉시 반영 + 햅틱
        val applyOptionsListener = CompoundButton.OnCheckedChangeListener { v, _ ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            pushOptionsToService()
        }
        btnHR.setOnCheckedChangeListener(applyOptionsListener)
        btnPace.setOnCheckedChangeListener(applyOptionsListener)
        btnCoach.setOnCheckedChangeListener(applyOptionsListener)
        btnCadence.setOnCheckedChangeListener { v, checked ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            // SPM 패널은 상시 표시 — 토글은 측정/안내 ON-OFF 만.
            // OFF 시 표시는 "--" 로 리셋.
            if (checked) {
                // ACTIVITY_RECOGNITION 권한 (Android 10+) 런타임 요청
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val granted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACTIVITY_RECOGNITION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                            REQ_ACTIVITY_RECOGNITION
                        )
                    }
                }
            } else {
                tvCadence.text = "--"
            }
            pushOptionsToService()
        }

        btnMetronome.setOnCheckedChangeListener { v, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (!isBound) return@setOnCheckedChangeListener
            if (checked) {
                val bpm = etMetronomeBpm.text.toString().toIntOrNull() ?: 180
                hrService?.startMetronome(bpm)
                startPulse(btnMetronome).also { metronomePulseAnim = it }
            } else {
                hrService?.stopMetronome()
                stopPulse(metronomePulseAnim, btnMetronome); metronomePulseAnim = null
            }
        }

        // 언어 스피너 → 즉시 반영
        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pushOptionsToService()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 읽기 간격 ±
        findViewById<Button>(R.id.btnIntervalMinus).setOnClickListener {
            val cur = etInterval.text.toString().toIntOrNull() ?: 3
            etInterval.setText((cur - 1).coerceAtLeast(1).toString())
            pushOptionsToService()
        }
        findViewById<Button>(R.id.btnIntervalPlus).setOnClickListener {
            val cur = etInterval.text.toString().toIntOrNull() ?: 3
            etInterval.setText((cur + 1).toString())
            pushOptionsToService()
        }

        // EditText 직접 입력 → focus 벗어날 때 반영
        etInterval.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                val v = etInterval.text.toString().toIntOrNull()?.coerceIn(1, 600)
                if (v != null) {
                    etInterval.setText(v.toString())
                    pushOptionsToService()
                }
            }
        }
        etMetronomeBpm.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                val v = etMetronomeBpm.text.toString().toIntOrNull()?.coerceIn(30, 400)
                if (v != null) {
                    etMetronomeBpm.setText(v.toString())
                    applyMetronomeIfOn()
                }
            }
        }

        // 타이핑 직접 입력 시에도 반영 — 500ms debounce
        // (focus 가 빠지지 않아도, 입력이 멈추면 자동 반영됨)
        etInterval.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                etInterval.removeCallbacks(intervalApplyRunnable)
                etInterval.postDelayed(intervalApplyRunnable, 500)
            }
            override fun beforeTextChanged(cs: CharSequence?, s: Int, c: Int, a: Int) {}
            override fun onTextChanged(cs: CharSequence?, s: Int, b: Int, c: Int) {}
        })
        etMetronomeBpm.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                etMetronomeBpm.removeCallbacks(metroApplyRunnable)
                etMetronomeBpm.postDelayed(metroApplyRunnable, 500)
            }
            override fun beforeTextChanged(cs: CharSequence?, s: Int, c: Int, a: Int) {}
            override fun onTextChanged(cs: CharSequence?, s: Int, b: Int, c: Int) {}
        })
        // 키보드 "완료" 눌렀을 때 포커스 해제 → focus listener 발동으로 즉시 교정 반영
        val doneAction = android.widget.TextView.OnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                true
            } else false
        }
        etInterval.setOnEditorActionListener(doneAction)
        etMetronomeBpm.setOnEditorActionListener(doneAction)

        // ─── 모드 선택 (달리기 / 골프 / Meditation) ───
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        // 골프 모드 가시성 먼저 적용 — 비활성화 상태면 prefs 의 app_mode 도 보정해서
        // 아래 savedMode 조회 시 올바른 값이 들어오도록
        applyGolfModeVisibility()
        val savedMode = prefs.getString("app_mode", "running") ?: "running"
        val initialModeBtn = when (savedMode) {
            "golf" -> R.id.modeGolf
            "meditation" -> R.id.modeMeditation
            else -> R.id.modeRunning
        }
        modeSelector.check(initialModeBtn)
        applyModeUi(savedMode)

        modeSelector.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.modeGolf -> "golf"
                R.id.modeMeditation -> "meditation"
                else -> "running"
            }
            prefs.edit().putString("app_mode", mode).apply()
            applyModeUi(mode)

            // 모드 전환 시 기존 재생 전부 정지 + 각 토글 리셋 (혼란 방지)
            hrService?.stopMetronome()
            hrService?.stopBreathwork()
            hrService?.stopNoise()
            suppressListeners = true
            btnMetronome.isChecked = false
            btnGolfSwing.isChecked = false
            btnBreathStart.isChecked = false
            btnNoiseStart.isChecked = false
            suppressListeners = false
            breathPhaseContainer.visibility = View.GONE
            focusBreathPhaseContainer.visibility = View.GONE
            breathWhiteAnim?.cancel(); breathWhiteAnim = null
            breathBlueAnim?.cancel(); breathBlueAnim = null
            focusBreathWhiteAnim?.cancel(); focusBreathWhiteAnim = null
            focusBreathBlueAnim?.cancel(); focusBreathBlueAnim = null
            breathWhiteBar.scaleX = 0f; breathBlueBar.scaleX = 0f
            focusBreathWhiteBar.scaleX = 0f; focusBreathBlueBar.scaleX = 0f
            lastBreathPhaseLabel = null
            stopPhaseRemainingTicker()
            // 모드 전환 → 시각 피드백 + 몰입 모드 정리
            forceTorchOff()
            flashOverlay.animate().cancel(); flashOverlay.alpha = 0f
            cancelDimSchedule(); deactivateDim(animate = false)
            if (focusOverlay.visibility == View.VISIBLE) exitFocusMode()
            updateKeepScreenOn()

            // 서비스에 mode 반영 (Meditation 이면 TTS 루프를 평균 HR 안내 경로로 전환)
            pushOptionsToService()

            // Meditation 처음 선택 시 1회 팝업
            if (mode == "meditation" && !prefs.getBoolean("meditation_tip_shown", false)) {
                showMeditationTip()
                prefs.edit().putBoolean("meditation_tip_shown", true).apply()
            }
        }

        // 후면 플래시(라이트) 가능 카메라 ID 탐지
        initFlashCameraId()

        // 시각 피드백 체크박스 상태 복원 + 리스너
        cbFlashLight.isChecked = prefs.getBoolean("golf_flash_light_enabled", false)
        cbFlashScreen.isChecked = prefs.getBoolean("golf_flash_screen_enabled", false)
        cbFlashLight.setOnCheckedChangeListener { v, checked ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (checked) {
                // 일부 OEM 은 setTorchMode 에 CAMERA 권한을 요구. 미허용이면 즉시 요청.
                val granted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.CAMERA),
                        REQ_CAMERA_TORCH
                    )
                    // 권한 응답 받기 전엔 prefs 저장하지 않음 — 결과에 따라 onRequestPermissionsResult 가 처리
                    return@setOnCheckedChangeListener
                }
            }
            prefs.edit().putBoolean("golf_flash_light_enabled", checked).apply()
            if (!checked) forceTorchOff()
            updateKeepScreenOn()
        }
        cbFlashScreen.setOnCheckedChangeListener { v, checked ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            prefs.edit().putBoolean("golf_flash_screen_enabled", checked).apply()
            if (checked) {
                showMessage(
                    "10초 후 화면이 검정색으로 변합니다. 화면을 터치하면 다시 돌아옵니다.",
                    long = true
                )
                scheduleDimIfNeeded()
            } else {
                flashOverlay.animate().cancel(); flashOverlay.alpha = 0f
                cancelDimSchedule(); deactivateDim(animate = false)
            }
            updateKeepScreenOn()
        }

        // 클럽 프리셋 — 짧은 탭: BPM 적용 / 길게 탭: BPM 편집 다이얼로그
        setupClubPreset(R.id.btnClubDriver, "드라이버", "golf_preset_driver_bpm", 76)
        setupClubPreset(R.id.btnClubIron, "아이언", "golf_preset_iron_bpm", 90)
        setupClubPreset(R.id.btnClubWedge, "웨지", "golf_preset_wedge_bpm", 103)

        // 스윙 연습 토글 — ON: 3:1 패턴 메트로놈 루프 시작, OFF: 중지
        // (app_mode == "golf" 이면 startMetronome Runnable 이 4번째 비트에 임팩트 톤 삽입)
        btnGolfSwing.setOnCheckedChangeListener { v, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (!isBound) {
                showMessage("먼저 '시작' 을 눌러주세요")
                suppressListeners = true
                btnGolfSwing.isChecked = false
                suppressListeners = false
                return@setOnCheckedChangeListener
            }
            if (checked) {
                val bpm = etMetronomeBpm.text.toString().toIntOrNull()?.coerceIn(40, 200) ?: 90
                hrService?.startMetronome(bpm)
                startPulse(btnGolfSwing).also { metronomePulseAnim = it }
                scheduleDimIfNeeded()
            } else {
                hrService?.stopMetronome()
                stopPulse(metronomePulseAnim, btnGolfSwing); metronomePulseAnim = null
                forceTorchOff()
                flashOverlay.animate().cancel(); flashOverlay.alpha = 0f
                cancelDimSchedule(); deactivateDim(animate = false)
            }
            updateKeepScreenOn()
        }

        // ─── Meditation 모드 ───
        // HR 평균 윈도우 슬라이더 (1~10초)
        val avgInit = prefs.getInt("meditation_hr_avg_sec", 10).coerceIn(1, 10)
        seekMeditationHrAvg.progress = avgInit - 1
        tvMedHrAvgLabel.text = "${avgInit}초 평균"
        tvFocusHrAvgLabel.text = "${avgInit}초 평균"
        seekMeditationHrAvg.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = progress + 1
                tvMedHrAvgLabel.text = "${sec}초 평균"
                tvFocusHrAvgLabel.text = "${sec}초 평균"
                prefs.edit().putInt("meditation_hr_avg_sec", sec).apply()
                // 슬라이더 이동 즉시 평균 BPM 숫자 갱신
                val avg = hrService?.getAvgHR(sec) ?: 0
                val avgText = if (avg > 0) "$avg" else "--"
                tvMedHrAvg.text = avgText
                if (focusOverlay.visibility == View.VISIBLE) tvFocusHrAvg.text = avgText
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 호흡 프리셋 수동 라디오 그룹
        val presetButtons = mapOf(
            "box" to rbPresetBox,
            "478" to rbPreset478,
            "coherent" to rbPresetCoherent,
            "long_exhale" to rbPresetLongExhale,
            "custom" to rbPresetCustom
        )

        // 초기 선택 상태 복원
        val savedPreset = prefs.getString("meditation_preset", "box") ?: "box"
        presetButtons.forEach { (id, rb) -> rb.isChecked = (id == savedPreset) }
        cbBreathMetro.isChecked = prefs.getBoolean("breath_metro_enabled", false)

        // 라디오 (수동 상호 배제)
        presetButtons.forEach { (id, rb) ->
            rb.setOnClickListener {
                rb.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                presetButtons.forEach { (_, other) -> other.isChecked = (other == rb) }
                prefs.edit().putString("meditation_preset", id).apply()
                // 호흡 진행 중이었다면 새 프리셋으로 재시작 (첫 사이클 TTS 부터)
                if (btnBreathStart.isChecked && isBound) {
                    hrService?.startBreathwork(id)
                }
            }
            rb.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showBreathTechniqueInfo(id)
                true
            }
        }

        // 초 카운트 공용 체크박스 — 4개 프리셋 모두에 적용. 체크만 하면 서비스의
        // 매초 tick 로직이 prefs 를 즉시 읽어 반영 (별도 재시작 불필요)
        cbBreathMetro.setOnCheckedChangeListener { v, checked ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            prefs.edit().putBoolean("breath_metro_enabled", checked).apply()
        }

        // ─── 백색 소음 ───
        val savedNoiseType = prefs.getString("noise_preset", "white") ?: "white"
        when (savedNoiseType) {
            "brown" -> rbNoiseBrown.isChecked = true
            "custom" -> rbNoiseCustom.isChecked = true
            else -> rbNoiseWhite.isChecked = true
        }
        noisePresetGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rbNoiseBrown -> "brown"
                R.id.rbNoiseCustom -> "custom"
                else -> "white"
            }
            prefs.edit().putString("noise_preset", type).apply()
            // 재생 중이면 즉시 반영
            if (btnNoiseStart.isChecked && isBound) startNoiseFromPrefs()
        }
        btnNoiseStart.setOnCheckedChangeListener { v, checked ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (!isBound) {
                showMessage("먼저 '시작' 을 눌러주세요")
                suppressListeners = true
                btnNoiseStart.isChecked = false
                suppressListeners = false
                return@setOnCheckedChangeListener
            }
            if (checked) startNoiseFromPrefs() else hrService?.stopNoise()
        }

        // 호흡 시작 토글
        btnBreathStart.setOnCheckedChangeListener { v, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (!isBound) {
                showMessage("먼저 '시작' 을 눌러주세요")
                suppressListeners = true
                btnBreathStart.isChecked = false
                suppressListeners = false
                return@setOnCheckedChangeListener
            }
            if (checked) {
                val presetId = prefs.getString("meditation_preset", "box") ?: "box"
                lastBreathPhaseLabel = null
                breathWhiteBar.scaleX = 0f
                breathBlueBar.scaleX = 0f
                focusBreathWhiteBar.scaleX = 0f
                focusBreathBlueBar.scaleX = 0f
                hrService?.startBreathwork(presetId)
                breathPhaseContainer.visibility = View.VISIBLE
                focusBreathPhaseContainer.visibility = View.VISIBLE
                startPulse(btnBreathStart).also { breathPulseAnim = it }
                startPhaseRemainingTicker()
            } else {
                hrService?.stopBreathwork()
                breathPhaseContainer.visibility = View.GONE
                focusBreathPhaseContainer.visibility = View.GONE
                breathWhiteAnim?.cancel(); breathWhiteAnim = null
                breathBlueAnim?.cancel(); breathBlueAnim = null
                focusBreathWhiteAnim?.cancel(); focusBreathWhiteAnim = null
                focusBreathBlueAnim?.cancel(); focusBreathBlueAnim = null
                breathWhiteBar.scaleX = 0f; breathBlueBar.scaleX = 0f
                focusBreathWhiteBar.scaleX = 0f; focusBreathBlueBar.scaleX = 0f
                lastBreathPhaseLabel = null
                stopPulse(breathPulseAnim, btnBreathStart); breathPulseAnim = null
                stopPhaseRemainingTicker()
            }
        }

        // 첫 실행 redirect 는 onCreate 초반에 이미 처리됨.
        // 여기서는 일반 진입 시 배터리 최적화 안내만.
        maybePromptBatteryOptimization()
    }

    override fun onStart() {
        super.onStart()
        // 서비스가 돌고 있으면 바인드 (새로 시작하지는 않음)
        if (!isBound) {
            try {
                bindService(Intent(this, HRForegroundService::class.java), serviceConnection, 0)
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        // 현재 서비스 상태와 UI 동기화
        if (!isBound) {
            // 서비스가 죽어있으면 시작 버튼 상태 리셋
            setRunningState(false)
        }
        // Settings 에서 골프 모드 토글 변경하고 돌아왔을 때 즉시 반영
        applyGolfModeVisibility()
    }

    /**
     * 골프 모드 가시성 + 일관성 보정.
     *
     * - prefs `golf_mode_enabled = false` 면 메인 화면 모드 탭에서 골프 RadioButton 숨김
     * - 만약 현재 저장된 mode 가 "golf" 인데 비활성화 상태면 "running" 으로 강제 전환
     *   (Settings 에서 끄고 돌아올 때 일관성 유지)
     *
     * onCreate 와 onResume 에서 호출.
     */
    private fun applyGolfModeVisibility() {
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val golfEnabled = prefs.getBoolean("golf_mode_enabled", false)
        val modeGolfBtn = findViewById<RadioButton>(R.id.modeGolf)
        modeGolfBtn.visibility = if (golfEnabled) View.VISIBLE else View.GONE

        if (!golfEnabled && prefs.getString("app_mode", "running") == "golf") {
            prefs.edit().putString("app_mode", "running").apply()
            // modeSelector 가 현재 골프 선택 중이면 running 으로 전환 (listener 가 applyModeUi 호출)
            if (modeSelector.checkedRadioButtonId == R.id.modeGolf) {
                modeSelector.check(R.id.modeRunning)
            }
        }
    }

    private fun pushOptionsToService() {
        if (!isBound) return
        val interval = etInterval.text.toString().toIntOrNull() ?: 3
        val selectedLocale = languages[spinnerLanguage.selectedItemPosition].second
        hrService?.setOptions(
            btnHR.isChecked,
            btnPace.isChecked,
            interval,
            selectedLocale,
            btnCoach.isChecked,
            btnCadence.isChecked
        )
    }

    private fun applyMetronomeIfOn() {
        if (!isBound) return
        // 메트로놈 또는 골프 스윙 연습 어느 쪽이든 돌고 있으면 BPM 변경을 즉시 반영
        val running = btnMetronome.isChecked || btnGolfSwing.isChecked
        if (running) {
            val bpm = etMetronomeBpm.text.toString().toIntOrNull() ?: 180
            hrService?.startMetronome(bpm)
        }
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        btnStart.text = if (running) "중지" else when (modeSelector.checkedRadioButtonId) {
            R.id.modeGolf -> "골프 시작"
            R.id.modeMeditation -> "Meditation 시작"
            else -> "달리기 시작"
        }
        btnStart.setBackgroundResource(
            if (running) R.drawable.bg_stop_button else R.drawable.bg_start_button
        )
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            if (!ensureBluetoothOn()) return
            startHRService()
        } else {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), REQ_HR_PERMISSIONS)
        }
    }

    private fun ensureBluetoothOn(): Boolean {
        val bm = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null) {
            tvStatus.text = "이 기기는 블루투스를 지원하지 않습니다"
            return false
        }
        if (!adapter.isEnabled) {
            try {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2001)
            } catch (_: SecurityException) {
                tvStatus.text = "블루투스 권한 필요"
            }
            return false
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BT_ENABLE) {
            if (resultCode == RESULT_OK) {
                startHRService()
            } else {
                tvStatus.text = "블루투스 꺼짐"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_HR_PERMISSIONS -> {
                // BLE / 위치 / 알림 등 운동 시작에 필요한 권한 묶음
                if (grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    if (ensureBluetoothOn()) startHRService()
                } else {
                    tvStatus.text = "권한 허용 필요"
                }
            }
            REQ_ACTIVITY_RECOGNITION -> {
                // 케이던스 측정용 — 거부되어도 운동은 시작 가능. 토글만 끄고 안내.
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (!granted) {
                    btnCadence.isChecked = false
                    showMessage("케이던스 측정에는 활동 감지 권한이 필요합니다")
                }
            }
            REQ_CAMERA_TORCH -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    // cbFlashLight 는 이미 체크된 상태로 유지 — prefs 저장 + keep-screen 갱신만
                    // (cbFlashLight listener 가 권한 요청 후 return 하면서 prefs 저장을 미뤘음)
                    getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
                        .edit().putBoolean("golf_flash_light_enabled", true).apply()
                    updateKeepScreenOn()
                } else {
                    cbFlashLight.isChecked = false
                    showMessage("라이트 사용에는 카메라 권한이 필요합니다")
                }
            }
        }
    }

    private fun maybePromptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val asked = prefs.getBoolean("battery_opt_asked", false)
        if (asked) return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_opt_asked", true).apply()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("백그라운드 실행 허용")
            .setMessage("운동 중 앱이 강제로 종료되지 않도록 배터리 최적화 예외에 추가해주세요.")
            .setPositiveButton("설정 열기") { _, _ ->
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                prefs.edit().putBoolean("battery_opt_asked", true).apply()
            }
            .setNegativeButton("나중에") { _, _ ->
                prefs.edit().putBoolean("battery_opt_asked", true).apply()
            }
            .show()
    }

    private fun startHRService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            hrService = null
        }
        val intent = Intent(this, HRForegroundService::class.java)
        stopService(intent)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        tvStatus.text = "연결 중"
        setRunningState(true)
    }

    private fun stopHRService() {
        // Meditation 세션이었다면 요약 토스트 (서비스 unbind 전에 데이터 추출)
        val medSummary = hrService?.getMeditationSummary()
        hrService?.stopMetronome()
        hrService?.stopNoise()
        suppressListeners = true
        btnNoiseStart.isChecked = false
        suppressListeners = false
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, HRForegroundService::class.java)
        stopService(intent)
        uiHandler.removeCallbacks(sessionTimerRunnable)
        tvHeartRate.text = "--"
        tvPace.text = "--'--\""
        tvStatus.text = "중지됨"
        tvMedTimer.text = "00:00:00"
        forceTorchOff()
        flashOverlay.animate().cancel(); flashOverlay.alpha = 0f
        cancelDimSchedule(); deactivateDim(animate = false)
        if (focusOverlay.visibility == View.VISIBLE) exitFocusMode()
        updateKeepScreenOn()
        setRunningState(false)
        medSummary?.let { showMeditationSummary(it) }
    }

    /** 현재 prefs 에 따라 노이즈 시작. 슬라이더 값/볼륨도 prefs 에서. */
    private fun startNoiseFromPrefs() {
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val type = prefs.getString("noise_preset", "white") ?: "white"
        val cutoff = prefs.getInt("noise_custom_cutoff_hz", 5000).toFloat()
        val volume = prefs.getInt("noise_volume_pct", 50).coerceIn(0, 100) / 100f
        hrService?.startNoise(type, cutoff, volume)
    }

    /** 노이즈 커스텀 슬라이더 다이얼로그 (컷오프 + 음량). 재생 중 실시간 반영. */
    private fun showNoiseDialog() {
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_noise, null)

        val seekCutoff = view.findViewById<SeekBar>(R.id.seekNoiseCutoff)
        val etCutoff = view.findViewById<EditText>(R.id.etNoiseCutoff)
        val btnCutoffMinus = view.findViewById<Button>(R.id.btnNoiseCutoffMinus)
        val btnCutoffPlus = view.findViewById<Button>(R.id.btnNoiseCutoffPlus)
        val seekVol = view.findViewById<SeekBar>(R.id.seekNoiseVolume)
        val tvVol = view.findViewById<TextView>(R.id.tvNoiseVolume)

        // 슬라이더 max=9990, progress 0~9990 → cutoff = progress + 10 (10~10000Hz)
        val savedCutoff = prefs.getInt("noise_custom_cutoff_hz", 5000).coerceIn(10, 10000)
        seekCutoff.progress = savedCutoff - 10
        etCutoff.setText(savedCutoff.toString())
        etCutoff.setSelection(etCutoff.text.length)
        val savedVol = prefs.getInt("noise_volume_pct", 50).coerceIn(0, 100)
        seekVol.progress = savedVol
        tvVol.text = "$savedVol%"

        // 컷오프 입력 — 커스텀 모드에서만 의미. 다른 프리셋 선택 시 흐릿 + disable
        val customSelected = rbNoiseCustom.isChecked
        val cutoffAlpha = if (customSelected) 1.0f else 0.4f
        seekCutoff.isEnabled = customSelected
        seekCutoff.alpha = cutoffAlpha
        etCutoff.isEnabled = customSelected
        etCutoff.alpha = cutoffAlpha
        btnCutoffMinus.isEnabled = customSelected
        btnCutoffMinus.alpha = cutoffAlpha
        btnCutoffPlus.isEnabled = customSelected
        btnCutoffPlus.alpha = cutoffAlpha

        // ── 단일 진실 소스: commitCutoff(hz) 가 슬라이더 + EditText + prefs + live 노이즈 모두 동기화 ──
        // 슬라이더 listener (fromUser=false 일 때) 는 EditText 갱신 안 함 — 무한 루프 방지.
        fun commitCutoff(rawHz: Int) {
            val hz = rawHz.coerceIn(10, 10000)
            etCutoff.setText(hz.toString())
            etCutoff.setSelection(etCutoff.text.length)
            // 슬라이더 이동 — listener 가 prefs 저장 + live 반영 처리
            seekCutoff.progress = hz - 10
        }

        fun commitFromText() {
            val hz = etCutoff.text.toString().toIntOrNull()
                ?: (seekCutoff.progress + 10)  // 무효 입력이면 현재 슬라이더 값으로 복원
            commitCutoff(hz)
        }

        seekCutoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val hz = progress + 10
                if (fromUser) {
                    // 사용자가 슬라이더 드래그 — EditText 도 동기화
                    etCutoff.setText(hz.toString())
                    etCutoff.setSelection(etCutoff.text.length)
                }
                prefs.edit().putInt("noise_custom_cutoff_hz", hz).apply()
                // 재생 중 + 커스텀 선택 시 즉시 반영
                if (btnNoiseStart.isChecked && rbNoiseCustom.isChecked && isBound) {
                    hrService?.updateNoiseCutoff(hz.toFloat())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // EditText — Done / 포커스 잃을 때 commit
        etCutoff.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitFromText()
                true
            } else false
        }
        etCutoff.setOnFocusChangeListener { _, focused ->
            if (!focused) commitFromText()
        }

        // ± 버튼 — 1Hz 단위 미세 조정
        btnCutoffMinus.setOnClickListener {
            val cur = etCutoff.text.toString().toIntOrNull() ?: (seekCutoff.progress + 10)
            commitCutoff(cur - 1)
        }
        btnCutoffPlus.setOnClickListener {
            val cur = etCutoff.text.toString().toIntOrNull() ?: (seekCutoff.progress + 10)
            commitCutoff(cur + 1)
        }
        seekVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVol.text = "$progress%"
                prefs.edit().putInt("noise_volume_pct", progress).apply()
                if (btnNoiseStart.isChecked && isBound) {
                    hrService?.updateNoiseVolume(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("노이즈 설정")
            .setView(view)
            .setPositiveButton("닫기", null)
            .show()
    }

    /** 커스텀 호흡 사이클 편집 다이얼로그 — 4단계 stepper + 실시간 합계 */
    private fun showCustomBreathDialog() {
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_custom_breath, null)

        val etInhale = view.findViewById<EditText>(R.id.etInhaleSec)
        val etHold1 = view.findViewById<EditText>(R.id.etHold1Sec)
        val etExhale = view.findViewById<EditText>(R.id.etExhaleSec)
        val etHold2 = view.findViewById<EditText>(R.id.etHold2Sec)
        val tvTotal = view.findViewById<TextView>(R.id.tvCycleTotal)

        // 초기값 로드
        etInhale.setText(prefs.getInt("breath_custom_inhale_sec", 4).coerceIn(1, 30).toString())
        etHold1.setText(prefs.getInt("breath_custom_hold1_sec", 4).coerceIn(0, 30).toString())
        etExhale.setText(prefs.getInt("breath_custom_exhale_sec", 4).coerceIn(1, 30).toString())
        etHold2.setText(prefs.getInt("breath_custom_hold2_sec", 0).coerceIn(0, 30).toString())

        fun refreshTotal() {
            val total = (etInhale.text.toString().toIntOrNull() ?: 0) +
                (etHold1.text.toString().toIntOrNull() ?: 0) +
                (etExhale.text.toString().toIntOrNull() ?: 0) +
                (etHold2.text.toString().toIntOrNull() ?: 0)
            tvTotal.text = "한 사이클: ${total}초"
        }
        refreshTotal()

        // 각 phase 의 +/- 버튼 + EditText 변경 시 합계 갱신
        // (들숨/날숨은 1 이상, 홀드는 0 이상 / 모두 30 이하)
        fun bindStepper(et: EditText, minus: Int, plus: Int, lo: Int, hi: Int) {
            view.findViewById<Button>(minus).setOnClickListener {
                val cur = et.text.toString().toIntOrNull() ?: lo
                et.setText((cur - 1).coerceAtLeast(lo).toString())
                refreshTotal()
            }
            view.findViewById<Button>(plus).setOnClickListener {
                val cur = et.text.toString().toIntOrNull() ?: lo
                et.setText((cur + 1).coerceAtMost(hi).toString())
                refreshTotal()
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refreshTotal() }
                override fun beforeTextChanged(cs: CharSequence?, s: Int, c: Int, a: Int) {}
                override fun onTextChanged(cs: CharSequence?, s: Int, b: Int, c: Int) {}
            })
        }
        bindStepper(etInhale, R.id.btnInhaleMinus, R.id.btnInhalePlus, 1, 30)
        bindStepper(etHold1, R.id.btnHold1Minus, R.id.btnHold1Plus, 0, 30)
        bindStepper(etExhale, R.id.btnExhaleMinus, R.id.btnExhalePlus, 1, 30)
        bindStepper(etHold2, R.id.btnHold2Minus, R.id.btnHold2Plus, 0, 30)

        AlertDialog.Builder(this)
            .setTitle("커스텀 호흡 사이클")
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                val inhale = etInhale.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 4
                val hold1 = etHold1.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 0
                val exhale = etExhale.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 4
                val hold2 = etHold2.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 0
                prefs.edit()
                    .putInt("breath_custom_inhale_sec", inhale)
                    .putInt("breath_custom_hold1_sec", hold1)
                    .putInt("breath_custom_exhale_sec", exhale)
                    .putInt("breath_custom_hold2_sec", hold2)
                    .apply()
                // 호흡 진행 중이고 현재 선택이 custom 이면 새 값으로 재시작 (첫 사이클 TTS 부터)
                if (btnBreathStart.isChecked && isBound && rbPresetCustom.isChecked) {
                    hrService?.startBreathwork("custom")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 호흡 기법별 도움말 다이얼로그 (long-press 트리거) */
    private fun showBreathTechniqueInfo(presetId: String) {
        val (title, body) = when (presetId) {
            "box" -> "박스 호흡 (4-4-4-4)" to
                "들숨 4초 → 멈춤 4초 → 날숨 4초 → 멈춤 4초.\n\n" +
                "네이비 SEAL 등 군에서 스트레스 상황 진정용으로 사용. " +
                "균형 잡힌 호흡으로 자율신경계를 안정시킨다."
            "478" -> "4-7-8 수면 기법" to
                "들숨 4초 → 멈춤 7초 → 날숨 8초.\n\n" +
                "Andrew Weil 박사가 개발. 부교감신경 강하게 활성화로 빠른 진정. " +
                "수면 직전에 추천."
            "coherent" -> "분당 6회 호흡 (5-5)" to
                "들숨 5초 → 날숨 5초 (멈춤 없음).\n\n" +
                "코히어런트 호흡(Coherent Breathing). 분당 6회 페이스가 HRV 향상과 " +
                "심혈관 안정에 최적이라는 연구 다수. 일상 안정 호흡으로 적합."
            "long_exhale" -> "긴 날숨 (4-8)" to
                "들숨 4초 → 날숨 8초.\n\n" +
                "날숨이 들숨보다 길면 부교감신경이 강하게 활성화된다. " +
                "긴장 완화와 빠른 진정에 효과적."
            "custom" -> "커스텀 호흡" to
                "사용자가 직접 정의한 4단계 사이클.\n\n" +
                "라디오 옆 연필 아이콘으로 들숨 / 홀드1 / 날숨 / 홀드2 의 시간을 각각 설정할 수 있다. " +
                "홀드를 0으로 두면 그 단계는 사이클에서 빠진다."
            else -> return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("닫기", null)
            .show()
    }

    /** Meditation 세션 종료 후 짧은 요약 토스트 — 시작/끝 1분 평균 비교 */
    private fun showMeditationSummary(s: HRForegroundService.MeditationSummary) {
        val mm = s.totalSec / 60
        val ss = s.totalSec % 60
        val timeStr = String.format("%d:%02d", mm, ss)
        val msg = when {
            s.firstMinuteAvg > 0 && s.lastMinuteAvg > 0 -> {
                val delta = s.lastMinuteAvg - s.firstMinuteAvg
                val sign = if (delta > 0) "+" else ""
                "$timeStr 명상 · 시작 ${s.firstMinuteAvg} → 끝 ${s.lastMinuteAvg} (${sign}${delta} BPM)"
            }
            s.lastMinuteAvg > 0 -> "$timeStr 명상 · 평균 ${s.lastMinuteAvg} BPM"
            else -> "$timeStr 명상 완료"
        }
        showMessage(msg, long = true)
    }

    private fun applyModeUi(mode: String) {
        // 모드 전환 시 부드러운 crossfade (150ms)
        (dataCard.parent as? android.view.ViewGroup)?.let { parent ->
            TransitionManager.beginDelayedTransition(parent, Fade().apply { duration = 150 })
        }
        // 데이터 카드 전환 — Meditation 전용 카드는 별도 레이아웃
        dataCard.visibility = if (mode == "meditation") View.GONE else View.VISIBLE
        meditationDataCard.visibility = if (mode == "meditation") View.VISIBLE else View.GONE
        // 훈련 도구 패널 전환
        metronomePanel.visibility = if (mode == "running") View.VISIBLE else View.GONE
        golfPanel.visibility = if (mode == "golf") View.VISIBLE else View.GONE
        meditationPanel.visibility = if (mode == "meditation") View.VISIBLE else View.GONE
        // 보이스 코치 / PACE / 케이던스 는 달리기 모드에서만 의미 있음
        coachRow.visibility = if (mode == "running") View.VISIBLE else View.GONE
        btnPace.visibility = if (mode == "running") View.VISIBLE else View.GONE
        pacePanel.visibility = if (mode == "running") View.VISIBLE else View.GONE
        btnCadence.visibility = if (mode == "running") View.VISIBLE else View.GONE
        cadencePanel.visibility = if (mode == "running") View.VISIBLE else View.GONE
        // 섹션 라벨을 모드 맥락에 맞게
        tvTrainingSection.text = when (mode) {
            "golf" -> "골프 메트로놈"
            "meditation" -> "호흡 가이드"
            else -> "운동 도구"
        }
        // 시작 버튼 문구도 모드별 (H7)
        btnStart.text = if (isRunning) "중지" else when (mode) {
            "golf" -> "골프 시작"
            "meditation" -> "Meditation 시작"
            else -> "달리기 시작"
        }
    }

    // ────────── 골프 시각 피드백 (라이트 + 화면 깜빡) ──────────

    private fun initFlashCameraId() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        flashCameraId = try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: CameraAccessException) { null }
    }

    /** 서비스가 통지한 메트로놈 beat — 체크박스 상태에 따라 라이트/화면 flash */
    private fun onMetronomeBeat(isImpact: Boolean) {
        if (cbFlashLight.isChecked) triggerTorch(isImpact)
        if (cbFlashScreen.isChecked) flashScreen(isImpact)
    }

    private fun triggerTorch(isImpact: Boolean) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val id = flashCameraId ?: return
        try {
            cm.setTorchMode(id, true)
            val durationMs = if (isImpact) 150L else 60L
            uiHandler.postDelayed({
                try { cm.setTorchMode(id, false) } catch (_: Exception) { }
            }, durationMs)
        } catch (e: SecurityException) {
            // CAMERA 권한 누락 / 정책으로 거부 — 토글 끄고 사용자에게 안내 (1회만)
            cbFlashLight.isChecked = false
            getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("golf_flash_light_enabled", false).apply()
            showMessage("라이트 사용 권한이 거부되어 라이트 옵션을 껐습니다")
        } catch (_: Exception) {
            // 일부 기기 (전면 토치 등) — 단발 무시. 반복되면 OS 가 알려줌.
        }
    }

    private fun forceTorchOff() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val id = flashCameraId ?: return
        try { cm.setTorchMode(id, false) } catch (_: Exception) { }
    }

    private fun flashScreen(isImpact: Boolean) {
        // 일반: 흰색, 임팩트: 노란색 — 눈에 즉시 구분
        val color = if (isImpact) 0xFFFFEB3B.toInt() else 0xFFFFFFFF.toInt()
        flashOverlay.setBackgroundColor(color)
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 1f
        flashOverlay.animate()
            .alpha(0f)
            .setDuration(if (isImpact) 180 else 100)
            .start()
    }

    /**
     * 골프 화면 dim — 화면 체크 + 스윙 연습 ON 상태에서 dimDelayMs 후 전체 화면 검정.
     * dim 활성 시에도 flashOverlay 는 그 위에 있어 비트 깜빡임 유지.
     * 사용자 터치 시 dim 해제 + 다시 10초 카운트다운.
     */
    private fun scheduleDimIfNeeded() {
        cancelDimSchedule()
        if (cbFlashScreen.isChecked && btnGolfSwing.isChecked && isBound) {
            val r = Runnable { activateDim() }
            dimRunnable = r
            uiHandler.postDelayed(r, dimDelayMs)
        }
    }

    private fun cancelDimSchedule() {
        dimRunnable?.let { uiHandler.removeCallbacks(it) }
        dimRunnable = null
    }

    private fun activateDim() {
        dimActive = true
        dimOverlay.visibility = View.VISIBLE
        dimOverlay.animate().cancel()
        dimOverlay.animate().alpha(1f).setDuration(300).start()
    }

    private fun deactivateDim(animate: Boolean) {
        if (!dimActive && dimOverlay.visibility == View.GONE) return
        dimActive = false
        dimOverlay.animate().cancel()
        if (animate) {
            dimOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                if (!dimActive) dimOverlay.visibility = View.GONE
            }.start()
        } else {
            dimOverlay.alpha = 0f
            dimOverlay.visibility = View.GONE
        }
    }

    private fun onDimTouched() {
        // 검정 화면 터치 → 복귀 + 10초 재카운트
        deactivateDim(animate = true)
        scheduleDimIfNeeded()
    }

    private fun updateKeepScreenOn() {
        val golfFlashActive = ::cbFlashLight.isInitialized &&
            (cbFlashLight.isChecked || cbFlashScreen.isChecked) &&
            ::btnGolfSwing.isInitialized && btnGolfSwing.isChecked && isBound
        val focusActive = ::focusOverlay.isInitialized && focusOverlay.visibility == View.VISIBLE
        if (golfFlashActive || focusActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startPulse(view: View): ObjectAnimator {
        val anim = ObjectAnimator.ofFloat(view, "alpha", 1.0f, 0.65f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        return anim
    }

    private fun stopPulse(anim: ObjectAnimator?, view: View) {
        anim?.cancel()
        view.alpha = 1.0f
    }

    private fun flashCoachAlert(upper: Boolean) {
        // 상한 초과면 빨강, 하한 이탈이면 파랑으로 500ms 짧은 pulse
        val transparent = android.graphics.Color.TRANSPARENT
        val peak = if (upper) 0x66FF2D55.toInt() else 0x66007AFF.toInt()
        val anim = ValueAnimator.ofObject(ArgbEvaluator(), transparent, peak, transparent)
        anim.duration = 500
        anim.addUpdateListener { v -> tvHeartRate.setBackgroundColor(v.animatedValue as Int) }
        anim.start()
    }

    /**
     * 양방향 게이지:
     *   - 들숨            : 흰선 0 → 1 (중앙에서 바깥으로 확장)
     *   - 홀드 (들숨 후)  : 흰선 1 유지, 파란선 1 → 0 (바깥에서 중앙으로 수렴)
     *   - 날숨            : 흰선 1 → 0 (바깥에서 중앙으로 수렴)
     *   - 홀드 (날숨 후)  : 흰선 0 유지, 파란선 0 → 1 (중앙에서 바깥으로 확장)
     * 각 View 의 scaleX 를 pivotX = center 로 애니메이션 → 양쪽이 동시에 확장/수렴
     */
    private fun onBreathPhaseChanged(label: String, durationSec: Int) {
        tvBreathPhase.text = label
        tvFocusBreathPhase.text = label
        val prev = lastBreathPhaseLabel
        lastBreathPhaseLabel = label
        val durMs = durationSec * 1000L
        // 남은 시간 표시 동기화
        phaseStartMs = SystemClock.elapsedRealtime()
        phaseDurationSec = durationSec

        // 기존 애니메이션 정리 (메인 + 몰입 양쪽)
        breathWhiteAnim?.cancel(); breathWhiteAnim = null
        breathBlueAnim?.cancel(); breathBlueAnim = null
        focusBreathWhiteAnim?.cancel(); focusBreathWhiteAnim = null
        focusBreathBlueAnim?.cancel(); focusBreathBlueAnim = null

        // pivot 을 중앙으로 — 양쪽으로 확장되도록
        breathWhiteBar.pivotX = breathWhiteBar.width / 2f
        breathBlueBar.pivotX = breathBlueBar.width / 2f
        focusBreathWhiteBar.pivotX = focusBreathWhiteBar.width / 2f
        focusBreathBlueBar.pivotX = focusBreathBlueBar.width / 2f

        when (label) {
            "들숨" -> {
                setBarsScale(0f, 0f, focus = false)
                setBarsScale(0f, 0f, focus = true)
                breathWhiteAnim = animBar(breathWhiteBar, 0f, 1f, durMs)
                focusBreathWhiteAnim = animBar(focusBreathWhiteBar, 0f, 1f, durMs)
            }
            "날숨" -> {
                setBarsScale(1f, 0f, focus = false)
                setBarsScale(1f, 0f, focus = true)
                breathWhiteAnim = animBar(breathWhiteBar, 1f, 0f, durMs)
                focusBreathWhiteAnim = animBar(focusBreathWhiteBar, 1f, 0f, durMs)
            }
            "홀드" -> {
                if (prev == "들숨") {
                    // 들숨 후 홀드: 흰선 유지, 파란선 1 → 0
                    setBarsScale(1f, 1f, focus = false)
                    setBarsScale(1f, 1f, focus = true)
                    breathBlueAnim = animBar(breathBlueBar, 1f, 0f, durMs)
                    focusBreathBlueAnim = animBar(focusBreathBlueBar, 1f, 0f, durMs)
                } else {
                    // 날숨 후 홀드: 파란선 0 → 1
                    setBarsScale(0f, 0f, focus = false)
                    setBarsScale(0f, 0f, focus = true)
                    breathBlueAnim = animBar(breathBlueBar, 0f, 1f, durMs)
                    focusBreathBlueAnim = animBar(focusBreathBlueBar, 0f, 1f, durMs)
                }
            }
        }
    }

    private fun setBarsScale(white: Float, blue: Float, focus: Boolean) {
        if (focus) {
            focusBreathWhiteBar.scaleX = white
            focusBreathBlueBar.scaleX = blue
        } else {
            breathWhiteBar.scaleX = white
            breathBlueBar.scaleX = blue
        }
    }

    private fun animBar(view: View, from: Float, to: Float, durMs: Long): ObjectAnimator {
        return ObjectAnimator.ofFloat(view, "scaleX", from, to).apply {
            duration = durMs
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun updateMeditationUi(hr: Int, status: String) {
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val windowSec = prefs.getInt("meditation_hr_avg_sec", 10).coerceIn(1, 10)
        val avg = hrService?.getAvgHR(windowSec) ?: 0
        val curMin = hrService?.getCurrentMinuteAvg() ?: 0
        val prevMin = hrService?.getPrevMinuteAvg() ?: 0
        val hrText = if (hr > 0) "$hr" else "--"
        val avgText = if (avg > 0) "$avg" else "--"
        val curText = if (curMin > 0) "$curMin" else "--"
        val prevText = if (prevMin > 0) "$prevMin" else "--"
        tvMedHrInstant.text = hrText
        tvMedHrAvg.text = avgText
        tvMedCurrMinute.text = curText
        tvMedPrevMinute.text = prevText
        tvMedStatus.text = status
        // 몰입 모드 오버레이도 동일 값으로 동기화
        if (focusOverlay.visibility == View.VISIBLE) {
            tvFocusHrInstant.text = hrText
            tvFocusHrAvg.text = avgText
            tvFocusCurr.text = curText
            tvFocusPrev.text = prevText
        }
    }

    private fun enterFocusMode() {
        btnFocusMode.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        focusOverlay.visibility = View.VISIBLE
        // 현재 값으로 즉시 채우기
        val hr = hrService?.getCurrentHR() ?: 0
        updateMeditationUi(hr, tvMedStatus.text?.toString() ?: "")
        hrService?.getHrSamples()?.let { focusHrGraph.setSamples(it) }
        // 호흡 진행 중이면 phase 컨테이너도 노출 + 현재 진행 상태 스냅샷 복제
        // (메인 카드의 ObjectAnimator 가 진행 중이므로 다음 phase 전환 callback 부터 양쪽 동기화)
        if (breathPhaseContainer.visibility == View.VISIBLE) {
            focusBreathPhaseContainer.visibility = View.VISIBLE
            tvFocusBreathPhase.text = tvBreathPhase.text
            focusBreathWhiteBar.scaleX = breathWhiteBar.scaleX
            focusBreathBlueBar.scaleX = breathBlueBar.scaleX
        } else {
            focusBreathPhaseContainer.visibility = View.GONE
        }
        // 뒤로가기 제스처/버튼으로도 몰입 모드만 해제되도록 가로챔
        focusBackCallback.isEnabled = true
        // 서비스가 "집중 모드 N분 후 음소거" 옵션 발동 여부를 판단할 수 있게 prefs 에 기록
        getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("focus_mode_active", true).apply()
        updateKeepScreenOn()
    }

    private fun exitFocusMode() {
        focusOverlay.visibility = View.GONE
        focusBackCallback.isEnabled = false
        getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("focus_mode_active", false).apply()
        // 몰입 모드 안에서만 의미 있는 자동 종료 타이머 — 나가면 해제
        autoStopTargetMs = null
        updateAutoStopLabel()
        updateKeepScreenOn()
    }

    // 첫 실행 흐름은 OnboardingActivity (3-page carousel) 가 담당.
    // 이전에 있던 showFirstLaunchFlow / showDeviceTypeDialog / showDeviceSetupGuide
    // (AlertDialog 3개 체인) 은 Phase E 에서 OnboardingActivity 로 통합되어 제거됨.

    private fun showMeditationTip() {
        AlertDialog.Builder(this)
            .setTitle("Meditation 모드")
            .setMessage(
                "호흡 훈련에서는 설정한 시간 동안의 평균 심박수를 안내합니다. " +
                "심박수 표시 아래 슬라이더로 1~10초 사이에서 조정할 수 있습니다.\n\n" +
                "시작 후 1분마다 직전 1분 평균 심박수의 변화도 음성으로 알려드립니다."
            )
            .setPositiveButton("확인", null)
            .show()
    }

    private fun setupClubPreset(btnId: Int, displayName: String, prefKey: String, default: Int) {
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val btn = findViewById<Button>(btnId)
        fun refresh() {
            btn.text = "$displayName\n${prefs.getInt(prefKey, default)}"
        }
        refresh()
        btn.setOnClickListener {
            etMetronomeBpm.setText(prefs.getInt(prefKey, default).toString())
            applyMetronomeIfOn()
        }
        btn.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showClubEditDialog(displayName, prefKey, default, ::refresh)
            true
        }
    }

    private fun showClubEditDialog(name: String, key: String, default: Int, onSaved: () -> Unit) {
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        val current = prefs.getInt(key, default)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("$name BPM")
            .setMessage("40~200 사이 값을 입력하세요")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceIn(40, 200)
                if (v != null) {
                    prefs.edit().putInt(key, v).apply()
                    onSaved()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
        }
    }
}
