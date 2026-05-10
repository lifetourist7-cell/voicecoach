package com.jecheon.voicecoach.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.SampleDataPoint
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

/**
 * 워치 측 HR 측정 + 폰으로 1초 단위 송신.
 *
 * - HealthServices `MeasureClient` 로 HEART_RATE_BPM 콜백 등록
 * - 콜백이 와도 1초당 1회로 throttle 후 페어링된 폰 노드에 `/hr_update` 메시지 전송
 * - Foreground service — 화면 꺼져도 측정/전송 유지
 */
class HrSenderService : Service() {

    companion object {
        const val MESSAGE_PATH_HR = "/hr_update"
        private const val NOTIF_CHANNEL_ID = "hr_sender"
        private const val NOTIF_CHANNEL_PERM = "hr_perm"
        private const val NOTIF_ID = 1
        private const val NOTIF_PERM_ID = 2
        private const val SEND_INTERVAL_MS = 1000L

        /**
         * 서비스가 실제 측정 중인지 단순 플래그.
         *
         * Wear OS UI (WearMainActivity) 가 onStart 마다 이 값 보고 토글/상태 동기화.
         * 폰에서 /start_hr_sender 로 원격 시작했다 사용자가 워치 앱 켰을 때 — UI 가
         * 기본 OFF 상태로 보이지 않고 "전송 중" 으로 시작.
         *
         * onCreate 에서 권한 통과 후 true, onDestroy 에서 false. @Volatile 로 cross-thread
         * 가시성 확보 (Activity 는 main thread, service callback 은 health-services thread).
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    /** Activity 가 bind 해 실시간 BPM 표시할 수 있도록 callback. */
    interface BpmListener {
        fun onBpm(bpm: Int)
        fun onAvailabilityChanged(available: Boolean)
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): HrSenderService = this@HrSenderService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private var bpmListener: BpmListener? = null
    fun setListener(l: BpmListener?) { bpmListener = l }

    private var lastSentMs: Long = 0L
    @Volatile var lastBpm: Int = 0
        private set
    /** 마지막으로 알려진 health-services 가용성. UI bind 시 즉시 표시용. */
    @Volatile var lastAvailable: Boolean = false
        private set

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: androidx.health.services.client.data.DeltaDataType<*, *>,
            availability: Availability
        ) {
            val available = availability is androidx.health.services.client.data.DataTypeAvailability &&
                availability == androidx.health.services.client.data.DataTypeAvailability.AVAILABLE
            lastAvailable = available
            bpmListener?.onAvailabilityChanged(available)
        }

        override fun onDataReceived(data: DataPointContainer) {
            val points = data.getData(DataType.HEART_RATE_BPM)
            val latest = points.lastOrNull() ?: return
            val bpm = (latest as SampleDataPoint<Double>).value.toInt()
            if (bpm <= 0) return
            lastBpm = bpm
            bpmListener?.onBpm(bpm)
            // 1초 throttle
            val now = SystemClock.elapsedRealtime()
            if (now - lastSentMs >= SEND_INTERVAL_MS) {
                lastSentMs = now
                sendBpmToPhone(bpm)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // BODY_SENSORS 권한 체크 — 없으면 측정 불가능. 사용자에게 알림으로 안내 후 종료.
        // (Service 는 직접 권한 다이얼로그 못 띄움 → 알림 PendingIntent 로 Activity 호출)
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            showPermissionNeededNotification()
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, buildNotification())

        val measureClient = HealthServices.getClient(this).measureClient
        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
            // 측정 callback 정상 등록 후에만 isRunning=true.
            // (등록 자체가 실패하면 곧바로 stopSelf 라 isRunning 은 false 유지)
            isRunning = true
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 사용자가 "정지" 누르면 명시적 stopSelf 로 종료
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            HealthServices.getClient(this).measureClient
                .unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
        } catch (_: Exception) {}
        bpmListener = null
    }

    private fun sendBpmToPhone(bpm: Int) {
        // 메인 thread 에서 Tasks.await 호출하면 ANR 위험 → 단발 thread
        Thread {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                val phoneNode = nodes.firstOrNull() ?: return@Thread
                val payload = ByteBuffer.allocate(4).putInt(bpm).array()
                Wearable.getMessageClient(this)
                    .sendMessage(phoneNode.id, MESSAGE_PATH_HR, payload)
            } catch (_: Exception) {
                // 폰이 sleep / 페어링 끊김 — 다음 비트에 재시도
            }
        }.start()
    }

    /**
     * 권한 부재 시 워치 화면에 알림 — 탭하면 [WearMainActivity] 가 열려 권한 다이얼로그 진행.
     */
    private fun showPermissionNeededNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_PERM, "VoiceCoach 권한 안내", NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }
        val intent = Intent(this, WearMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, intent, piFlags)

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_PERM)
            .setContentTitle("VoiceCoach")
            .setContentText("심박수 권한 허용이 필요합니다 — 탭하세요")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(NOTIF_PERM_ID, notif) } catch (_: SecurityException) {
            // POST_NOTIFICATIONS 권한도 없으면 알림 못 띄움. 이 경우 사용자가 워치 앱 직접 열어야 함.
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, "VoiceCoach 워치", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("VoiceCoach")
            .setContentText("심박수 전송 중")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
