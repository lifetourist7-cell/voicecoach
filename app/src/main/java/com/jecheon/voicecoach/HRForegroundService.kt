package com.jecheon.voicecoach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.os.Bundle
import android.os.HandlerThread
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.util.Locale
import java.util.UUID

class HRForegroundService : Service() {

    private val binder = LocalBinder()
    private var tts: TextToSpeech? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var currentHR = 0
    private var currentPace = ""
    private var currentPaceMin = 0
    private var currentPaceSec = 0
    private var callback: ((Int, String, String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var metronomeHandler: Handler? = null
    private var metronomeThread: HandlerThread? = null
    private var ttsInterval = 10000L
    private var metronomeRunnable: Runnable? = null
    private var toneGenerator: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ttsRunnable: Runnable? = null
    private var lastHRTimestampMs: Long = 0L
    private var disconnectAnnounced: Boolean = false
    private val speedBuffer = ArrayDeque<Pair<Long, Float>>()
    private val paceAvgWindowMs = 3000L
    private val maxAccuracyMeters = 15f

    private var hrEnabled = true
    private var paceEnabled = false
    private var selectedLocale = Locale.KOREAN

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val hrServiceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val hrCharacteristicUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    inner class LocalBinder : Binder() {
        fun getService(): HRForegroundService = this@HRForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        acquireWakeLock()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        initTTS()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceCoach::HRServiceWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun setCallback(cb: (Int, String, String) -> Unit) {
        callback = cb
    }

    fun setOptions(hr: Boolean, pace: Boolean, intervalSeconds: Int, locale: Locale = Locale.KOREAN) {
        hrEnabled = hr
        paceEnabled = pace
        ttsInterval = intervalSeconds * 1000L
        selectedLocale = locale
        tts?.language = locale
        handler.removeCallbacksAndMessages(null)
        if (paceEnabled) startLocationUpdates()
        if (currentHR > 0) startTTSLoop()
    }

    fun startMetronome(bpm: Int) {
        stopMetronome()
        if (bpm <= 0 || bpm > 400) return

        val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
        val volume = prefs.getInt("metronome_volume", 100).coerceIn(0, 100)

        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, volume)
        } catch (e: RuntimeException) {
            null
        }

        val thread = HandlerThread("MetronomeThread").apply { start() }
        metronomeThread = thread
        val h = Handler(thread.looper)
        metronomeHandler = h

        val intervalMs = (60000.0 / bpm).toLong()
        val startTimeNs = System.nanoTime()
        var beatCount = 0L
        val r = object : Runnable {
            override fun run() {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                beatCount++
                val nextTargetMs = beatCount * intervalMs
                val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000
                val delay = (nextTargetMs - elapsedMs).coerceAtLeast(0L)
                h.postDelayed(this, delay)
            }
        }
        metronomeRunnable = r
        h.post(r)
    }

    fun stopMetronome() {
        metronomeRunnable?.let { r -> metronomeHandler?.removeCallbacks(r) }
        metronomeRunnable = null
        metronomeThread?.quitSafely()
        metronomeThread = null
        metronomeHandler = null
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = selectedLocale
                startBLEScan()
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            callback?.invoke(currentHR, currentPace, "위치 권한 필요")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            // 3번: GPS 정확도 필터 — accuracy > 15m 인 샘플은 무시
            if (location.hasAccuracy() && location.accuracy > maxAccuracyMeters) return

            val speedMs = location.speed
            val now = SystemClock.elapsedRealtime()

            // 3초 이동 평균 버퍼 관리
            speedBuffer.addLast(now to speedMs)
            while (speedBuffer.isNotEmpty() && now - speedBuffer.first().first > paceAvgWindowMs) {
                speedBuffer.removeFirst()
            }

            val avgSpeedMs = if (speedBuffer.isNotEmpty()) {
                speedBuffer.map { it.second }.average().toFloat()
            } else 0f

            val minSpeedMs = 0.83f

            if (avgSpeedMs > minSpeedMs) {
                val paceSecondsPerKm = 1000.0 / avgSpeedMs
                if (paceSecondsPerKm < 1200) {
                    currentPaceMin = (paceSecondsPerKm / 60).toInt()
                    currentPaceSec = (paceSecondsPerKm % 60).toInt()
                    currentPace = "${currentPaceMin}'${currentPaceSec.toString().padStart(2, '0')}\""
                    callback?.invoke(currentHR, currentPace, "수신 중")
                }
            } else {
                currentPace = ""
                currentPaceMin = 0
                currentPaceSec = 0
            }
        }
    }

    private fun startBLEScan() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            callback?.invoke(0, currentPace, "블루투스가 꺼져 있습니다. 켠 뒤 다시 시도합니다...")
            handler.postDelayed({ startBLEScan() }, 5000)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            callback?.invoke(0, currentPace, "BLE 스캐너 사용 불가. 5초 후 재시도...")
            handler.postDelayed({ startBLEScan() }, 5000)
            return
        }

        callback?.invoke(0, currentPace, "가민 워치 검색 중...")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(hrServiceUuid))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            callback?.invoke(0, currentPace, "블루투스 권한 필요")
        } catch (e: IllegalStateException) {
            callback?.invoke(0, currentPace, "스캔 시작 실패. 5초 후 재시도...")
            handler.postDelayed({ startBLEScan() }, 5000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            callback?.invoke(0, currentPace, "워치 발견! 연결 중...")
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            try {
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(this)
            } catch (e: SecurityException) { }
            try {
                device.connectGatt(this@HRForegroundService, false, gattCallback)
            } catch (e: SecurityException) {
                callback?.invoke(0, currentPace, "블루투스 권한 필요")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "스캔 이미 진행 중"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "앱 등록 실패"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "기기 BLE 미지원"
                SCAN_FAILED_INTERNAL_ERROR -> "내부 오류"
                else -> "오류 코드 $errorCode"
            }
            callback?.invoke(0, currentPace, "스캔 실패($reason). 5초 후 재시도...")
            handler.postDelayed({ startBLEScan() }, 5000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) { }
                callback?.invoke(0, currentPace, "연결됨! 서비스 탐색 중...")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                currentHR = 0
                callback?.invoke(0, currentPace, "연결 끊김. 재연결 중...")
                try {
                    gatt.close()
                } catch (e: SecurityException) { }
                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }
                handler.postDelayed({ startBLEScan() }, 3000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hrService = gatt.getService(hrServiceUuid) ?: return
            val hrChar = hrService.getCharacteristic(hrCharacteristicUuid) ?: return
            try {
                gatt.setCharacteristicNotification(hrChar, true)
                val descriptor = hrChar.getDescriptor(cccdUuid)
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } catch (e: SecurityException) { }
            callback?.invoke(0, currentPace, "심박수 수신 시작!")
            startTTSLoop()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val flag = characteristic.properties
            val hr = if (flag and 0x01 != 0) {
                characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 1) ?: 0
            } else {
                characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1) ?: 0
            }
            currentHR = hr
            lastHRTimestampMs = SystemClock.elapsedRealtime()
            disconnectAnnounced = false
            callback?.invoke(hr, currentPace, "수신 중")
        }
    }

    private fun ttsParams(): Bundle {
        val prefs = getSharedPreferences("voicecoach_settings", MODE_PRIVATE)
        val volPct = prefs.getInt("tts_volume", 100).coerceIn(0, 100)
        return Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volPct / 100f)
        }
    }

    private fun startTTSLoop() {
        stopTTSLoop()
        val r = object : Runnable {
            override fun run() {
                val params = ttsParams()
                val now = SystemClock.elapsedRealtime()
                val hadHRBefore = lastHRTimestampMs > 0L
                val hrStale = !hadHRBefore || (now - lastHRTimestampMs) > 5000L
                val hrValid = currentHR > 0 && !hrStale

                var spokeSomething = false

                if (hrEnabled) {
                    if (hrValid) {
                        tts?.speak("$currentHR", TextToSpeech.QUEUE_FLUSH, params, "hr")
                        spokeSomething = true
                    } else if (hadHRBefore && !disconnectAnnounced) {
                        val msg = if (selectedLocale.language == "en") "Heart rate signal lost" else "연결이 끊어졌습니다"
                        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, params, "disconnect")
                        disconnectAnnounced = true
                        spokeSomething = true
                    }
                }

                if (paceEnabled && currentPaceMin > 0) {
                    val paceText = if (selectedLocale.language == "en") "$currentPaceMin minutes $currentPaceSec seconds" else "${currentPaceMin}분 ${currentPaceSec}초"
                    if (spokeSomething) {
                        tts?.playSilentUtterance(500, TextToSpeech.QUEUE_ADD, null)
                        tts?.speak(paceText, TextToSpeech.QUEUE_ADD, params, "pace")
                    } else {
                        tts?.speak(paceText, TextToSpeech.QUEUE_FLUSH, params, "pace")
                    }
                }

                handler.postDelayed(this, ttsInterval)
            }
        }
        ttsRunnable = r
        handler.post(r)
    }

    private fun stopTTSLoop() {
        ttsRunnable?.let { handler.removeCallbacks(it) }
        ttsRunnable = null
    }

    private fun startForegroundNotification() {
        val channelId = "hr_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "VoiceCoach", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VoiceCoach 실행 중")
            .setContentText("심박수 모니터링 중...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopMetronome()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: SecurityException) { }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        tts?.stop()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}