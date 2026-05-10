package com.jecheon.voicecoach.records.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 세션 / HR 샘플 / 이벤트 통합 DAO.
 *
 * Insert 패턴:
 *   - Session 먼저 insert → autoGenerate id 받음
 *   - 받은 id 로 HR samples / events 일괄 insert
 *   - [insertSessionWithChildren] 가 트랜잭션으로 묶음
 *
 * Query 패턴:
 *   - 목록: getAllSessions / getSessionsByMode (카드 list 용)
 *   - 상세: getSession / getHrSamples / getEvents 분리 호출
 *     (Room @Relation 으로 한 번에 가져올 수도 있지만 cardinality 큰 children 은 분리가 가벼움)
 */
@Dao
interface SessionDao {

    // ── Insert ──────────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHrSamples(samples: List<HrSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEvents(events: List<SessionEventEntity>)

    /**
     * 한 세션의 모든 데이터를 트랜잭션으로 저장.
     *   - 부분 실패 방지 (HR 만 저장됐는데 session row 가 없는 일 차단)
     *   - 새 sessionId 반환 (samples / events 의 sessionId 는 이 안에서 자동 채움)
     */
    @Transaction
    fun insertSessionWithChildren(
        session: SessionEntity,
        hrSamples: List<HrSampleEntity>,
        events: List<SessionEventEntity>
    ): Long {
        val newId = insertSession(session)
        if (hrSamples.isNotEmpty()) insertHrSamples(hrSamples.map { it.copy(sessionId = newId) })
        if (events.isNotEmpty()) insertEvents(events.map { it.copy(sessionId = newId) })
        return newId
    }

    // ── Query: list ─────────────────────────────────────────────────
    /** 모든 세션, 최근 시작 시각 우선. */
    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE mode = :mode ORDER BY startedAtMs DESC")
    fun getSessionsByMode(mode: String): List<SessionEntity>

    // ── Query: single ───────────────────────────────────────────────
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY elapsedMs ASC")
    fun getHrSamples(sessionId: Long): List<HrSampleEntity>

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY elapsedMs ASC")
    fun getEvents(sessionId: Long): List<SessionEventEntity>

    // ── Delete ──────────────────────────────────────────────────────
    /** 세션 삭제 시 cascade 로 hr_samples / session_events 자동 삭제됨. */
    @Query("DELETE FROM sessions WHERE id = :id")
    fun deleteSession(id: Long)

    @Query("DELETE FROM sessions")
    fun deleteAll()

    // ── Stats ───────────────────────────────────────────────────────
    @Query("SELECT COUNT(*) FROM sessions")
    fun countAll(): Int
}
