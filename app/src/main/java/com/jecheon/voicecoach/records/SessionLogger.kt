package com.jecheon.voicecoach.records

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jecheon.voicecoach.BuildConfig
import com.jecheon.voicecoach.records.db.HrSampleEntity
import com.jecheon.voicecoach.records.db.SessionEntity
import com.jecheon.voicecoach.records.db.SessionEventEntity
import com.jecheon.voicecoach.records.db.VoiceCoachDatabase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 한 세션의 진행 중 데이터 (HR 샘플, 이벤트, 누적 메트릭) 를 메모리에 모아두다가
 * 종료 시 [persist] 한 번에 DB 에 저장.
 *
 * 설계 이유:
 *   - 매 HR 샘플마다 DB write 하면 1Hz × 1시간 = 3600 commit → IO 비용 큼
 *   - 메모리 누적 후 한 번 저장이 효율적 + 트랜잭션 일관성 유지
 *   - 만약 process kill 되면 그 세션 기록 손실되지만, 정상 stop 흐름에서는 문제 없음
 *
 * Thread safety:
 *   - 모든 add* / update* 는 메인 thread (HRForegroundService 의 handler) 에서 호출
 *   - persist 는 백그라운드 IO thread.  단일 호출 보장 (persisted flag)
 *
 * 중복 저장 방지:
 *   - persist 는 [persisted] AtomicBoolean 으로 한 번만 실행
 *   - HRForegroundService.onDestroy 와 명시적 stop 에서 중복 호출돼도 안전
 */
class SessionLogger(
    context: Context,
    private val mode: String,
    private val deviceType: String,
    appVersionName: String = BuildConfig.VERSION_NAME,
    appVersionCode: Int = BuildConfig.VERSION_CODE
) {
    private val appContext = context.applicationContext
    private val sessionStartedAtMs: Long = System.currentTimeMillis()
    private val sessionStartedElapsed: Long = SystemClock.elapsedRealtime()

    private val versionName: String = appVersionName
    private val versionCode: Int = appVersionCode

    private val persisted = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    // ── 누적 데이터 ───────────────────────────────────────────────
    private val hrSamples = ArrayList<HrSampleEntity>()
    private val events = ArrayList<SessionEventEntity>()

    // ── HR 통계 ──────────────────────────────────────────────────
    private var firstHr: Int = 0
    private var latestHr: Int = 0
    private var maxHr: Int = 0
    private var minHr: Int = 0  // 양수 샘플 only
    private var hrSum: Long = 0L
    private var validHrCount: Int = 0
    private var totalHrCount: Int = 0
    private var lastHrSource: String = "none"

    // ── Running 메트릭 ───────────────────────────────────────────
    private var distanceMeters: Float = 0f
    private var lastPaceText: String? = null
    private var paceSum: Long = 0L
    private var paceCount: Int = 0
    private var bestPaceSecPerKm: Int = Int.MAX_VALUE
    private var cadenceSum: Long = 0L
    private var cadenceCount: Int = 0
    private var cadenceMaxObs: Int = 0
    private var cadenceLastObs: Int = 0
    private var stepCount: Int = 0
    private var coachEnabled: Boolean = false
    private var coachUpper: Int = 0
    private var coachLower: Int = 0
    private val coachAlertHigh = AtomicInteger(0)
    private val coachAlertLow = AtomicInteger(0)
    private var ttsIntervalSec: Int = 0
    private var hrVoiceEnabled: Boolean = false
    private var paceVoiceEnabled: Boolean = false
    private var cadenceVoiceEnabled: Boolean = false

    // ── Meditation 메트릭 ────────────────────────────────────────
    private var medHrAvgWindowSec: Int = 60
    private var startMinuteAvg: Int = 0
    private var lastMinuteAvg: Int = 0
    private val minuteAnnounceCount = AtomicInteger(0)
    private var focusModeUsed: Boolean = false
    private var focusMuteEnabled: Boolean = false
    private var focusMuteAfterMin: Int = 0
    private var breathPreset: String? = null
    private var breathAudioMode: String? = null
    private val breathTotalCycles = AtomicInteger(0)
    private val breathPhaseCount = AtomicInteger(0)
    private var breathStarted: Boolean = false
    private var noiseUsed: Boolean = false
    private var noisePreset: String? = null
    private var noiseVolumePct: Int = 0
    private var noiseCustomToneHz: Int = 0
    private var binauralEnabled: Boolean = false
    private var binauralStrength: String? = null
    private var binauralMode: String? = null
    private var binauralStartElapsed: Long = -1L
    private var binauralAccumulatedSec: Int = 0

    // ── Golf 메트릭 ──────────────────────────────────────────────
    private var golfMetronomeUsed: Boolean = false
    private var golfBpm: Int = 0
    private var selectedClubPreset: String? = null
    private var flashLightEnabled: Boolean = false
    private var flashScreenEnabled: Boolean = false
    private val swingBeatCount = AtomicInteger(0)
    private val impactBeatCount = AtomicInteger(0)

    init {
        logEvent("session_start", label = mode)
    }

    /** [System.currentTimeMillis] 기준 wall clock. */
    private fun nowMs(): Long = System.currentTimeMillis()
    private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - sessionStartedElapsed

    // ── HR 샘플 기록 ─────────────────────────────────────────────
    /**
     * 매 HR 수신 시 호출.
     * @param bpm 양수일 때만 통계 갱신, 0 일 때도 sample row 는 추가 (하트 소스 끊김 추적용).
     * @param source "ble" 또는 "wear" — 샘플 단위로 다를 수 있음.
     * @param paceSecPerKm null = 페이스 없음 (running 외 모드 또는 측정 불가).
     * @param cadence null = 케이던스 없음.
     */
    fun addHrSample(bpm: Int, source: String, paceSecPerKm: Int? = null, cadence: Int? = null) {
        if (cancelled.get() || persisted.get()) return
        val elapsed = elapsedMs()
        val wall = nowMs()
        hrSamples.add(
            HrSampleEntity(
                sessionId = 0,  // DAO 가 insertSessionWithChildren 에서 채움
                elapsedMs = elapsed,
                wallClockMs = wall,
                bpm = bpm,
                source = source,
                paceSecPerKm = paceSecPerKm,
                cadence = cadence
            )
        )
        totalHrCount++
        if (bpm > 0) {
            if (firstHr == 0) firstHr = bpm
            latestHr = bpm
            if (bpm > maxHr) maxHr = bpm
            if (minHr == 0 || bpm < minHr) minHr = bpm
            hrSum += bpm
            validHrCount++
            lastHrSource = source
        }
    }

    // ── Generic event ────────────────────────────────────────────
    fun logEvent(type: String, label: String? = null, value: Int? = null, extraJson: String? = null) {
        if (cancelled.get() || persisted.get()) return
        events.add(
            SessionEventEntity(
                sessionId = 0,
                elapsedMs = elapsedMs(),
                wallClockMs = nowMs(),
                type = type,
                label = label,
                value = value,
                extraJson = extraJson
            )
        )
    }

    // ── Running setters ──────────────────────────────────────────
    fun setRunningContext(
        coachEnabled: Boolean,
        coachUpper: Int,
        coachLower: Int,
        ttsIntervalSec: Int,
        hrVoiceEnabled: Boolean,
        paceVoiceEnabled: Boolean,
        cadenceVoiceEnabled: Boolean
    ) {
        this.coachEnabled = coachEnabled
        this.coachUpper = coachUpper
        this.coachLower = coachLower
        this.ttsIntervalSec = ttsIntervalSec
        this.hrVoiceEnabled = hrVoiceEnabled
        this.paceVoiceEnabled = paceVoiceEnabled
        this.cadenceVoiceEnabled = cadenceVoiceEnabled
    }

    fun reportPaceSample(secPerKm: Int, paceText: String) {
        if (secPerKm <= 0) return
        paceSum += secPerKm
        paceCount++
        if (secPerKm < bestPaceSecPerKm) bestPaceSecPerKm = secPerKm
        lastPaceText = paceText
    }

    fun reportDistance(distanceMetersTotal: Float) {
        if (distanceMetersTotal > distanceMeters) distanceMeters = distanceMetersTotal
    }

    fun reportCadence(spm: Int) {
        if (spm < 0) return
        cadenceSum += spm
        cadenceCount++
        if (spm > cadenceMaxObs) cadenceMaxObs = spm
        cadenceLastObs = spm
    }

    fun reportStepEvent() { stepCount++ }

    fun reportCoachAlert(upper: Boolean, currentBpm: Int) {
        if (upper) {
            coachAlertHigh.incrementAndGet()
            logEvent("coach_high", label = "high", value = currentBpm)
        } else {
            coachAlertLow.incrementAndGet()
            logEvent("coach_low", label = "low", value = currentBpm)
        }
    }

    // ── Meditation setters ───────────────────────────────────────
    fun setMeditationContext(
        hrAvgWindowSec: Int,
        focusMuteEnabled: Boolean,
        focusMuteAfterMin: Int,
        breathPreset: String?,
        breathAudioMode: String?
    ) {
        this.medHrAvgWindowSec = hrAvgWindowSec
        this.focusMuteEnabled = focusMuteEnabled
        this.focusMuteAfterMin = focusMuteAfterMin
        this.breathPreset = breathPreset
        this.breathAudioMode = breathAudioMode
    }

    fun reportFocusModeUsed() { focusModeUsed = true }

    fun reportStartMinuteAvg(avg: Int) { if (startMinuteAvg == 0) startMinuteAvg = avg }
    fun reportLastMinuteAvg(avg: Int) {
        lastMinuteAvg = avg
        minuteAnnounceCount.incrementAndGet()
        logEvent("meditation_minute_avg", value = avg)
    }

    fun reportBreathStarted() { breathStarted = true }
    fun reportBreathPhaseStart(label: String, durationSec: Int) {
        breathPhaseCount.incrementAndGet()
        logEvent("breath_phase_start", label = label, value = durationSec)
    }
    fun reportBreathCycleComplete(cycleIndex: Int) {
        breathTotalCycles.incrementAndGet()
        logEvent("breath_cycle_complete", value = cycleIndex)
    }
    fun reportBreathAudioModeChange(newMode: String) {
        breathAudioMode = newMode
        logEvent("breath_audio_mode_change", label = newMode)
    }

    fun reportNoiseStart(preset: String, volumePct: Int, customToneHz: Int) {
        noiseUsed = true
        noisePreset = preset
        noiseVolumePct = volumePct
        noiseCustomToneHz = customToneHz
        logEvent("noise_start", label = preset, value = volumePct)
    }
    fun reportNoiseStop() {
        logEvent("noise_stop")
    }
    fun reportNoisePresetChange(preset: String) {
        noisePreset = preset
        logEvent("noise_preset_change", label = preset)
    }

    fun reportBinauralStart(strength: String, mode: String) {
        binauralEnabled = true
        binauralStrength = strength
        binauralMode = mode
        if (binauralStartElapsed < 0) binauralStartElapsed = elapsedMs()
        logEvent("binaural_start", label = strength, value = (if (mode == "balanced") 1 else 0))
    }
    fun reportBinauralStop() {
        if (binauralStartElapsed >= 0) {
            val durSec = ((elapsedMs() - binauralStartElapsed) / 1000L).toInt().coerceAtLeast(0)
            binauralAccumulatedSec += durSec
            binauralStartElapsed = -1L
        }
        logEvent("binaural_stop")
    }

    // ── Golf setters ─────────────────────────────────────────────
    fun setGolfContext(
        bpm: Int,
        selectedClubPreset: String?,
        flashLightEnabled: Boolean,
        flashScreenEnabled: Boolean
    ) {
        golfMetronomeUsed = true
        golfBpm = bpm
        this.selectedClubPreset = selectedClubPreset
        this.flashLightEnabled = flashLightEnabled
        this.flashScreenEnabled = flashScreenEnabled
        logEvent("golf_metronome_start", value = bpm)
    }

    fun reportSwingBeat() { swingBeatCount.incrementAndGet() }
    fun reportImpactBeat() {
        impactBeatCount.incrementAndGet()
        logEvent("golf_impact_beat")
    }

    // ── Wear / BLE 상태 ──────────────────────────────────────────
    fun reportWearStatus(status: String) {
        logEvent("wear_status_$status")
    }

    fun reportBleConnected() { logEvent("ble_connected") }
    fun reportBleDisconnected() { logEvent("ble_disconnected") }

    /**
     * 세션 종료 — DB 에 영구 저장.  IO thread 에서 실행 (호출자는 메인 thread 가능).
     *
     * 멱등 (idempotent): 한 세션 인스턴스에 두 번 호출되도 한 번만 실제 저장.
     * 사용자가 stop 누르고 onDestroy 양쪽에서 호출돼도 안전.
     *
     * 매우 짧은 세션 (< 3 초) 은 의미 없는 우발 클릭으로 간주, 저장 생략.
     * 단 Meditation / Golf 같이 HR 없이도 의미 있는 모드라 duration 만 보고 판단.
     */
    fun persist() {
        if (cancelled.get()) return
        if (!persisted.compareAndSet(false, true)) return

        val endedAtMs = nowMs()
        val durationSec = (endedAtMs - sessionStartedAtMs) / 1000L
        if (durationSec < 3) {
            // 너무 짧은 세션 — 사용자가 실수로 누른 것으로 간주.  events / samples 도 의미 없음.
            return
        }

        // binaural 이 stop 호출 없이 세션 끝났으면 마지막 시점까지 누적
        if (binauralStartElapsed >= 0) {
            val durSec = ((elapsedMs() - binauralStartElapsed) / 1000L).toInt().coerceAtLeast(0)
            binauralAccumulatedSec += durSec
            binauralStartElapsed = -1L
        }

        logEvent("session_stop")

        val avgHr = if (validHrCount > 0) (hrSum / validHrCount).toInt() else 0
        val avgPace = if (paceCount > 0) (paceSum / paceCount).toInt() else 0
        val avgCadence = if (cadenceCount > 0) (cadenceSum / cadenceCount).toInt() else 0
        val bestPaceFinal = if (bestPaceSecPerKm == Int.MAX_VALUE) 0 else bestPaceSecPerKm

        val isRunning = mode == "running"
        val isMeditation = mode == "meditation"
        val isGolf = mode == "golf"

        val session = SessionEntity(
            mode = mode,
            startedAtMs = sessionStartedAtMs,
            endedAtMs = endedAtMs,
            durationSec = durationSec,
            deviceType = deviceType,
            hrSource = lastHrSource,
            appVersionName = versionName,
            appVersionCode = versionCode,
            avgHr = avgHr,
            maxHr = maxHr,
            minHr = minHr,
            latestHr = latestHr,
            firstHr = firstHr,
            lastHr = latestHr,
            validHrSampleCount = validHrCount,
            hrSampleCount = totalHrCount,

            distanceMeters = if (isRunning) distanceMeters else null,
            currentPaceLast = if (isRunning) lastPaceText else null,
            avgPaceSecPerKm = if (isRunning) avgPace else null,
            bestPaceSecPerKm = if (isRunning) bestPaceFinal else null,
            paceSampleCount = if (isRunning) paceCount else null,
            cadenceAvg = if (isRunning) avgCadence else null,
            cadenceMax = if (isRunning) cadenceMaxObs else null,
            cadenceLast = if (isRunning) cadenceLastObs else null,
            stepCount = if (isRunning) stepCount else null,
            coachEnabled = if (isRunning) coachEnabled else null,
            coachUpperBpm = if (isRunning) coachUpper else null,
            coachLowerBpm = if (isRunning) coachLower else null,
            coachAlertCountHigh = if (isRunning) coachAlertHigh.get() else null,
            coachAlertCountLow = if (isRunning) coachAlertLow.get() else null,
            coachAlertCountTotal = if (isRunning) (coachAlertHigh.get() + coachAlertLow.get()) else null,
            ttsIntervalSec = if (isRunning) ttsIntervalSec else null,
            hrVoiceEnabled = if (isRunning) hrVoiceEnabled else null,
            paceVoiceEnabled = if (isRunning) paceVoiceEnabled else null,
            cadenceVoiceEnabled = if (isRunning) cadenceVoiceEnabled else null,

            meditationHrAvgWindowSec = if (isMeditation) medHrAvgWindowSec else null,
            startMinuteAvgHr = if (isMeditation) startMinuteAvg else null,
            lastMinuteAvgHr = if (isMeditation) lastMinuteAvg else null,
            deltaHr = if (isMeditation && startMinuteAvg > 0 && lastMinuteAvg > 0)
                (lastMinuteAvg - startMinuteAvg) else null,
            minuteAnnouncementCount = if (isMeditation) minuteAnnounceCount.get() else null,
            focusModeUsed = if (isMeditation) focusModeUsed else null,
            focusMuteEnabled = if (isMeditation) focusMuteEnabled else null,
            focusMuteAfterMin = if (isMeditation) focusMuteAfterMin else null,
            breathPreset = if (isMeditation) breathPreset else null,
            breathAudioMode = if (isMeditation) breathAudioMode else null,
            breathTotalCycles = if (isMeditation) breathTotalCycles.get() else null,
            breathPhaseCount = if (isMeditation) breathPhaseCount.get() else null,
            breathStarted = if (isMeditation) breathStarted else null,
            noiseUsed = if (isMeditation) noiseUsed else null,
            noisePreset = if (isMeditation) noisePreset else null,
            noiseVolumePct = if (isMeditation) noiseVolumePct else null,
            noiseCustomToneHz = if (isMeditation) noiseCustomToneHz else null,
            binauralEnabled = if (isMeditation) binauralEnabled else null,
            binauralStrength = if (isMeditation) binauralStrength else null,
            binauralMode = if (isMeditation) binauralMode else null,
            binauralUsedDurationSec = if (isMeditation) binauralAccumulatedSec else null,

            golfSwingMetronomeUsed = if (isGolf) golfMetronomeUsed else null,
            golfBpm = if (isGolf) golfBpm else null,
            selectedClubPreset = if (isGolf) selectedClubPreset else null,
            flashLightEnabled = if (isGolf) flashLightEnabled else null,
            flashScreenEnabled = if (isGolf) flashScreenEnabled else null,
            swingBeatCount = if (isGolf) swingBeatCount.get() else null,
            impactBeatCount = if (isGolf) impactBeatCount.get() else null,
        )

        val samplesSnapshot = hrSamples.toList()
        val eventsSnapshot = events.toList()

        VoiceCoachDatabase.io.execute {
            try {
                val dao = VoiceCoachDatabase.get(appContext).sessionDao()
                dao.insertSessionWithChildren(session, samplesSnapshot, eventsSnapshot)
            } catch (e: Exception) {
                // 저장 실패 — 앱 크래시 X.  로그만 남김.  사용자 데이터 한 세션 손실은 감수.
                Log.e(TAG, "session persist failed", e)
            }
        }
    }

    /**
     * 매우 짧은 클릭 등으로 세션 자체가 의미 없을 때 호출.  persist 호출되도 무시됨.
     * (사용처 거의 없음 — 일반 stop 흐름에서는 persist 의 internal duration 검사로 충분)
     */
    fun cancel() { cancelled.set(true) }

    companion object { private const val TAG = "SessionLogger" }
}
