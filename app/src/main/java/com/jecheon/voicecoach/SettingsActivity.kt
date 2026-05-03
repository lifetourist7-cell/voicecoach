package com.jecheon.voicecoach

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private var hrService: HRForegroundService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            hrService = (binder as HRForegroundService.LocalBinder).getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            hrService = null
            isBound = false
        }
    }

    /** 시스템 폰트 스케일 무시 — 항상 1.0x 로 렌더링. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withFixedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyEdgeToEdge()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "설정"

        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)

        val seekTts = findViewById<SeekBar>(R.id.seekTtsVolume)
        val tvTts = findViewById<TextView>(R.id.tvTtsVolume)
        val seekMetro = findViewById<SeekBar>(R.id.seekMetronomeVolume)
        val tvMetro = findViewById<TextView>(R.id.tvMetronomeVolume)
        val seekPace = findViewById<SeekBar>(R.id.seekPaceWindow)
        val tvPace = findViewById<TextView>(R.id.tvPaceWindow)

        val ttsInit = prefs.getInt("tts_volume", 100)
        val metroInit = prefs.getInt("metronome_volume", 100)
        val paceWindowInit = prefs.getInt("pace_avg_window_sec", 3).coerceIn(1, 5)

        seekTts.progress = ttsInit
        tvTts.text = "$ttsInit%"
        seekMetro.progress = metroInit
        tvMetro.text = "$metroInit%"
        seekPace.progress = paceWindowInit - 1
        tvPace.text = "${paceWindowInit}초"

        seekTts.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTts.text = "$progress%"
                prefs.edit().putInt("tts_volume", progress).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // 슬라이더 떼면 service 의 audio focus / 음악 볼륨 부스트 즉시 갱신
                hrService?.refreshTtsVolume()
            }
        })

        seekMetro.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMetro.text = "$progress%"
                prefs.edit().putInt("metronome_volume", progress).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // 슬라이더 놓는 순간 현재 실행 중인 메트로놈에 즉시 반영
                hrService?.refreshMetronomeVolume()
            }
        })

        seekPace.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + 1
                tvPace.text = "${seconds}초"
                prefs.edit().putInt("pace_avg_window_sec", seconds).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── TTS 라벨 토글 (각 metric 별 "심박수 165" vs "165") ──
        val cbLabelHr = findViewById<CheckBox>(R.id.cbTtsLabelHr)
        val cbLabelPace = findViewById<CheckBox>(R.id.cbTtsLabelPace)
        val cbLabelCadence = findViewById<CheckBox>(R.id.cbTtsLabelCadence)
        cbLabelHr.isChecked = prefs.getBoolean("tts_label_hr", false)
        cbLabelPace.isChecked = prefs.getBoolean("tts_label_pace", false)
        cbLabelCadence.isChecked = prefs.getBoolean("tts_label_cadence", false)
        cbLabelHr.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("tts_label_hr", checked).apply()
        }
        cbLabelPace.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("tts_label_pace", checked).apply()
        }
        cbLabelCadence.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("tts_label_cadence", checked).apply()
        }

        // ── 고급: 골프 메트로놈 모드 활성화 ──
        // Off = 메인 화면 모드 탭에서 골프 숨김 (HR 기반 wellness 코치로 포지셔닝)
        // On = 골프 탭 표시 (스윙 템포 도구가 필요한 경우)
        val cbGolfMode = findViewById<CheckBox>(R.id.cbGolfMode)
        cbGolfMode.isChecked = prefs.getBoolean("golf_mode_enabled", false)
        cbGolfMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("golf_mode_enabled", checked).apply()
        }

        // 메트로놈 사운드 선택 (비프 / 우드블록)
        val radioSound = findViewById<RadioGroup>(R.id.radioMetroSound)
        val currentSound = prefs.getString("metronome_sound", "beep")
        radioSound.check(if (currentSound == "wood") R.id.rbMetroWood else R.id.rbMetroBeep)
        radioSound.setOnCheckedChangeListener { _, checkedId ->
            val value = if (checkedId == R.id.rbMetroWood) "wood" else "beep"
            prefs.edit().putString("metronome_sound", value).apply()
        }

        // ── Meditation: 집중모드 N분 후 자동 음소거 ──
        val cbFocusAutoMute = findViewById<android.widget.CheckBox>(R.id.cbFocusAutoMute)
        val focusMuteRow = findViewById<android.view.View>(R.id.focusMuteRow)
        val etFocusMuteMin = findViewById<android.widget.EditText>(R.id.etFocusMuteMin)
        val btnFocusMuteMinus = findViewById<android.widget.Button>(R.id.btnFocusMuteMinus)
        val btnFocusMutePlus = findViewById<android.widget.Button>(R.id.btnFocusMutePlus)

        val focusMuteEnabled = prefs.getBoolean("meditation_focus_mute_enabled", false)
        val focusMuteMin = prefs.getInt("meditation_focus_mute_min", 15).coerceIn(1, 120)
        cbFocusAutoMute.isChecked = focusMuteEnabled
        focusMuteRow.alpha = if (focusMuteEnabled) 1.0f else 0.4f
        focusMuteRow.isEnabled = focusMuteEnabled
        etFocusMuteMin.setText(focusMuteMin.toString())

        cbFocusAutoMute.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("meditation_focus_mute_enabled", checked).apply()
            focusMuteRow.alpha = if (checked) 1.0f else 0.4f
            focusMuteRow.isEnabled = checked
            btnFocusMuteMinus.isEnabled = checked
            btnFocusMutePlus.isEnabled = checked
            etFocusMuteMin.isEnabled = checked
        }

        fun saveFocusMuteMin() {
            val v = etFocusMuteMin.text.toString().toIntOrNull()?.coerceIn(1, 120) ?: return
            prefs.edit().putInt("meditation_focus_mute_min", v).apply()
        }
        btnFocusMuteMinus.setOnClickListener {
            val cur = etFocusMuteMin.text.toString().toIntOrNull() ?: 15
            etFocusMuteMin.setText((cur - 1).coerceAtLeast(1).toString())
            saveFocusMuteMin()
        }
        btnFocusMutePlus.setOnClickListener {
            val cur = etFocusMuteMin.text.toString().toIntOrNull() ?: 15
            etFocusMuteMin.setText((cur + 1).coerceAtMost(120).toString())
            saveFocusMuteMin()
        }
        etFocusMuteMin.setOnFocusChangeListener { _, focused -> if (!focused) saveFocusMuteMin() }
        // 초기 활성/비활성 상태 반영
        btnFocusMuteMinus.isEnabled = focusMuteEnabled
        btnFocusMutePlus.isEnabled = focusMuteEnabled
        etFocusMuteMin.isEnabled = focusMuteEnabled
    }

    override fun onStart() {
        super.onStart()
        // 서비스가 이미 돌고 있을 때만 바인드 (설정 화면이 서비스를 새로 시작시키면 안됨)
        try {
            bindService(Intent(this, HRForegroundService::class.java), serviceConnection, 0)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
