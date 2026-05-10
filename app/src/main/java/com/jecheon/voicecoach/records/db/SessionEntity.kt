package com.jecheon.voicecoach.records.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 한 세션의 요약 (한 row = 한 [Start]→[Stop] 사이클).
 *
 * 설계 원칙:
 *   - 한 세션 한 row.  HR 시계열은 별도 [HrSampleEntity] 에 다대일.  이벤트도 [SessionEventEntity].
 *   - 모드 (running/meditation/golf) 구분은 [mode] 컬럼으로.  모드 별 컬럼은 nullable.
 *   - 음성 안내, 메트로놈, 노이즈 사용 여부 등 사용자가 "그 세션에 무엇을 했나" 를 모두 보존.
 *   - 앱 버전 같이 기록해 미래 디버깅 / 마이그레이션 시 유용.
 *
 * 자료형 결정:
 *   - duration / 시간은 Long ms 단위.  Date 변환은 UI 단에서.
 *   - HR / pace 평균/최대/최소는 Int.  샘플 없을 때 0.
 *   - boolean 은 Room 이 자동으로 INTEGER 로 매핑.
 *
 * Nullable 정책:
 *   - 모드 별 컬럼 (예: meditation 만 의미 있는 firstMinuteAvg) 은 다른 모드 에선 null.
 *   - "측정 안 됨" vs "기록 못 함" 구분 필요한 곳은 null vs 0 둘 다 사용.  코드상 명확히.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // ── 공통 ────────────────────────────────────────────────────────
    /** "running" | "meditation" | "golf" */
    val mode: String,
    /** Wall clock 시작 시각 (System.currentTimeMillis). 사용자에게 보여줄 날짜/시각용. */
    val startedAtMs: Long,
    /** Wall clock 종료 시각.  세션 진행 중이면 = startedAtMs (저장은 종료 시점에만). */
    val endedAtMs: Long,
    /** 총 지속 시간 초 (endedAtMs - startedAtMs / 1000). UI 표시 편의 위해 따로 저장. */
    val durationSec: Long,

    /** Onboarding 에서 선택한 워치 종류 — "ble" / "wear" / "none" */
    val deviceType: String,
    /** 실제 HR 입력 출처 — "ble" / "wear" / "none". 세션 중 변할 수 있어 "마지막 알려진 source". */
    val hrSource: String,

    val appVersionName: String,
    val appVersionCode: Int,

    // ── HR 요약 ─────────────────────────────────────────────────────
    /** 모든 유효 (>0) HR 샘플의 산술 평균. 샘플 없으면 0. */
    val avgHr: Int,
    val maxHr: Int,
    /** 0 이 의미 있어서 minHr 는 양수만 집계.  유효 샘플 없으면 0. */
    val minHr: Int,
    /** 가장 마지막 받은 BPM. */
    val latestHr: Int,
    /** 가장 처음 받은 BPM. 60초 안 도달했으면 첫 샘플. */
    val firstHr: Int,
    /** 종료 직전 마지막 BPM (latestHr 와 동일). 명시적 컬럼으로 둠. */
    val lastHr: Int,
    /** 유효 (>0) 샘플 개수. */
    val validHrSampleCount: Int,
    /** 0 포함 전체 샘플 개수. */
    val hrSampleCount: Int,

    // ── Running 전용 (다른 모드는 null) ─────────────────────────────
    val distanceMeters: Float? = null,
    val currentPaceLast: String? = null,
    val avgPaceSecPerKm: Int? = null,
    val bestPaceSecPerKm: Int? = null,
    val paceSampleCount: Int? = null,
    val cadenceAvg: Int? = null,
    val cadenceMax: Int? = null,
    val cadenceLast: Int? = null,
    /** TYPE_STEP_DETECTOR 에서 받은 누적 step 이벤트 개수. */
    val stepCount: Int? = null,
    val coachEnabled: Boolean? = null,
    val coachUpperBpm: Int? = null,
    val coachLowerBpm: Int? = null,
    val coachAlertCountHigh: Int? = null,
    val coachAlertCountLow: Int? = null,
    val coachAlertCountTotal: Int? = null,
    val ttsIntervalSec: Int? = null,
    val hrVoiceEnabled: Boolean? = null,
    val paceVoiceEnabled: Boolean? = null,
    val cadenceVoiceEnabled: Boolean? = null,

    // ── Meditation 전용 ─────────────────────────────────────────────
    /** 1분 평균 안내 윈도우 초.  (현재 60초 고정이지만 향후 설정 가능성 위해 컬럼.) */
    val meditationHrAvgWindowSec: Int? = null,
    val startMinuteAvgHr: Int? = null,
    val lastMinuteAvgHr: Int? = null,
    /** lastMinuteAvgHr - startMinuteAvgHr.  음수면 감소 (이상적). */
    val deltaHr: Int? = null,
    /** announceMinuteUpdate 가 호출된 횟수. */
    val minuteAnnouncementCount: Int? = null,
    /** Focus 모드 (몰입 화면) 진입 한 적 있는지. */
    val focusModeUsed: Boolean? = null,
    val focusMuteEnabled: Boolean? = null,
    val focusMuteAfterMin: Int? = null,
    /** "box" | "478" | "coherent" | "long_exhale" | "custom" */
    val breathPreset: String? = null,
    /** "phase" | "count" | "silent" */
    val breathAudioMode: String? = null,
    val breathTotalCycles: Int? = null,
    val breathPhaseCount: Int? = null,
    val breathStarted: Boolean? = null,
    val noiseUsed: Boolean? = null,
    val noisePreset: String? = null,
    val noiseVolumePct: Int? = null,
    /** 커스텀 노이즈일 때만 의미. cutoff Hz. */
    val noiseCustomToneHz: Int? = null,
    val binauralEnabled: Boolean? = null,
    val binauralStrength: String? = null,
    val binauralMode: String? = null,
    /** 40Hz 비트가 실제 재생된 누적 시간 초.  세션 종료 직전 stop 까지의 합. */
    val binauralUsedDurationSec: Int? = null,

    // ── Golf 전용 ───────────────────────────────────────────────────
    val golfSwingMetronomeUsed: Boolean? = null,
    val golfBpm: Int? = null,
    /** "driver" | "iron" | "wedge" | "putter" | "custom" */
    val selectedClubPreset: String? = null,
    val flashLightEnabled: Boolean? = null,
    val flashScreenEnabled: Boolean? = null,
    val swingBeatCount: Int? = null,
    val impactBeatCount: Int? = null,
)
