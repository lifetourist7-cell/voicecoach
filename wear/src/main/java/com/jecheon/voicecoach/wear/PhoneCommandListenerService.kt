package com.jecheon.voicecoach.wear

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * 폰 앱이 보낸 명령 수신.
 *
 * - `/start_hr_sender` → [HrSenderService] foreground 시작
 * - `/stop_hr_sender`  → [HrSenderService] 정지
 *
 * 권한이 없으면 Service 측에서 알림으로 안내 (Service 가 직접 권한 다이얼로그 못 띄우는 제약).
 */
class PhoneCommandListenerService : WearableListenerService() {

    companion object {
        const val PATH_START = "/start_hr_sender"
        const val PATH_STOP = "/stop_hr_sender"
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_START -> {
                try {
                    ContextCompat.startForegroundService(
                        this, Intent(this, HrSenderService::class.java)
                    )
                } catch (_: Exception) { /* OS 가 백그라운드 차단 */ }
            }
            PATH_STOP -> {
                try { stopService(Intent(this, HrSenderService::class.java)) }
                catch (_: Exception) {}
            }
        }
    }
}
