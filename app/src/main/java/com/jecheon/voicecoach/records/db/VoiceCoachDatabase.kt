package com.jecheon.voicecoach.records.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 앱 전역 Room 데이터베이스.
 *
 * 단일 인스턴스 (singleton) — Activity / Service 어디에서나 [get] 으로 동일 객체 반환.
 *
 * 마이그레이션 정책:
 *   - 초기 출시 v1.5 가 첫 DB 도입 → version 1.
 *   - 이후 entity 변경 시 fallbackToDestructiveMigration() 대신 정식 Migration 정의 필요.
 *   - 사용자 데이터 보존이 중요 (운동 기록은 정성적 가치 큼).
 *
 * 비동기 IO:
 *   - 모든 DAO 호출은 메인 스레드 X.
 *   - [io] ExecutorService 로 worker thread 에서 호출.
 *   - 단일 thread 라 동시 트랜잭션 충돌 없음 — Room 의 lock 도 가벼워짐.
 */
@Database(
    entities = [SessionEntity::class, HrSampleEntity::class, SessionEventEntity::class],
    version = 1,
    exportSchema = false  // 단일 개발자 프로젝트 — schema 변경 사항은 git diff 로 추적
)
abstract class VoiceCoachDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        private const val DB_NAME = "voicecoach_records.db"

        @Volatile
        private var instance: VoiceCoachDatabase? = null

        /** 앱 전역 단일 IO executor — 모든 DB 작업이 이 한 thread 에서 직렬로. */
        val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VC-DB-IO").apply { isDaemon = true }
        }

        fun get(context: Context): VoiceCoachDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoiceCoachDatabase::class.java,
                    DB_NAME
                )
                    // 초기 출시 — 별도 마이그레이션 없음.  v2 부터 Migration.from(1).to(2) 정의.
                    .build()
                    .also { instance = it }
            }
        }
    }
}
