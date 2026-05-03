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

class WearMainActivity : AppCompatActivity() {

    private lateinit var btnToggle: ToggleButton
    private lateinit var tvBpm: TextView
    private lateinit var tvStatus: TextView

    private var hrService: HrSenderService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            hrService = (binder as HrSenderService.LocalBinder).getService()
            isBound = true
            hrService?.setListener(bpmListener)
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
            btnToggle.isChecked = false
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
