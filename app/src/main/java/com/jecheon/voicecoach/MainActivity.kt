package com.jecheon.voicecoach

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var hrService: HRForegroundService? = null
    private var isBound = false
    private lateinit var tvHeartRate: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnHR: ToggleButton
    private lateinit var btnPace: ToggleButton
    private lateinit var etInterval: EditText
    private lateinit var btnStart: Button
    private var isRunning = false
    private lateinit var spinnerLanguage: Spinner
    private lateinit var btnMetronome: ToggleButton
    private lateinit var etMetronomeBpm: EditText

    private val languages = listOf(
        Pair("한국어", Locale.KOREAN),
        Pair("English (US)", Locale.US),
        Pair("English (UK)", Locale.UK)
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as HRForegroundService.LocalBinder
            hrService = localBinder.getService()
            isBound = true

            // 시작 버튼 눌렀을 때의 모든 설정을 여기서 한꺼번에 적용
            val interval = etInterval.text.toString().toIntOrNull() ?: 10
            val hrEnabled = btnHR.isChecked
            val paceEnabled = btnPace.isChecked
            val selectedLocale = languages[spinnerLanguage.selectedItemPosition].second
            val metronomeEnabled = btnMetronome.isChecked
            val bpm = etMetronomeBpm.text.toString().toIntOrNull() ?: 160

            hrService?.setOptions(hrEnabled, paceEnabled, interval, selectedLocale)

            if (metronomeEnabled) {
                hrService?.startMetronome(bpm)
            } else {
                hrService?.stopMetronome()
            }

            hrService?.setCallback { hr: Int, pace: String, status: String ->
                runOnUiThread {
                    tvHeartRate.text = if (hr > 0) "$hr BPM" else "-- BPM"
                    tvPace.text = if (pace.isNotEmpty()) pace else "--'--\""
                    tvStatus.text = status
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            hrService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvPace = findViewById(R.id.tvPace)
        tvStatus = findViewById(R.id.tvStatus)
        btnHR = findViewById(R.id.btnHR)
        btnPace = findViewById(R.id.btnPace)
        etInterval = findViewById(R.id.etInterval)
        btnStart = findViewById(R.id.btnStart)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        btnMetronome = findViewById(R.id.btnMetronome)
        etMetronomeBpm = findViewById(R.id.etMetronomeBpm)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        btnStart.setOnClickListener {
            if (isRunning) {
                stopHRService()
            } else {
                requestPermissionsAndStart()
            }
        }

        findViewById<Button>(R.id.btnIntervalMinus).setOnClickListener {
            val cur = etInterval.text.toString().toIntOrNull() ?: 3
            etInterval.setText((cur - 1).coerceAtLeast(1).toString())
        }
        findViewById<Button>(R.id.btnIntervalPlus).setOnClickListener {
            val cur = etInterval.text.toString().toIntOrNull() ?: 3
            etInterval.setText((cur + 1).toString())
        }
        findViewById<Button>(R.id.btnBpmMinus).setOnClickListener {
            val cur = etMetronomeBpm.text.toString().toIntOrNull() ?: 180
            etMetronomeBpm.setText((cur - 1).coerceAtLeast(1).toString())
        }
        findViewById<Button>(R.id.btnBpmPlus).setOnClickListener {
            val cur = etMetronomeBpm.text.toString().toIntOrNull() ?: 180
            etMetronomeBpm.setText((cur + 1).coerceAtMost(400).toString())
        }
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        btnStart.text = if (running) "중지" else "시작"
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startHRService()
        } else {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startHRService()
        } else {
            tvStatus.text = "권한이 필요합니다"
        }
    }

    private fun startHRService() {
        // 이미 바인딩된 상태면 먼저 해제하고 재시작
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            hrService = null
        }
        val intent = Intent(this, HRForegroundService::class.java)
        stopService(intent) // 기존 서비스 종료
        ContextCompat.startForegroundService(this, intent) // 새로 시작
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        tvStatus.text = "연결 중..."
        setRunningState(true)
    }

    private fun stopHRService() {
        hrService?.stopMetronome()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, HRForegroundService::class.java)
        stopService(intent)
        tvHeartRate.text = "-- BPM"
        tvPace.text = "--'--\""
        tvStatus.text = "중지됨"
        setRunningState(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
        }
    }
}