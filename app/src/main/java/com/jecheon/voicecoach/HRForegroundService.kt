package com.jecheon.voicecoach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.os.Bundle
import android.os.HandlerThread
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

class HRForegroundService : Service() {

    companion object {
        const val ACTION_STOP = "com.jecheon.voicecoach.ACTION_STOP"
        const val ACTION_EXTERNAL_HR = "com.jecheon.voicecoach.ACTION_EXTERNAL_HR"
        const val EXTRA_BPM = "bpm"
        /** Wear 메시지가 마지막으로 들어온 후 이 시간 내엔 BLE notification 무시. */
        private const val WEAR_PRIORITY_WINDOW_MS = 5000L
    }

    private val binder = LocalBinder()
    private var tts: TextToSpeech? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var currentHR = 0
    private var currentPace = ""
    private var currentPaceMin = 0
    private var currentPaceSec = 0
    private var callback: ((Int, String, String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var metronomeHandler: Handler? = null
    private var metronomeThread: HandlerThread? = null
    private var ttsInterval = 10000L
    private var metronomeRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ttsRunnable: Runnable? = null
    private var lastHRTimestampMs: Long = 0L
    private var disconnectAnnounced: Boolean = false
    private val speedBuffer = ArrayDeque<Pair<Long, Float>>()
    private val maxAccuracyMeters = 15f

    private var coachEnabled = false
    private var wasAboveUpper = false
    private var wasBelowLower = false
    private var lastUpperAlertMs = 0L
    private var lastLowerAlertMs = 0L

    private var metronomeBpmCurrent = 0
    private var scanStartedMs = 0L
    private var scanTimeoutHandler: Runnable? = null
    private var lastNonZeroHRMs = 0L
    /** "ble" | "wear" | "none" — 마지막으로 받은 HR 의 출처 */
    private var hrSource: String = "none"
    /** 마지막 Wear 메시지 수신 시각 — BLE 우선/우회 판단용 */
    private var lastWearHrMs: Long = 0L

    // Meditation — HR 샘플 버퍼 (최근 120초 유지)
    private val hrBuffer = ArrayDeque<Pair<Long, Int>>()
    private var breathRunnable: Runnable? = null
    // Meditation 1분 음성 안내 트래커 (rolling window 평균을 매 60초마다 발화)
    private var minuteAnnouncerRunnable: Runnable? = null
    private var lastAnnouncedMinuteAvg: Int = 0
    // 호흡 phase 변경 UI callback (label, durationSec)
    private var breathPhaseCallback: ((String, Int) -> Unit)? = null
    // 코치 임계값 이탈 UI callback (upper = 상한 초과)
    private var coachAlertCallback: ((Boolean) -> Unit)? = null
    // 메트로놈 beat UI callback (isImpact = 4비트 중 4번째, 골프 모드일 때만 의미)
    private var beatCallback: ((Boolean) -> Unit)? = null
    // Meditation 노이즈 (백색/브라운/커스텀) 재생기
    private val noisePlayer = NoisePlayer()
    private var currentAppMode: String = "running"
    // 세션 경과 시간 — 서비스 onCreate 시점부터
    private var sessionStartMs: Long = 0L
    // Meditation 시작 1분 평균 스냅샷 (세션 종료 시 시작-끝 비교용)
    private var firstMinuteAvgSnapshot: Int = 0

    /** Meditation 세션 요약 — 시작/끝 1분 평균 차이로 명상 효과 정량 표시 */
    data class MeditationSummary(
        val totalSec: Long,
        val firstMinuteAvg: Int,
        val lastMinuteAvg: Int
    )

    private var soundPool: SoundPool? = null
    private var metroBeepSoundId = 0
    private var metroWoodSoundId = 0
    private var metroImpactSoundId = 0
    private var alertHighSoundId = 0
    private var alertLowSoundId = 0
    @Volatile private var soundsLoaded = 0
    private val soundsNeeded = 5

    private fun getPaceAvgWindowMs(): Long {
        val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
        return prefs.getInt("pace_avg_window_sec", 3).coerceIn(1, 5) * 1000L
    }

    private var hrEnabled = true
    private var paceEnabled = false
    private var cadenceEnabled = false
    private var selectedLocale = Locale.KOREAN

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // ── 케이던스 측정 (TYPE_STEP_DETECTOR) ──
    // 60초 rolling window 의 step 이벤트 timestamp → SPM 산출
    private var sensorManager: SensorManager? = null
    private var stepDetector: Sensor? = null
    private val stepTimestamps = ArrayDeque<Long>()
    private val cadenceWindowMs = 60_000L  // 60초 윈도우 = SPM 직접 산출
    @Volatile private var currentCadence: Int = 0

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // TYPE_STEP_DETECTOR — values[0] 은 항상 1.0, 이벤트 발생 자체가 step 1번
            val now = SystemClock.elapsedRealtime()
            stepTimestamps.addLast(now)
            // 60초 이전 이벤트 정리
            while (stepTimestamps.isNotEmpty() && now - stepTimestamps.first() > cadenceWindowMs) {
                stepTimestamps.removeFirst()
            }
            // SPM = window 내 step 수. 윈도우가 아직 60초 안 찼으면 비례 환산
            val elapsedMs = if (stepTimestamps.size >= 2)
                now - stepTimestamps.first()
            else cadenceWindowMs
            currentCadence = if (elapsedMs > 5000L)  // 최소 5초 이상 데이터
                (stepTimestamps.size * 60_000.0 / elapsedMs).toInt()
            else 0
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val hrServiceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val hrCharacteristicUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    inner class LocalBinder : Binder() {
        fun getService(): HRForegroundService = this@HRForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 알림 "중지" 버튼이 보내는 액션 처리
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Wear OS 컴패니언 앱이 보낸 HR 메시지 처리
        if (intent?.action == ACTION_EXTERNAL_HR) {
            val bpm = intent.getIntExtra(EXTRA_BPM, 0)
            if (bpm > 0) onExternalHr(bpm)
            return START_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 앱 목록에서 스와이프로 날릴 때 서비스도 함께 종료
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        sessionStartMs = SystemClock.elapsedRealtime()
        startForegroundNotification()
        // Wear OS 컴패니언이 페어링되어 있으면 자동 측정 시작 명령 송신
        sendCommandToWatch("/start_hr_sender")
        acquireWakeLock()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        stepDetector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        initSoundPool()
        initTTS()
    }

    private fun startCadenceUpdates() {
        val sm = sensorManager ?: return
        val sensor = stepDetector ?: return
        sm.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopCadenceUpdates() {
        sensorManager?.unregisterListener(stepListener)
        stepTimestamps.clear()
        currentCadence = 0
    }

    // ────────── SoundPool 기반 비프 (ToneGenerator 대체) ──────────

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        sp.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) soundsLoaded++
        }
        soundPool = sp

        // cache 디렉토리에 짧은 사인파 WAV 를 한 번 생성 (이미 있으면 재사용)
        val clickFile = writeBeepWavIfNeeded("metro_click.wav", freq = 2000.0, durationSec = 0.030)
        val highFile  = writeBeepWavIfNeeded("alert_high.wav", freq = 1400.0, durationSec = 0.180)
        val lowFile   = writeBeepWavIfNeeded("alert_low.wav",  freq = 700.0,  durationSec = 0.220)

        metroBeepSoundId = sp.load(clickFile.absolutePath, 1)
        alertHighSoundId = sp.load(highFile.absolutePath, 1)
        alertLowSoundId = sp.load(lowFile.absolutePath, 1)

        val woodFile = writeWoodblockWavIfNeeded("metro_wood.wav")
        metroWoodSoundId = sp.load(woodFile.absolutePath, 1)

        // 골프 모드 임팩트 톤 — 일반 비트(2000Hz 비프 / 900Hz 우드)와 구별되는 중역 비프
        val impactFile = writeBeepWavIfNeeded("metro_impact.wav", freq = 1400.0, durationSec = 0.050)
        metroImpactSoundId = sp.load(impactFile.absolutePath, 1)
    }

    private fun writeBeepWavIfNeeded(name: String, freq: Double, durationSec: Double): File {
        val file = File(cacheDir, name)
        if (file.exists() && file.length() > 44) return file
        val sampleRate = 44100
        val numSamples = (durationSec * sampleRate).toInt()
        val fadeSamples = (sampleRate * 0.005).toInt().coerceAtLeast(1) // 5ms fade
        val pcmBytes = ByteArray(numSamples * 2)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val env = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > numSamples - fadeSamples -> (numSamples - i).toDouble() / fadeSamples
                else -> 1.0
            }
            val v = (sin(2 * PI * freq * t) * env * 0.85 * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.putShort(v.toShort())
        }
        writeWavFile(file, pcmBytes, sampleRate)
        return file
    }

    private fun writeWavFile(file: File, pcm: ByteArray, sampleRate: Int) {
        val totalSize = 36 + pcm.size
        FileOutputStream(file).use { os ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII))
            h.putInt(totalSize)
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16)                  // fmt chunk size (PCM)
            h.putShort(1)                 // audio format = PCM
            h.putShort(1)                 // channels = mono
            h.putInt(sampleRate)
            h.putInt(sampleRate * 2)      // byte rate
            h.putShort(2)                 // block align
            h.putShort(16)                // bits per sample
            h.put("data".toByteArray(Charsets.US_ASCII))
            h.putInt(pcm.size)
            os.write(h.array())
            os.write(pcm)
        }
    }

    private fun writeWoodblockWavIfNeeded(name: String): File {
        val file = File(cacheDir, name)
        if (file.exists() && file.length() > 44) return file
        val sampleRate = 44100
        val durationSec = 0.08                 // 80ms
        val numSamples = (durationSec * sampleRate).toInt()
        val pcmBytes = ByteArray(numSamples * 2)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        // 비조화 모드 3개 — 목재 타악기 스펙트럼 근사 (1 : 1.95 : 2.93)
        val partials = listOf(
            Triple(900.0, 1.00, 35.0),         // fundamental
            Triple(1760.0, 0.55, 50.0),        // 비조화 2nd
            Triple(2640.0, 0.30, 75.0)         // bright click (빠른 감쇠)
        )
        val rand = java.util.Random(42)        // 재현 가능한 attack 노이즈 (seed 고정)
        val attackSec = 0.004                  // 4ms click burst

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var s = 0.0
            for ((f, a, d) in partials) {
                s += a * sin(2 * PI * f * t) * kotlin.math.exp(-d * t)
            }
            if (t < attackSec) {
                val env = 1.0 - t / attackSec
                s += (rand.nextDouble() * 2 - 1) * 0.25 * env
            }
            val v = (s * 0.6 * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.putShort(v.toShort())
        }
        writeWavFile(file, pcmBytes, sampleRate)
        return file
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceCoach::HRServiceWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun setCallback(cb: (Int, String, String) -> Unit) {
        callback = cb
    }

    /**
     * Wear OS 컴패니언 앱이 보낸 외부 HR. BLE [onCharacteristicChanged] 와 동일한 후처리:
     * 0 필터, 버퍼 갱신, Meditation 1분 집계, 알림/콜백, 코치 임계값.
     */
    private fun onExternalHr(bpm: Int) {
        val nowMs = SystemClock.elapsedRealtime()
        // HR=0 spurious 필터 (BLE 와 같은 정책)
        if (bpm == 0 && lastNonZeroHRMs > 0 && (nowMs - lastNonZeroHRMs) < 5000L) return
        if (bpm > 0) {
            lastNonZeroHRMs = nowMs
            hrBuffer.addLast(nowMs to bpm)
            while (hrBuffer.isNotEmpty() && nowMs - hrBuffer.first().first > 120_000L) {
                hrBuffer.removeFirst()
            }
            if (currentAppMode == "meditation" && firstMinuteAvgSnapshot == 0) {
                val sessionElapsed = if (sessionStartMs > 0) nowMs - sessionStartMs else 0L
                if (sessionElapsed >= 60_000L) {
                    firstMinuteAvgSnapshot = getCurrentMinuteAvg()
                }
            }
        }
        currentHR = bpm
        lastHRTimestampMs = nowMs
        disconnectAnnounced = false
        hrSource = "wear"
        lastWearHrMs = nowMs
        callback?.invoke(bpm, currentPace, "심박수 수신 중 (워치)")
        updateNotification()
        if (coachEnabled && bpm > 0 && currentAppMode == "running") checkThresholds(bpm)
    }

    /** 호흡 phase 가 바뀔 때마다 UI 에 (label, durationSec) 를 통지. */
    fun setBreathPhaseCallback(cb: (String, Int) -> Unit) {
        breathPhaseCallback = cb
    }

    /** 코치 임계값 이탈 시 UI 가 시각 피드백을 줄 수 있도록 통지 (upper=true 이면 상한 초과). */
    fun setCoachAlertCallback(cb: (Boolean) -> Unit) {
        coachAlertCallback = cb
    }

    /** 메트로놈 beat 통지 — isImpact=true 이면 골프 3:1 패턴의 4번째(임팩트) 비트. */
    fun setBeatCallback(cb: (Boolean) -> Unit) {
        beatCallback = cb
    }

    /**
     * 명상용 노이즈 재생/정지.
     * @param type "white" | "brown" | "custom"
     * @param customCutoffHz "custom" 일 때만 사용 (50~22000Hz). 다른 type 은 무시.
     */
    fun startNoise(type: String, customCutoffHz: Float = 5000f, volume: Float = 0.5f) {
        val cutoff = when (type) {
            "white" -> 22_000f       // 거의 풀 화이트
            "brown" -> 250f          // 강한 저역통과 → 브라운 비슷
            "custom" -> customCutoffHz.coerceIn(50f, 22_000f)
            else -> 22_000f
        }
        noisePlayer.setCutoff(cutoff)
        noisePlayer.setVolume(volume)
        noisePlayer.start()
    }

    /** 노이즈 컷오프만 실시간 변경 (재생 중 슬라이더 이동). */
    fun updateNoiseCutoff(cutoffHz: Float) {
        noisePlayer.setCutoff(cutoffHz)
    }

    /** 노이즈 음량 실시간 변경. 0~1. */
    fun updateNoiseVolume(v: Float) {
        noisePlayer.setVolume(v)
    }

    fun stopNoise() {
        noisePlayer.stop()
    }

    fun isNoisePlaying(): Boolean = noisePlayer.isPlaying()

    fun setOptions(
        hr: Boolean,
        pace: Boolean,
        intervalSeconds: Int,
        locale: Locale = Locale.KOREAN,
        coach: Boolean = false,
        cadence: Boolean = false
    ) {
        val newInterval = intervalSeconds * 1000L
        val newMode = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
            .getString("app_mode", "running") ?: "running"
        // 모든 인자 + mode 가 기존과 동일하면 no-op — TTS 루프/위치 업데이트 리셋 방지
        if (hr == hrEnabled && pace == paceEnabled && cadence == cadenceEnabled
            && newInterval == ttsInterval
            && locale == selectedLocale && coach == coachEnabled
            && newMode == currentAppMode) return
        // 모드 전환 부수 효과
        if (newMode != currentAppMode) {
            resetMinuteAggregation()
            if (newMode == "meditation") startMinuteAnnouncer()
            else if (currentAppMode == "meditation") stopMinuteAnnouncer()
        }
        hrEnabled = hr
        paceEnabled = pace
        cadenceEnabled = cadence
        ttsInterval = newInterval
        selectedLocale = locale
        tts?.language = locale
        coachEnabled = coach
        currentAppMode = newMode
        wasAboveUpper = false
        wasBelowLower = false
        lastUpperAlertMs = 0L
        lastLowerAlertMs = 0L
        disconnectAnnounced = false
        // 주의: 과거에는 여기서 handler.removeCallbacksAndMessages(null) 로 모든 큐를 비웠으나
        // (a) 직전에 startMinuteAnnouncer() 가 예약한 60초 안내 Runnable 도 같이 지워지고
        // (b) BLE 재연결 / scan timeout / breath 가이드 등 별개 lifecycle 의 작업이 무차별 취소됨.
        // 각 Runnable 은 자기 start/stop 함수가 책임지므로 여기서는 일괄 제거하지 않음.
        if (paceEnabled) startLocationUpdates()
        if (cadenceEnabled) startCadenceUpdates() else stopCadenceUpdates()
        // Meditation 모드는 항상 TTS 루프 필요 (평균 HR 안내용)
        if (newMode == "meditation" || hrEnabled || paceEnabled || cadenceEnabled) startTTSLoop()
        else stopTTSLoop()
    }

    /** 외부 (UI / Wear listener) 가 현재 cadence 조회. */
    fun getCurrentCadence(): Int = currentCadence

    private fun checkThresholds(hr: Int) {
        val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
        val upperEnabled = prefs.getBoolean("coach_upper_enabled", false)
        val upperBpm = prefs.getInt("coach_upper_bpm", 180)
        val lowerEnabled = prefs.getBoolean("coach_lower_enabled", false)
        val lowerBpm = prefs.getInt("coach_lower_bpm", 120)
        val realertMs = prefs.getInt("coach_realert_interval_sec", 30) * 1000L
        val now = SystemClock.elapsedRealtime()

        if (upperEnabled) {
            val isAbove = hr > upperBpm
            if (isAbove) {
                val justEntered = !wasAboveUpper
                val timeSinceAlert = now - lastUpperAlertMs
                if (justEntered || timeSinceAlert >= realertMs) {
                    playCoachAlert(upper = true, threshold = upperBpm)
                    lastUpperAlertMs = now
                }
            }
            wasAboveUpper = isAbove
        } else {
            wasAboveUpper = false
        }

        if (lowerEnabled) {
            val isBelow = hr < lowerBpm
            if (isBelow) {
                val justEntered = !wasBelowLower
                val timeSinceAlert = now - lastLowerAlertMs
                if (justEntered || timeSinceAlert >= realertMs) {
                    playCoachAlert(upper = false, threshold = lowerBpm)
                    lastLowerAlertMs = now
                }
            }
            wasBelowLower = isBelow
        } else {
            wasBelowLower = false
        }
    }

    private fun playCoachAlert(upper: Boolean, threshold: Int) {
        // 0) UI 시각 피드백 (flash)
        coachAlertCallback?.invoke(upper)

        // 1) 부드러운 비프음 — SoundPool 로 재생 (상한: 고음, 하한: 저음)
        val sp = soundPool
        if (sp != null) {
            val id = if (upper) alertHighSoundId else alertLowSoundId
            sp.play(id, 1f, 1f, 1, 0, 1f)
        }

        // 2) 비프 뒤에 TTS 안내 — 비프와 겹치지 않게 약간 지연 후 발화
        val msg = if (selectedLocale.language == "en") {
            if (upper) "Heart rate above $threshold" else "Heart rate below $threshold"
        } else {
            if (upper) "심박수 ${threshold}보다 높음" else "심박수 ${threshold}보다 낮음"
        }
        handler.postDelayed({
            tts?.speak(msg, TextToSpeech.QUEUE_ADD, ttsParams(), "coach")
        }, 300)
    }

    fun getCurrentHR(): Int = currentHR
    fun getCurrentPace(): String = currentPace
    fun isMetronomeRunning(): Boolean = metronomeRunnable != null

    fun refreshMetronomeVolume() {
        // SoundPool 기반에선 매 tick 마다 prefs 볼륨을 읽어 play() 에 전달하므로
        // 별도 재시작 없이 다음 비트부터 자동 반영됨. 호환성 위해 남겨둠.
    }

    fun startMetronome(bpm: Int) {
        if (bpm <= 0 || bpm > 400) return
        // 이미 같은 BPM 으로 돌고 있으면 no-op — 박자 끊김 방지
        if (metronomeRunnable != null && bpm == metronomeBpmCurrent) return
        stopMetronome()
        metronomeBpmCurrent = bpm

        val sp = soundPool ?: return

        val thread = HandlerThread("MetronomeThread").apply { start() }
        metronomeThread = thread
        val h = Handler(thread.looper)
        metronomeHandler = h

        val intervalMs = (60000.0 / bpm).toLong()
        val startTimeNs = System.nanoTime()
        var beatCount = 0L
        val r = object : Runnable {
            override fun run() {
                val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
                val vol = prefs.getInt("metronome_volume", 100).coerceIn(0, 100) / 100f
                val soundType = prefs.getString("metronome_sound", "beep")
                val mode = prefs.getString("app_mode", "running")
                // 골프 모드: 4비트 중 4번째(인덱스 3)는 임팩트 톤 — 3:1 템포 패턴
                val isImpactBeat = (mode == "golf") && (beatCount % 4 == 3L)
                val id = when {
                    isImpactBeat -> metroImpactSoundId
                    soundType == "wood" -> metroWoodSoundId
                    else -> metroBeepSoundId
                }
                // 임팩트 비트는 살짝 볼륨 업 (강조)
                val playVol = if (isImpactBeat) (vol * 1.2f).coerceAtMost(1f) else vol
                sp.play(id, playVol, playVol, 1, 0, 1f)
                // UI 통지 — 시각 피드백 (골프 모드 라이트/화면 flash). main thread 로 post.
                val cb = beatCallback
                if (cb != null) handler.post { cb.invoke(isImpactBeat) }
                beatCount++
                val nextTargetMs = beatCount * intervalMs
                val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000
                val delay = (nextTargetMs - elapsedMs).coerceAtLeast(0L)
                h.postDelayed(this, delay)
            }
        }
        metronomeRunnable = r
        h.post(r)
    }

    fun stopMetronome() {
        metronomeRunnable?.let { r -> metronomeHandler?.removeCallbacks(r) }
        metronomeRunnable = null
        metronomeThread?.quitSafely()
        metronomeThread = null
        metronomeHandler = null
        metronomeBpmCurrent = 0
    }

    // ────────── Meditation ──────────

    /**
     * 최근 [windowSec] 초 구간의 심박수 산술평균. 샘플이 없으면 0.
     */
    fun getAvgHR(windowSec: Int): Int {
        val now = SystemClock.elapsedRealtime()
        val windowMs = windowSec * 1000L
        val samples = hrBuffer.filter { now - it.first <= windowMs }.map { it.second }
        return if (samples.isEmpty()) 0 else samples.average().toInt()
    }

    /**
     * 현재 시각 기준 [-59s, 0s] 구간의 산술평균.
     * 시작 직후엔 샘플이 적어서 평균도 점진적으로 안정화되며, 60초 이후 정상값.
     */
    fun getCurrentMinuteAvg(): Int {
        val now = SystemClock.elapsedRealtime()
        val samples = hrBuffer.filter { now - it.first <= 60_000L }.map { it.second }
        return if (samples.isEmpty()) 0 else samples.average().toInt()
    }

    /**
     * 현재 시각 기준 [-119s, -60s] 구간의 산술평균.
     * 세션이 60초 미만이면 샘플이 없어 0을 반환 → UI 에서 "--" 로 표시.
     */
    /** 서비스 시작 이후 경과 초. */
    fun getSessionElapsedSec(): Long {
        return if (sessionStartMs == 0L) 0L else (SystemClock.elapsedRealtime() - sessionStartMs) / 1000
    }

    /** 최근 120초 HR 샘플 스냅샷 — Meditation 그래프 그리기용. (elapsedRealtimeMs, bpm). */
    fun getHrSamples(): List<Pair<Long, Int>> = hrBuffer.toList()

    /**
     * Wear OS 컴패니언 앱 (페어링된 워치) 에 명령 전송.
     * 메인 thread 에서 Tasks.await 호출하면 ANR 위험 → 단발 worker thread.
     * 워치 미페어링 / 미설치 시 조용히 실패.
     */
    private fun sendCommandToWatch(path: String) {
        Thread {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.id, path, ByteArray(0))
                }
            } catch (_: Exception) {
                // 워치 없음/꺼짐/페어링 끊김 — 무시
            }
        }.start()
    }

    /**
     * Meditation 모드에서 사용자가 활성화한 "집중모드 N분 후 음소거" 옵션이 발동 중인지.
     * 조건: Meditation 모드 + 옵션 ON + 집중 모드 진입 상태 + 세션 경과 시간 ≥ N분.
     * 발동 시 호흡 비프/TTS, HR 음성 안내, 1분 평균 안내 모두 무음 처리.
     */
    private fun isMeditationFocusMuted(): Boolean {
        if (currentAppMode != "meditation") return false
        val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("meditation_focus_mute_enabled", false)) return false
        if (!prefs.getBoolean("focus_mode_active", false)) return false
        val muteAfterMin = prefs.getInt("meditation_focus_mute_min", 15).coerceAtLeast(1)
        return getSessionElapsedSec() >= muteAfterMin * 60L
    }

    fun getPrevMinuteAvg(): Int {
        val now = SystemClock.elapsedRealtime()
        val samples = hrBuffer
            .filter { (ts, _) -> (now - ts) in 60_001L..120_000L }
            .map { it.second }
        return if (samples.isEmpty()) 0 else samples.average().toInt()
    }

    /** Meditation 모드 진입/이탈 시 HR 버퍼 + 음성 안내 baseline 초기화 */
    private fun resetMinuteAggregation() {
        hrBuffer.clear()
        lastAnnouncedMinuteAvg = 0
        firstMinuteAvgSnapshot = 0
    }

    /**
     * Meditation 세션 요약 — 종료 직전에 호출.
     * 60초 미만 세션은 의미 없어 null. 시작/끝 평균 모두 0이면 null.
     */
    fun getMeditationSummary(): MeditationSummary? {
        if (currentAppMode != "meditation") return null
        val totalSec = getSessionElapsedSec()
        if (totalSec < 60) return null
        val first = firstMinuteAvgSnapshot
        // 끝 1분 평균 = 가장 최근 완료된 prev (직전 1분), 없으면 현재 누적
        val last = if (getPrevMinuteAvg() > 0) getPrevMinuteAvg() else getCurrentMinuteAvg()
        if (first == 0 && last == 0) return null
        return MeditationSummary(totalSec, first, last)
    }

    private fun startMinuteAnnouncer() {
        stopMinuteAnnouncer()
        val r = object : Runnable {
            override fun run() {
                val avg = getCurrentMinuteAvg()
                if (avg > 0) announceMinuteUpdate(avg)
                handler.postDelayed(this, 60_000L)
            }
        }
        minuteAnnouncerRunnable = r
        handler.postDelayed(r, 60_000L)
    }

    private fun stopMinuteAnnouncer() {
        minuteAnnouncerRunnable?.let { handler.removeCallbacks(it) }
        minuteAnnouncerRunnable = null
    }

    /**
     * 1분 경계에 도달했을 때 간결한 음성 안내.
     * "65" (첫 1분) / "65, 3 감소" / "65, 2 증가" / "65 유지"
     */
    private fun announceMinuteUpdate(finishedAvg: Int) {
        if (finishedAvg <= 0) return
        if (isMeditationFocusMuted()) {
            // 음소거 중에도 baseline 만 갱신 (음소거 해제 후 변화량 비교를 위해)
            lastAnnouncedMinuteAvg = finishedAvg
            return
        }
        val isEn = selectedLocale.language == "en"
        val msg = if (lastAnnouncedMinuteAvg == 0) {
            "$finishedAvg"
        } else {
            val delta = finishedAvg - lastAnnouncedMinuteAvg
            val absDelta = kotlin.math.abs(delta)
            when {
                delta > 0 && isEn -> "$finishedAvg, up $absDelta"
                delta < 0 && isEn -> "$finishedAvg, down $absDelta"
                delta == 0 && isEn -> "$finishedAvg"
                delta > 0 -> "$finishedAvg, $absDelta 증가"
                delta < 0 -> "$finishedAvg, $absDelta 감소"
                else -> "$finishedAvg"
            }
        }
        tts?.speak(msg, TextToSpeech.QUEUE_ADD, ttsParams(), "minute_avg")
        lastAnnouncedMinuteAvg = finishedAvg
    }

    /**
     * 호흡 가이드 시작. 단일 1초 Runnable 이 phase 전환과 tick 재생을 모두 담당.
     *
     * Tick 재생 규칙:
     *   - 초 카운트 OFF (prefs `breath_metro_enabled` = false)
     *       · 첫 사이클: phase 시작 시 TTS 로 "들숨/홀드/날숨" 안내, 비프 없음
     *       · 그 뒤 사이클: phase 시작 시만 해당 phase 톤 1회 재생
     *   - 초 카운트 ON
     *       · phase 내부 매초 tick, 모두 동일한 phase 대표 톤 (들숨 고음 / 홀드 클릭 / 날숨 저음)
     *       · 첫 사이클도 phase 시작엔 TTS 동반
     *
     * Phase 톤:
     *   들숨 → alertHighSoundId (고음)
     *   날숨 → alertLowSoundId (저음)
     *   홀드 → metroBeepSoundId (짧은 클릭)
     *
     * (심박수 음성 안내는 센서 설정의 HR 토글이 담당, 여기서는 관여하지 않음)
     */
    fun startBreathwork(presetId: String) {
        stopBreathwork()
        val customPrefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
        val phases: List<Pair<String, Int>> = when (presetId) {
            "box" -> listOf("들숨" to 4, "홀드" to 4, "날숨" to 4, "홀드" to 4)
            "478" -> listOf("들숨" to 4, "홀드" to 7, "날숨" to 8)
            "coherent" -> listOf("들숨" to 5, "날숨" to 5)
            "long_exhale" -> listOf("들숨" to 4, "날숨" to 8)
            "custom" -> {
                // 사용자가 정의한 4-phase 사이클. sec=0 phase 는 시퀀스에서 제외.
                val inhale = customPrefs.getInt("breath_custom_inhale_sec", 4).coerceIn(1, 30)
                val hold1 = customPrefs.getInt("breath_custom_hold1_sec", 4).coerceIn(0, 30)
                val exhale = customPrefs.getInt("breath_custom_exhale_sec", 4).coerceIn(1, 30)
                val hold2 = customPrefs.getInt("breath_custom_hold2_sec", 0).coerceIn(0, 30)
                listOf("들숨" to inhale, "홀드" to hold1, "날숨" to exhale, "홀드" to hold2)
                    .filter { it.second > 0 }
            }
            else -> return
        }
        if (phases.isEmpty()) return
        val englishMap = mapOf("들숨" to "Inhale", "홀드" to "Hold", "날숨" to "Exhale")
        val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)

        val r = object : Runnable {
            var phaseIdx = 0
            var secInPhase = 0
            var cycleCount = 0
            override fun run() {
                val (label, sec) = phases[phaseIdx]
                val isPhaseStart = (secInPhase == 0)
                val isFirstCycle = (cycleCount == 0)
                val metroOn = prefs.getBoolean("breath_metro_enabled", false)

                if (isPhaseStart) {
                    // Phase 전환 — UI 통지 (음소거 와 무관, 시각은 항상 살아있음)
                    breathPhaseCallback?.invoke(label, sec)
                    if (isFirstCycle && !isMeditationFocusMuted()) {
                        // 첫 사이클은 phase 이름 TTS 안내
                        val spoken = if (selectedLocale.language == "en") englishMap[label] ?: label else label
                        tts?.speak(spoken, TextToSpeech.QUEUE_ADD, ttsParams(), "breath_${phaseIdx}_${cycleCount}")
                    }
                }

                // Tick 결정:
                //   - 초 카운트 ON: 매초 재생
                //   - 초 카운트 OFF + 첫 사이클: 재생 안 함 (TTS 가 대신)
                //   - 초 카운트 OFF + 두 번째 사이클 이후: phase 시작 시만 재생
                val shouldTick = when {
                    metroOn -> true
                    isPhaseStart && !isFirstCycle -> true
                    else -> false
                }
                if (shouldTick) playPhaseTick(label)

                // 진행
                secInPhase++
                if (secInPhase >= sec) {
                    secInPhase = 0
                    phaseIdx = (phaseIdx + 1) % phases.size
                    if (phaseIdx == 0) cycleCount++
                }
                handler.postDelayed(this, 1000L)
            }
        }
        breathRunnable = r
        handler.post(r)
    }

    fun stopBreathwork() {
        breathRunnable?.let { handler.removeCallbacks(it) }
        breathRunnable = null
    }

    private fun playPhaseTick(label: String) {
        if (isMeditationFocusMuted()) return
        val sp = soundPool ?: return
        val soundId = when (label) {
            "들숨" -> alertHighSoundId
            "날숨" -> alertLowSoundId
            else -> metroBeepSoundId  // "홀드"
        }
        sp.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = selectedLocale
                // 음악 위로 명확하게 들리도록 ASSISTANCE_NAVIGATION_GUIDANCE attribute.
                // 네비/운동 안내용 stream 으로 처리되어 일반 미디어보다 우선 재생됨.
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(attrs)
                startBLEScan()
            }
        }
    }

    // ── TTS audio focus — 세션 전체 lifecycle 동안 유지 ──
    // 발화 단위로 focus 를 잡았다 풀면 매 안내마다 음악이 ducking → unducking → ducking 깜빡임.
    // 세션 시작 시 한 번 잡고 종료 시 한 번 푸는 방식 — 음악이 안내 동안 한결같이 ducking 됨.
    //
    // 정책: 시스템 STREAM_MUSIC 의 절대 볼륨은 절대 건드리지 않음.
    // (사용자가 Spotify / 유튜브 듣는 중에 앱이 폰 볼륨 자체를 바꾸면 매우 침습적)
    // → AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK 만으로 다른 미디어 앱에 "잠시 죽이세요" 신호.
    //   실제 ducking 비율은 OS / 미디어 앱이 결정. 우리는 stream volume 안 바꿈.
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var focusHeld: Boolean = false

    private fun ensureAudioManager(): AudioManager? {
        if (audioManager == null) audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        return audioManager
    }

    /**
     * TTS 루프 lifecycle 에 맞춰 audio focus 보유 상태 동기화.
     * 세션 시작 시 잡고 (다른 미디어 ducking), 종료 시 풀음. 발화 단위로 토글 안 함.
     *
     * 호출 시점: TTS 루프 시작 / 종료 / 옵션 변경 / 음량 슬라이더 변경.
     */
    fun syncAudioFocusState() {
        val am = ensureAudioManager() ?: return
        // TTS 루프가 돌고 있으면 focus 유지, 아니면 해제.
        // (TTS 음량 슬라이더 자체는 focus 보유 여부와 독립 — 항상 ducking 켜는 정책)
        val shouldHold = ttsRunnable != null
        if (shouldHold && !focusHeld) acquireFocus(am)
        else if (!shouldHold && focusHeld) releaseFocus(am)
    }

    private fun acquireFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        focusHeld = true
    }

    private fun releaseFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        focusHeld = false
    }

    /** 외부 (Settings 화면) 가 슬라이더 변경 시 호출 — focus 상태 재동기화. */
    fun refreshTtsVolume() {
        syncAudioFocusState()
    }

    /** 서비스 종료 시 호출 — focus 가 잡혀 있으면 풀음. */
    private fun abandonTtsAudioFocus() {
        val am = ensureAudioManager() ?: return
        if (focusHeld) releaseFocus(am)
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            callback?.invoke(currentHR, currentPace, "위치 권한 필요")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            // 3번: GPS 정확도 필터 — accuracy > 15m 인 샘플은 무시
            if (location.hasAccuracy() && location.accuracy > maxAccuracyMeters) return

            val speedMs = location.speed
            val now = SystemClock.elapsedRealtime()

            // 이동 평균 버퍼 관리 (윈도우 크기는 설정에서 읽음)
            val windowMs = getPaceAvgWindowMs()
            speedBuffer.addLast(now to speedMs)
            while (speedBuffer.isNotEmpty() && now - speedBuffer.first().first > windowMs) {
                speedBuffer.removeFirst()
            }

            val avgSpeedMs = if (speedBuffer.isNotEmpty()) {
                speedBuffer.map { it.second }.average().toFloat()
            } else 0f

            val minSpeedMs = 0.83f

            if (avgSpeedMs > minSpeedMs) {
                val paceSecondsPerKm = 1000.0 / avgSpeedMs
                if (paceSecondsPerKm < 1200) {
                    currentPaceMin = (paceSecondsPerKm / 60).toInt()
                    currentPaceSec = (paceSecondsPerKm % 60).toInt()
                    currentPace = "${currentPaceMin}'${currentPaceSec.toString().padStart(2, '0')}\""
                    callback?.invoke(currentHR, currentPace, "심박수 수신 중")
                    updateNotification()
                }
            } else {
                currentPace = ""
                currentPaceMin = 0
                currentPaceSec = 0
            }
        }
    }

    private fun startBLEScan() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            callback?.invoke(0, currentPace, "블루투스 꺼짐 — 잠시 후 재시도")
            handler.postDelayed({ startBLEScan() }, 5000)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            callback?.invoke(0, currentPace, "블루투스 일시 불가 — 잠시 후 재시도")
            handler.postDelayed({ startBLEScan() }, 5000)
            return
        }

        callback?.invoke(0, currentPace, "워치 검색 중")
        scanStartedMs = SystemClock.elapsedRealtime()
        scanTimeoutHandler?.let { handler.removeCallbacks(it) }
        scanTimeoutHandler = Runnable {
            // BLE GATT 연결이 안 됐어도 Wear OS 컴패니언이 HR 을 보내고 있으면
            // 사용자 입장에선 "워치 정상 동작 중" — 에러 메시지로 덮어쓰지 않음.
            val nowMs = SystemClock.elapsedRealtime()
            val wearActive = lastWearHrMs > 0 && (nowMs - lastWearHrMs) < WEAR_PRIORITY_WINDOW_MS
            if (bluetoothGatt == null && !wearActive) {
                callback?.invoke(0, currentPace, "워치를 찾지 못했습니다")
            }
        }
        handler.postDelayed(scanTimeoutHandler!!, 30000L)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(hrServiceUuid))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            callback?.invoke(0, currentPace, "블루투스 권한 필요")
        } catch (e: IllegalStateException) {
            callback?.invoke(0, currentPace, "스캔 실패 — 잠시 후 재시도")
            handler.postDelayed({ startBLEScan() }, 5000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            scanTimeoutHandler?.let { handler.removeCallbacks(it) }
            callback?.invoke(0, currentPace, "워치 발견 — 연결 중")
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            try {
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(this)
            } catch (e: SecurityException) { }
            try {
                device.connectGatt(this@HRForegroundService, false, gattCallback)
            } catch (e: SecurityException) {
                callback?.invoke(0, currentPace, "블루투스 권한 필요")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "스캔 이미 진행 중"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "앱 등록 실패"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "기기 BLE 미지원"
                SCAN_FAILED_INTERNAL_ERROR -> "내부 오류"
                else -> "오류 코드 $errorCode"
            }
            callback?.invoke(0, currentPace, "스캔 오류 — 잠시 후 재시도")
            handler.postDelayed({ startBLEScan() }, 5000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) { }
                callback?.invoke(0, currentPace, "연결됨 — 서비스 탐색 중")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                currentHR = 0
                callback?.invoke(0, currentPace, "연결 끊김 — 재연결 중")
                try {
                    gatt.close()
                } catch (e: SecurityException) { }
                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }
                handler.postDelayed({ startBLEScan() }, 3000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hrService = gatt.getService(hrServiceUuid) ?: return
            val hrChar = hrService.getCharacteristic(hrCharacteristicUuid) ?: return
            try {
                gatt.setCharacteristicNotification(hrChar, true)
                val descriptor = hrChar.getDescriptor(cccdUuid)
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } catch (e: SecurityException) { }
            callback?.invoke(0, currentPace, "심박수 수신 중")
            startTTSLoop()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val flag = characteristic.properties
            val hr = if (flag and 0x01 != 0) {
                characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 1) ?: 0
            } else {
                characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1) ?: 0
            }
            val nowMs = SystemClock.elapsedRealtime()
            // Wear 우선 정책: 최근 [WEAR_PRIORITY_WINDOW_MS] 내 Wear 메시지가 있었으면 BLE 무시
            if (lastWearHrMs > 0 && (nowMs - lastWearHrMs) < WEAR_PRIORITY_WINDOW_MS) {
                return
            }
            // HR 0 값이 가끔 섞여올 때 — 최근 5초 내 유효값이 있었으면 무시
            if (hr == 0 && lastNonZeroHRMs > 0 && (nowMs - lastNonZeroHRMs) < 5000L) {
                return
            }
            if (hr > 0) {
                lastNonZeroHRMs = nowMs
                // HR 샘플 버퍼 — 최근 120초 유지 (Meditation 1분 평균 / 이전 1분 평균 rolling 계산용)
                hrBuffer.addLast(nowMs to hr)
                while (hrBuffer.isNotEmpty() && nowMs - hrBuffer.first().first > 120_000L) {
                    hrBuffer.removeFirst()
                }
                // Meditation: 세션 첫 60초 경계에서 시작 1분 평균 스냅샷 저장
                if (currentAppMode == "meditation" && firstMinuteAvgSnapshot == 0) {
                    val sessionElapsed = if (sessionStartMs > 0) nowMs - sessionStartMs else 0L
                    if (sessionElapsed >= 60_000L) {
                        firstMinuteAvgSnapshot = getCurrentMinuteAvg()
                    }
                }
            }
            currentHR = hr
            lastHRTimestampMs = nowMs
            disconnectAnnounced = false
            hrSource = "ble"
            callback?.invoke(hr, currentPace, "심박수 수신 중")
            updateNotification()
            // 보이스 코치는 달리기 모드에서만 동작 (골프/Meditation 에선 UI 에서 숨겨져 있음)
            if (coachEnabled && hr > 0 && currentAppMode == "running") checkThresholds(hr)
        }
    }

    private fun ttsParams(): Bundle {
        val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
        val volPct = prefs.getInt("tts_volume", 100).coerceIn(0, 100)
        // KEY_PARAM_VOLUME 은 0.0~1.0 범위. 시스템 미디어 볼륨은 건드리지 않음 (사용자 침습 방지).
        // 음악 위로 명료하게 들리는 효과는 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK 으로 제공.
        return Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volPct / 100f)
        }
    }

    private fun startTTSLoop() {
        stopTTSLoop()
        // 세션 시작 — focus 잡고 음악 ducking 활성화 (볼륨 > 100% 일 때만).
        // 발화 단위가 아니라 루프 lifecycle 전체 동안 유지됨.
        syncAudioFocusState()
        val r = object : Runnable {
            override fun run() {
                // 코치 경고 / 호흡 가이드 등 다른 TTS 발화가 재생 중이면 이번 tick 건너뜀
                if (tts?.isSpeaking == true) {
                    handler.postDelayed(this, ttsInterval)
                    return
                }
                val params = ttsParams()
                val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
                val mode = prefs.getString("app_mode", "running")

                // Meditation 모드 — HR 토글이 ON 일 때만 윈도우 평균 심박수를 숫자로 발화
                if (mode == "meditation") {
                    if (hrEnabled && !isMeditationFocusMuted()) {
                        val windowSec = prefs.getInt("meditation_hr_avg_sec", 10).coerceIn(1, 10)
                        val avg = getAvgHR(windowSec)
                        if (avg > 0) {
                            tts?.speak("$avg", TextToSpeech.QUEUE_FLUSH, params, "hr_avg")
                        }
                    }
                    handler.postDelayed(this, ttsInterval)
                    return
                }

                // 달리기/골프 모드 — 기존 HR/PACE 안내
                val now = SystemClock.elapsedRealtime()
                val hadHRBefore = lastHRTimestampMs > 0L
                val hrStale = !hadHRBefore || (now - lastHRTimestampMs) > 5000L
                val hrValid = currentHR > 0 && !hrStale

                var spokeSomething = false
                val isEn = selectedLocale.language == "en"
                // 라벨 토글 — 사용자 설정에 따라 "165" vs "심박수 165" 형태 결정
                val labelHr = prefs.getBoolean("tts_label_hr", false)
                val labelPace = prefs.getBoolean("tts_label_pace", false)
                val labelCadence = prefs.getBoolean("tts_label_cadence", false)

                if (hrEnabled) {
                    if (hrValid) {
                        val text = if (labelHr) {
                            if (isEn) "Heart rate $currentHR" else "심박수 $currentHR"
                        } else "$currentHR"
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "hr")
                        spokeSomething = true
                    } else if (hadHRBefore && !disconnectAnnounced) {
                        val msg = if (isEn) "Heart rate signal lost" else "연결이 끊어졌습니다"
                        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, params, "disconnect")
                        disconnectAnnounced = true
                        spokeSomething = true
                    }
                }

                if (paceEnabled && currentPaceMin > 0) {
                    val raw = if (isEn) "$currentPaceMin minutes $currentPaceSec seconds"
                              else "${currentPaceMin}분 ${currentPaceSec}초"
                    val paceText = if (labelPace) {
                        if (isEn) "Pace $raw" else "페이스 $raw"
                    } else raw
                    if (spokeSomething) {
                        tts?.playSilentUtterance(500, TextToSpeech.QUEUE_ADD, null)
                        tts?.speak(paceText, TextToSpeech.QUEUE_ADD, params, "pace")
                    } else {
                        tts?.speak(paceText, TextToSpeech.QUEUE_FLUSH, params, "pace")
                    }
                    spokeSomething = true
                }

                if (cadenceEnabled && currentCadence > 0) {
                    val cadText = if (labelCadence) {
                        if (isEn) "Cadence $currentCadence" else "케이던스 $currentCadence"
                    } else "$currentCadence"
                    if (spokeSomething) {
                        tts?.playSilentUtterance(500, TextToSpeech.QUEUE_ADD, null)
                        tts?.speak(cadText, TextToSpeech.QUEUE_ADD, params, "cadence")
                    } else {
                        tts?.speak(cadText, TextToSpeech.QUEUE_FLUSH, params, "cadence")
                    }
                }

                handler.postDelayed(this, ttsInterval)
            }
        }
        ttsRunnable = r
        handler.post(r)
    }

    private fun stopTTSLoop() {
        ttsRunnable?.let { handler.removeCallbacks(it) }
        ttsRunnable = null
        // 세션 끝 — focus 반납 + 음악 볼륨 원복
        abandonTtsAudioFocus()
    }

    private val notifChannelId = "hr_monitor_channel"
    private val notifId = 1

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        // 중지 액션 — onStartCommand 에서 ACTION_STOP 감지 후 stopSelf
        val stopIntent = Intent(this, HRForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags)

        return NotificationCompat.Builder(this, notifChannelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_heart, "중지", stopPi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notifChannelId, "VoiceCoach", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(notifId, buildNotification("VoiceCoach 실행 중", "심박수 모니터링 중..."))
    }

    private fun updateNotification() {
        val hrPart = if (currentHR > 0) "♥ $currentHR" else "♥ --"
        val title = when (currentAppMode) {
            "golf" -> "골프 · $hrPart"
            "meditation" -> "Meditation · $hrPart"
            else -> "달리기 · $hrPart"
        }
        val text = when (currentAppMode) {
            "golf" -> "메트로놈 ${if (metronomeBpmCurrent > 0) metronomeBpmCurrent.toString() + " BPM" else "대기"}"
            "meditation" -> {
                val elapsed = getSessionElapsedSec()
                val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
                String.format("경과 %02d:%02d:%02d", h, m, s)
            }
            else -> if (currentPace.isNotEmpty()) "페이스 $currentPace" else "심박수 수신 중"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(notifId, buildNotification(title, text))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 페어링된 워치에 측정 정지 명령
        sendCommandToWatch("/stop_hr_sender")
        handler.removeCallbacksAndMessages(null)
        stopMetronome()
        stopBreathwork()
        stopMinuteAnnouncer()
        stopNoise()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: SecurityException) { }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopCadenceUpdates()
        abandonTtsAudioFocus()  // 미디어 볼륨 원복 + focus 반납
        tts?.stop()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        soundPool?.release()
        soundPool = null
    }
}