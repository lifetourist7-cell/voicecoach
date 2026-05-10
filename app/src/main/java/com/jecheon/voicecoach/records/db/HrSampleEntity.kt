package com.jecheon.voicecoach.records.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 한 세션 내 1개 HR 측정점.
 *
 * 한 세션이 1Hz 로 1시간이면 ~3600 row.  배치 insert 로 한 트랜잭션에 처리.
 * sessionId 인덱스 필수 — 세션별 조회가 매우 자주 일어남.
 *
 * sessionId 가 사라지면 cascade 로 함께 삭제 (사용자가 세션 통째로 지움).
 */
@Entity(
    tableName = "hr_samples",
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
data class HrSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 세션 시작으로부터 경과 ms.  0 = 세션 시작 시점. 그래프 X 축 계산용. */
    val elapsedMs: Long,
    /** Wall clock 시각 (System.currentTimeMillis).  타임스탬프 표기 / 일별 통계용. */
    val wallClockMs: Long,
    val bpm: Int,
    /** "ble" | "wear" — 세션 중 source 가 바뀔 수 있어 샘플 단위로 기록. */
    val source: String,
    /** 해당 시점 페이스 (running 전용).  null 가능. */
    val paceSecPerKm: Int? = null,
    /** 해당 시점 케이던스 (running 전용).  null 가능. */
    val cadence: Int? = null,
)
