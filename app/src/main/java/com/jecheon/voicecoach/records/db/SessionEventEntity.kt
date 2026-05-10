package com.jecheon.voicecoach.records.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 세션 진행 중 발생한 이벤트 (음성 안내, 코치 알림, 호흡 phase 전환 등) 의 타임라인 기록.
 *
 * type 으로 분류, label / value / extraJson 으로 추가 컨텍스트.
 *
 * 알려진 type:
 *   - session_start, session_stop
 *   - hr_received               (label = "ble"/"wear", value = bpm)
 *   - coach_high, coach_low     (label = "high"/"low", value = current bpm)
 *   - voice_hr                  (value = bpm)
 *   - voice_pace                (label = pace string)
 *   - voice_cadence             (value = spm)
 *   - meditation_minute_avg     (value = avg)
 *   - breath_phase_start        (label = "들숨"/"홀드"/"날숨", value = duration sec)
 *   - breath_cycle_complete     (value = cycle index)
 *   - breath_audio_mode_change  (label = "phase"/"count"/"silent")
 *   - noise_start, noise_stop   (label = preset, value = volume%)
 *   - noise_preset_change       (label = new preset)
 *   - binaural_start, binaural_stop  (label = strength, value = mode index)
 *   - golf_metronome_start      (value = bpm)
 *   - golf_impact_beat          (none)
 *   - wear_status_ok, wear_status_missing, wear_status_no_paired
 *   - ble_connected, ble_disconnected
 */
@Entity(
    tableName = "session_events",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedMs: Long,
    val wallClockMs: Long,
    val type: String,
    val label: String? = null,
    val value: Int? = null,
    /** 보조 데이터 — 자유 형식.  현재는 사용 거의 없지만 향후 확장 위해. */
    val extraJson: String? = null,
)
