package com.jecheon.voicecoach.records.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jecheon.voicecoach.HrGraphView
import com.jecheon.voicecoach.R
import com.jecheon.voicecoach.applyEdgeToEdge
import com.jecheon.voicecoach.records.db.HrSampleEntity
import com.jecheon.voicecoach.records.db.SessionEntity
import com.jecheon.voicecoach.records.db.SessionEventEntity
import com.jecheon.voicecoach.records.db.VoiceCoachDatabase
import com.jecheon.voicecoach.withFixedFontScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 세션 상세 화면.
 *
 * 표시 항목:
 *   - 모드 / 날짜 / 총 시간 / 평균·최대·최저 BPM
 *   - 전체 심박 그래프 (HrGraphView, 세션 시작~끝 windowSec 자동 매핑)
 *   - 모드별 상세 (running / meditation / golf 각각 별도 섹션)
 *   - 이벤트 타임라인 (코치 알림, 호흡 phase, 노이즈 시작/정지 등)
 *
 * HrGraphView 는 elapsedRealtime 기반인데 DB 샘플은 그것이 아닌 "session 시작 후 elapsedMs".
 * 그래프에 넣을 때는 (windowEnd - elapsedMs) 매핑으로 가짜 elapsedRealtime 좌표 만들어 사용.
 */
class HistoryDetailActivity : AppCompatActivity() {

    companion object { const val EXTRA_SESSION_ID = "session_id" }

    private lateinit var detailHrGraph: HrGraphView
    private lateinit var tvDetailMode: TextView
    private lateinit var tvDetailDate: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvAvgHr: TextView
    private lateinit var tvMaxHr: TextView
    private lateinit var tvMinHr: TextView
    private lateinit var tvSessionMeta: TextView

    private lateinit var runningSection: LinearLayout
    private lateinit var runningStats: LinearLayout
    private lateinit var meditationSection: LinearLayout
    private lateinit var meditationStats: LinearLayout
    private lateinit var golfSection: LinearLayout
    private lateinit var golfStats: LinearLayout
    private lateinit var timeline: LinearLayout

    private var session: SessionEntity? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withFixedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_detail)
        applyEdgeToEdge()

        detailHrGraph = findViewById(R.id.detailHrGraph)
        tvDetailMode = findViewById(R.id.tvDetailMode)
        tvDetailDate = findViewById(R.id.tvDetailDate)
        tvDuration = findViewById(R.id.tvDuration)
        tvAvgHr = findViewById(R.id.tvAvgHr)
        tvMaxHr = findViewById(R.id.tvMaxHr)
        tvMinHr = findViewById(R.id.tvMinHr)
        tvSessionMeta = findViewById(R.id.tvSessionMeta)
        runningSection = findViewById(R.id.runningSection)
        runningStats = findViewById(R.id.runningStatsContainer)
        meditationSection = findViewById(R.id.meditationSection)
        meditationStats = findViewById(R.id.meditationStatsContainer)
        golfSection = findViewById(R.id.golfSection)
        golfStats = findViewById(R.id.golfStatsContainer)
        timeline = findViewById(R.id.timelineContainer)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId <= 0) { finish(); return }
        loadSession(sessionId)
    }

    private fun loadSession(id: Long) {
        VoiceCoachDatabase.io.execute {
            val dao = VoiceCoachDatabase.get(this).sessionDao()
            val s = dao.getSession(id)
            if (s == null) {
                runOnUiThread { finish() }
                return@execute
            }
            val samples = dao.getHrSamples(id)
            val events = dao.getEvents(id)
            runOnUiThread { render(s, samples, events) }
        }
    }

    private fun render(s: SessionEntity, samples: List<HrSampleEntity>, events: List<SessionEventEntity>) {
        session = s
        tvDetailMode.text = SessionFormat.modeLabel(s.mode)
        val fmt = SimpleDateFormat("yyyy/M/d (E) HH:mm", Locale.KOREAN)
        tvDetailDate.text = fmt.format(Date(s.startedAtMs))
        tvDuration.text = SessionFormat.duration(s.durationSec)
        tvAvgHr.text = if (s.avgHr > 0) s.avgHr.toString() else "--"
        tvMaxHr.text = if (s.maxHr > 0) s.maxHr.toString() else "--"
        tvMinHr.text = if (s.minHr > 0) s.minHr.toString() else "--"
        tvSessionMeta.text = "v${s.appVersionName} (${s.appVersionCode})  ·  ${SessionFormat.deviceLabel(s.deviceType, s.hrSource)}"

        // 그래프: 그래프 windowSec 를 세션 전체 길이로 설정 → 한 화면에 전체 보임
        val totalSec = s.durationSec.coerceAtLeast(60).toInt()
        detailHrGraph.setWindowSec(totalSec)
        // HrGraphView 는 elapsedRealtime 기반.  여기서는 가짜 좌표 사용:
        // now (현재 시각) - sample.elapsedMs = 그래프 X 좌표용 timestamp.
        val nowFake = android.os.SystemClock.elapsedRealtime()
        val mappedSamples = samples.map { (nowFake - (s.durationSec * 1000L - it.elapsedMs)) to it.bpm }
        detailHrGraph.setSamples(mappedSamples)

        // 모드별 섹션 채우기
        when (s.mode) {
            "running" -> {
                runningSection.visibility = View.VISIBLE
                meditationSection.visibility = View.GONE
                golfSection.visibility = View.GONE
                fillRunningStats(s)
            }
            "meditation" -> {
                runningSection.visibility = View.GONE
                meditationSection.visibility = View.VISIBLE
                golfSection.visibility = View.GONE
                fillMeditationStats(s)
            }
            "golf" -> {
                runningSection.visibility = View.GONE
                meditationSection.visibility = View.GONE
                golfSection.visibility = View.VISIBLE
                fillGolfStats(s)
            }
        }

        // 타임라인
        fillTimeline(events)
    }

    private fun fillRunningStats(s: SessionEntity) {
        runningStats.removeAllViews()
        addStatRow("거리", SessionFormat.distance(s.distanceMeters))
        addStatRow("평균 페이스", SessionFormat.pace(s.avgPaceSecPerKm ?: 0))
        addStatRow("베스트 페이스", SessionFormat.pace(s.bestPaceSecPerKm ?: 0))
        addStatRow("평균 케이던스", (s.cadenceAvg ?: 0).let { if (it > 0) "$it SPM" else "--" })
        addStatRow("최대 케이던스", (s.cadenceMax ?: 0).let { if (it > 0) "$it SPM" else "--" })
        addStatRow("총 발걸음", (s.stepCount ?: 0).toString())
        addStatRow("코치 임계값",
            if (s.coachEnabled == true) "${s.coachLowerBpm ?: '?'} ~ ${s.coachUpperBpm ?: '?'} BPM" else "끄기")
        addStatRow("코치 알림 (상한 / 하한)",
            "${s.coachAlertCountHigh ?: 0}회 / ${s.coachAlertCountLow ?: 0}회")
        addStatRow("음성 안내 간격", "${s.ttsIntervalSec ?: 0}초")
        val voiceItems = ArrayList<String>()
        if (s.hrVoiceEnabled == true) voiceItems.add("HR")
        if (s.paceVoiceEnabled == true) voiceItems.add("페이스")
        if (s.cadenceVoiceEnabled == true) voiceItems.add("케이던스")
        addStatRow("음성 항목", if (voiceItems.isEmpty()) "없음" else voiceItems.joinToString(", "), divider = false)
    }

    private fun fillMeditationStats(s: SessionEntity) {
        meditationStats.removeAllViews()
        val start = s.startMinuteAvgHr ?: 0
        val last = s.lastMinuteAvgHr ?: 0
        addStatRow("시작 1분 평균", if (start > 0) "$start BPM" else "--", parent = meditationStats)
        addStatRow("마지막 1분 평균", if (last > 0) "$last BPM" else "--", parent = meditationStats)
        val delta = s.deltaHr
        val deltaText = if (delta == null || start == 0 || last == 0) "--"
                        else if (delta == 0) "유지"
                        else if (delta < 0) "▼ ${-delta} BPM 감소"
                        else "▲ $delta BPM 증가"
        addStatRow("변화", deltaText, parent = meditationStats)
        addStatRow("1분 평균 안내", "${s.minuteAnnouncementCount ?: 0}회", parent = meditationStats)
        addStatRow("호흡 프리셋",
            s.breathPreset?.let { SessionFormat.breathPresetLabel(it) } ?: "--", parent = meditationStats)
        addStatRow("호흡 안내음", SessionFormat.breathAudioModeLabel(s.breathAudioMode), parent = meditationStats)
        addStatRow("호흡 사이클 / phase",
            "${s.breathTotalCycles ?: 0} / ${s.breathPhaseCount ?: 0}", parent = meditationStats)
        addStatRow("몰입 모드 사용", if (s.focusModeUsed == true) "예" else "아니요", parent = meditationStats)
        if (s.focusMuteEnabled == true) {
            addStatRow("집중 음소거", "${s.focusMuteAfterMin ?: 0}분 후", parent = meditationStats)
        }
        addStatRow("노이즈", buildNoiseSummary(s), parent = meditationStats)
        addStatRow("40Hz 포커스 비트",
            if (s.binauralEnabled == true) "${s.binauralStrength ?: '?'} · ${s.binauralUsedDurationSec ?: 0}초" else "끄기",
            parent = meditationStats, divider = false)
    }

    private fun buildNoiseSummary(s: SessionEntity): String {
        if (s.noiseUsed != true) return "끄기"
        val preset = s.noisePreset ?: "?"
        val custom = if (preset == "custom" && (s.noiseCustomToneHz ?: 0) > 0) " (${s.noiseCustomToneHz}Hz)" else ""
        val vol = s.noiseVolumePct ?: 0
        return "${preset.uppercase()}${custom} · ${vol}%"
    }

    private fun fillGolfStats(s: SessionEntity) {
        golfStats.removeAllViews()
        addStatRow("메트로놈 BPM", (s.golfBpm ?: 0).toString(), parent = golfStats)
        addStatRow("클럽 프리셋",
            s.selectedClubPreset?.let { SessionFormat.golfClubLabel(it) } ?: "--", parent = golfStats)
        addStatRow("총 비트 수",
            "${(s.swingBeatCount ?: 0) + (s.impactBeatCount ?: 0)}회 (스윙 ${s.swingBeatCount ?: 0} / 임팩트 ${s.impactBeatCount ?: 0})",
            parent = golfStats)
        addStatRow("후면 플래시", if (s.flashLightEnabled == true) "켜기" else "끄기", parent = golfStats)
        addStatRow("화면 깜빡", if (s.flashScreenEnabled == true) "켜기" else "끄기",
            parent = golfStats, divider = false)
    }

    private fun fillTimeline(events: List<SessionEventEntity>) {
        timeline.removeAllViews()
        if (events.isEmpty()) {
            val tv = TextView(this).apply {
                text = "기록된 이벤트 없음"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }
            timeline.addView(tv)
            return
        }
        // 너무 많으면 나중에 RecyclerView 로 교체.  현재 단순 LinearLayout — 200건 까지는 스크롤 OK.
        val maxShow = 300
        val list = if (events.size > maxShow) events.take(maxShow) else events
        for (e in list) {
            timeline.addView(makeTimelineRow(e))
        }
        if (events.size > maxShow) {
            val tv = TextView(this).apply {
                text = "… 그리고 ${events.size - maxShow}건 더"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, 12, 0, 0)
            }
            timeline.addView(tv)
        }
    }

    private fun makeTimelineRow(e: SessionEventEntity): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 8, 0, 8)
        }
        val time = TextView(this).apply {
            text = formatElapsed(e.elapsedMs)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            width = (60f * resources.displayMetrics.density).toInt()
        }
        val desc = TextView(this).apply {
            text = describeEvent(e)
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(time)
        row.addView(desc)
        return row
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%d:%02d", m, s)
    }

    private fun describeEvent(e: SessionEventEntity): String {
        val l = e.label
        val v = e.value
        return when (e.type) {
            "session_start" -> "세션 시작 (${SessionFormat.modeLabel(l ?: "?")})"
            "session_stop" -> "세션 종료"
            "coach_high" -> "코치 — 상한 초과 (${v ?: "?"} BPM)"
            "coach_low" -> "코치 — 하한 미달 (${v ?: "?"} BPM)"
            "voice_hr" -> "음성 안내 — HR ${v ?: "?"}"
            "voice_pace" -> "음성 안내 — 페이스 ${l ?: "?"}"
            "voice_cadence" -> "음성 안내 — 케이던스 ${v ?: "?"}"
            "meditation_minute_avg" -> "1분 평균 — ${v ?: "?"} BPM"
            "breath_phase_start" -> "호흡 — ${l ?: "?"} (${v ?: "?"}초)"
            "breath_cycle_complete" -> "호흡 사이클 ${v ?: "?"} 완료"
            "breath_audio_mode_change" -> "호흡 안내음 → ${SessionFormat.breathAudioModeLabel(l)}"
            "noise_start" -> "노이즈 시작 — ${l ?: "?"} ${v ?: 0}%"
            "noise_stop" -> "노이즈 정지"
            "noise_preset_change" -> "노이즈 프리셋 → ${l ?: "?"}"
            "binaural_start" -> "40Hz 비트 시작 — ${l ?: "?"}"
            "binaural_stop" -> "40Hz 비트 정지"
            "golf_metronome_start" -> "메트로놈 시작 — ${v ?: "?"} BPM"
            "golf_impact_beat" -> "임팩트 비트"
            "wear_status_ok" -> "워치 — 연결됨"
            "wear_status_missing" -> "워치 — 앱 미설치"
            "wear_status_no_paired" -> "워치 — 페어링 없음"
            "ble_connected" -> "BLE 연결됨"
            "ble_disconnected" -> "BLE 연결 끊김"
            else -> e.type
        }
    }

    private fun addStatRow(label: String, value: String, divider: Boolean = true, parent: LinearLayout? = null) {
        val container = parent ?: runningStats  // running 이 기본값
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 10, 0, 10)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            gravity = Gravity.END
        })
        container.addView(row)
        if (divider) {
            val line = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(getColor(R.color.surface_variant))
            }
            container.addView(line)
        }
    }

    private fun confirmDelete() {
        val s = session ?: return
        AlertDialog.Builder(this)
            .setTitle("기록 삭제")
            .setMessage("이 세션을 삭제할까요? 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ -> deleteSession(s.id) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteSession(id: Long) {
        VoiceCoachDatabase.io.execute {
            try {
                VoiceCoachDatabase.get(this).sessionDao().deleteSession(id)
            } catch (_: Exception) {}
            runOnUiThread { finish() }
        }
    }
}
