package com.jecheon.voicecoach.records.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jecheon.voicecoach.R
import com.jecheon.voicecoach.applyEdgeToEdge
import com.jecheon.voicecoach.records.db.SessionEntity
import com.jecheon.voicecoach.records.db.VoiceCoachDatabase
import com.jecheon.voicecoach.withFixedFontScale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 기록 화면 — 세션 카드 목록 + 모드 필터.
 *
 * 데이터 흐름:
 *   - onResume 마다 DB 에서 다시 로드 (세션 종료 후 돌아왔을 때 자동 갱신)
 *   - IO thread 에서 쿼리, 메인 thread 에서 RecyclerView.notifyDataSetChanged
 *
 * 카드 터치 → HistoryDetailActivity 로 sessionId 전달.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var sessionList: RecyclerView
    private lateinit var emptyView: View
    private lateinit var tvCount: TextView
    private lateinit var filterGroup: RadioGroup

    private val adapter = SessionAdapter { session ->
        startActivity(
            Intent(this, HistoryDetailActivity::class.java)
                .putExtra(HistoryDetailActivity.EXTRA_SESSION_ID, session.id)
        )
    }

    private var currentFilter: String = "all"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withFixedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        applyEdgeToEdge()

        sessionList = findViewById(R.id.sessionList)
        emptyView = findViewById(R.id.emptyView)
        tvCount = findViewById(R.id.tvCount)
        filterGroup = findViewById(R.id.filterGroup)

        sessionList.layoutManager = LinearLayoutManager(this)
        sessionList.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        filterGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.filterRunning -> "running"
                R.id.filterMeditation -> "meditation"
                R.id.filterGolf -> "golf"
                else -> "all"
            }
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        VoiceCoachDatabase.io.execute {
            val dao = VoiceCoachDatabase.get(this).sessionDao()
            val sessions = if (currentFilter == "all") dao.getAllSessions()
                           else dao.getSessionsByMode(currentFilter)
            runOnUiThread {
                adapter.submit(sessions)
                val count = sessions.size
                tvCount.text = if (count > 0) "총 ${count}건" else ""
                emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE
                sessionList.visibility = if (count == 0) View.GONE else View.VISIBLE
            }
        }
    }
}

/**
 * 세션 카드 어댑터.
 *
 * 모드별로 슬롯 1/2/3 의 텍스트가 다름.
 *   running: 시간 / 평균 BPM / 평균 페이스
 *   meditation: 시간 / 평균 BPM / 시작→끝 변화
 *   golf: 시간 / 평균 BPM / BPM(메트로놈)
 */
private class SessionAdapter(
    private val onClick: (SessionEntity) -> Unit
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    private val items = ArrayList<SessionEntity>()

    fun submit(list: List<SessionEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val accent: View = itemView.findViewById(R.id.modeAccent)
        private val tvMode: TextView = itemView.findViewById(R.id.tvCardMode)
        private val tvDate: TextView = itemView.findViewById(R.id.tvCardDate)
        private val tvSlot1V: TextView = itemView.findViewById(R.id.tvCardSlot1Value)
        private val tvSlot1L: TextView = itemView.findViewById(R.id.tvCardSlot1Label)
        private val tvSlot2V: TextView = itemView.findViewById(R.id.tvCardSlot2Value)
        private val tvSlot2L: TextView = itemView.findViewById(R.id.tvCardSlot2Label)
        private val tvSlot3V: TextView = itemView.findViewById(R.id.tvCardSlot3Value)
        private val tvSlot3L: TextView = itemView.findViewById(R.id.tvCardSlot3Label)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvCardSubtitle)

        fun bind(s: SessionEntity) {
            // 모드 라벨 + 색
            tvMode.text = SessionFormat.modeLabel(s.mode)
            accent.setBackgroundColor(itemView.context.getColor(SessionFormat.modeColor(s.mode)))
            // 날짜 — 오늘은 "오늘", 어제는 "어제", 그 외 "M/d (요일)"
            tvDate.text = SessionFormat.relativeDateTime(s.startedAtMs)

            tvSlot1V.text = SessionFormat.duration(s.durationSec)
            tvSlot1L.text = "시간"
            tvSlot2V.text = if (s.avgHr > 0) s.avgHr.toString() else "--"
            tvSlot2L.text = "평균 BPM"

            when (s.mode) {
                "running" -> {
                    val avgPace = s.avgPaceSecPerKm ?: 0
                    tvSlot3V.text = if (avgPace > 0) SessionFormat.pace(avgPace) else "--"
                    tvSlot3L.text = "평균 페이스"
                    tvSubtitle.text = SessionFormat.runningSubtitle(s)
                }
                "meditation" -> {
                    val delta = s.deltaHr
                    tvSlot3V.text = when {
                        delta == null || (s.startMinuteAvgHr ?: 0) == 0 -> "--"
                        delta == 0 -> "유지"
                        delta < 0 -> "▼ ${-delta}"
                        else -> "▲ $delta"
                    }
                    tvSlot3L.text = "변화"
                    tvSubtitle.text = SessionFormat.meditationSubtitle(s)
                }
                "golf" -> {
                    val bpm = s.golfBpm ?: 0
                    tvSlot3V.text = if (bpm > 0) bpm.toString() else "--"
                    tvSlot3L.text = "메트로놈 BPM"
                    tvSubtitle.text = SessionFormat.golfSubtitle(s)
                }
                else -> {
                    tvSlot3V.text = "--"
                    tvSlot3L.text = ""
                    tvSubtitle.text = ""
                }
            }
            itemView.setOnClickListener { onClick(s) }
        }
    }
}

/** 세션 텍스트 포맷팅 헬퍼 — Activity / Detail / 카드 모두 공유. */
internal object SessionFormat {

    private val timeFmt = SimpleDateFormat("M/d (E) HH:mm", Locale.KOREAN)

    fun modeLabel(mode: String): String = when (mode) {
        "running" -> "달리기"
        "meditation" -> "Meditation"
        "golf" -> "골프"
        else -> mode
    }

    fun modeColor(mode: String): Int = when (mode) {
        "running" -> R.color.hr_neon_red
        "meditation" -> R.color.pace_electric_blue
        "golf" -> R.color.accent_green
        else -> R.color.text_secondary
    }

    fun relativeDateTime(wallMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = wallMs }
        val now = Calendar.getInstance()
        val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                      cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val timeOnly = SimpleDateFormat("HH:mm", Locale.KOREAN).format(Date(wallMs))
        if (sameDay) return "오늘 $timeOnly"
        val yesterday = (Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) })
        if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)) return "어제 $timeOnly"
        return timeFmt.format(Date(wallMs))
    }

    fun duration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
               else String.format("%d:%02d", m, s)
    }

    fun pace(secPerKm: Int): String {
        if (secPerKm <= 0) return "--"
        val m = secPerKm / 60
        val s = secPerKm % 60
        return String.format("%d'%02d\"", m, s)
    }

    fun distance(meters: Float?): String {
        if (meters == null || meters <= 0f) return "--"
        return if (meters >= 1000) String.format("%.2f km", meters / 1000f)
               else "${meters.toInt()} m"
    }

    fun deviceLabel(deviceType: String, hrSource: String): String {
        val src = when (hrSource) {
            "wear" -> "워치"
            "ble" -> "BLE"
            else -> when (deviceType) { "wear" -> "워치"; "ble" -> "BLE"; else -> "워치 없음" }
        }
        return src
    }

    fun runningSubtitle(s: SessionEntity): String {
        val parts = ArrayList<String>()
        parts.add(deviceLabel(s.deviceType, s.hrSource))
        s.distanceMeters?.takeIf { it > 0f }?.let { parts.add(distance(it)) }
        s.cadenceAvg?.takeIf { it > 0 }?.let { parts.add("케이던스 ${it} SPM") }
        s.coachAlertCountTotal?.takeIf { it > 0 }?.let { parts.add("코치 ${it}회") }
        return parts.joinToString("  ·  ")
    }

    fun meditationSubtitle(s: SessionEntity): String {
        val parts = ArrayList<String>()
        parts.add(deviceLabel(s.deviceType, s.hrSource))
        s.breathPreset?.let { parts.add(breathPresetLabel(it)) }
        if (s.noiseUsed == true) parts.add("노이즈")
        if (s.binauralEnabled == true) parts.add("40Hz")
        if (s.focusModeUsed == true) parts.add("몰입")
        return parts.joinToString("  ·  ")
    }

    fun golfSubtitle(s: SessionEntity): String {
        val parts = ArrayList<String>()
        s.selectedClubPreset?.let { parts.add(golfClubLabel(it)) }
        s.impactBeatCount?.takeIf { it > 0 }?.let { parts.add("임팩트 ${it}회") }
        if (s.flashLightEnabled == true) parts.add("플래시")
        if (s.flashScreenEnabled == true) parts.add("화면 깜빡")
        return parts.joinToString("  ·  ")
    }

    fun breathPresetLabel(p: String): String = when (p) {
        "box" -> "박스 호흡"
        "478" -> "4-7-8 호흡"
        "coherent" -> "Coherent 호흡"
        "long_exhale" -> "긴 날숨"
        "custom" -> "커스텀 호흡"
        else -> p
    }

    fun breathAudioModeLabel(m: String?): String = when (m) {
        "phase" -> "단계만"
        "count" -> "초 카운트"
        "silent" -> "무음"
        else -> "--"
    }

    fun golfClubLabel(c: String): String = when (c) {
        "driver" -> "드라이버"
        "iron" -> "아이언"
        "wedge" -> "웨지"
        "putter" -> "퍼터"
        else -> c
    }
}
