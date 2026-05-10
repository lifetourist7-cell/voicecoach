package com.jecheon.voicecoach.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Wear OS 컴패니언 메인 화면.
 *
 * Lifecycle 연동:
 *   - 폰에서 /start_hr_sender 가 와 PhoneCommandListenerService 가 HrSenderService 를 시작했을 수 있음.
 *   - 사용자가 워치 앱을 켜면 onStart 에서 HrSenderService.isRunning 검사.
 *   - 이미 동작 중이면 service 에 bind 해서 lastBpm / 가용성 즉시 UI 반영, 토글 ON 으로 강제.
 *   - 토글을 프로그래밍 방식으로 set 할 때 onCheckedChange listener 가 startSendingService 를
 *     중복 호출하지 않도록 [suppressToggleListener] 게이트 사용.
 *
 * 권한 흐름:
 *   - BODY_SENSORS 미부여 → 사용자가 토글 누르면 권한 요청 → 허용 시 알림 권한도 요청 → 서비스 시작.
 *   - 폰 명령으로 시작된 서비스가 권한 부재로 stopSelf 했어도 isRunning=false 라 UI 도 OFF 표시.
 */
class WearMainActivity : AppCompatActivity() {

    private lateinit var btnToggle: ToggleButton
    private lateinit var tvBpm: TextView
    private lateinit var tvStatus: TextView

    private var hrService: HrSenderService? = null
    private var isBound = false

    /**
     * onCreate / onStart 에서 토글 상태를 프로그래밍 방식으로 동기화할 때
     * onCheckedChange listener 의 startSendingService / stopSendingService 가 중복 발화하지
     * 않도록 차단. set 직전 true → set → false.
     */
    private var suppressToggleListener = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            hrService = (binder as HrSenderService.LocalBinder).getService()
            isBound = true
            hrService?.setListener(bpmListener)
            // bind 직후 마지막 알려진 BPM / 가용성 UI 즉시 반영
            hrService?.let { svc ->
                if (svc.lastBpm > 0) tvBpm.text = svc.lastBpm.toString()
                tvStatus.text = if (svc.lastAvailable)
                    getString(R.string.wear_status_sending)
                else
                    getString(R.string.wear_status_starting)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            hrService = null
            isBound = false
        }
    }

    private val bpmListener = object : HrSenderService.BpmListener {
        override fun onBpm(bpm: Int) {
            runOnUiThread {
                tvBpm.text = bpm.toString()
                tvStatus.text = getString(R.string.wear_status_sending)
            }
        }
        override fun onAvailabilityChanged(available: Boolean) {
            runOnUiThread {
                tvStatus.text = if (available) getString(R.string.wear_status_sending)
                else getString(R.string.wear_status_unavailable)
            }
        }
    }

    private val bodySensorsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 알림 권한도 필요 (Android 13+)
            requestNotifPermissionThenStart()
        } else {
            setToggleSilently(false)
            tvStatus.text = getString(R.string.wear_status_perm_denied)
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // notification 거부돼도 service 자체는 시작 (알림이 안 보일 뿐)
        startSendingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnToggle = findViewById(R.id.btnToggle)
        tvBpm = findViewById(R.id.tvBpm)
        tvStatus = findViewById(R.id.tvStatus)

        btnToggle.setOnCheckedChangeListener { _, checked ->
            if (suppressToggleListener) return@setOnCheckedChangeListener
            if (checked) {
                if (hasBodySensorsPermission()) {
                    requestNotifPermissionThenStart()
                } else {
                    bodySensorsLauncher.launch(Manifest.permission.BODY_SENSORS)
                }
            } else {
                stopSendingService()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 폰에서 원격 시작 (또는 이전 세션) 으로 서비스가 이미 살아있으면 UI 동기화.
        if (HrSenderService.isRunning) {
            // 토글 ON 강제 — listener 차단해서 startSendingService 중복 호출 방지
            setToggleSilently(true)
            tvStatus.text = getString(R.string.wear_status_starting)
            // 별도 ContextCompat.startForegroundService 는 호출하지 않음 — 이미 시작됨.
            // bind 만 추가로 걸어서 BPM listener 연결.
            if (!isBound) {
                val intent = Intent(this, HrSenderService::class.java)
                try {
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                } catch (_: SecurityException) {
                    // 매우 드문 경우 — bind 권한 거부. 무시.
                }
            }
        } else {
            // 서비스 안 살아있는데 토글이 ON 으로 잘못 남아있으면 OFF 로 정리
            if (btnToggle.isChecked) setToggleSilently(false)
            tvStatus.text = getString(R.string.wear_status_idle)
        }
    }

    /** Listener 차단하면서 토글 상태만 변경. UI 동기화 전용. */
    private fun setToggleSilently(checked: Boolean) {
        suppressToggleListener = true
        try {
            btnToggle.isChecked = checked
        } finally {
            suppressToggleListener = false
        }
    }

    private fun hasBodySensorsPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BODY_SENSORS
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestNotifPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startSendingService()
            else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSendingService()
        }
    }

    private fun startSendingService() {
        val intent = Intent(this, HrSenderService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        tvStatus.text = getString(R.string.wear_status_starting)
    }

    private fun stopSendingService() {
        try { hrService?.setListener(null) } catch (_: Exception) {}
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
        stopService(Intent(this, HrSenderService::class.java))
        hrService = null
        tvBpm.text = "--"
        tvStatus.text = getString(R.string.wear_status_idle)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
    }
}
