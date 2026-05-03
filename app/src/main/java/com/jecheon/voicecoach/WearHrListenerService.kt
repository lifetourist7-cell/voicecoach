package com.jecheon.voicecoach

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

/**
 * Wear OS 컴패니언 앱에서 보낸 HR 메시지를 받아 [HRForegroundService] 로 forward.
 *
 * - 메시지 path: `/hr_update`
 * - payload: 4바이트 BigEndian Int (BPM)
 * - 폰 앱 서비스가 시작되어 있지 않으면 startForegroundService 로 깨움
 * - 메시지 도착 빈도 = 1초/회 (워치 측 throttle)
 */
class WearHrListenerService : WearableListenerService() {

    companion object {
        private const val PATH_HR_UPDATE = "/hr_update"
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_HR_UPDATE) return
        if (event.data.size < 4) return
        val bpm = ByteBuffer.wrap(event.data).int
        if (bpm <= 0 || bpm > 250) return

        val intent = Intent(this, HRForegroundService::class.java).apply {
            action = HRForegroundService.ACTION_EXTERNAL_HR
            putExtra(HRForegroundService.EXTRA_BPM, bpm)
        }
        // 서비스가 안 떠있으면 foreground 로 시작 (사용자가 폰 앱 "시작" 안 눌렀을 가능성)
        // 단, foreground 로 시작 시 알림이 즉시 떠야 하는 제약이 있어 안전하게 startService 만 사용.
        // 사용자가 폰 앱에서 명시적으로 "시작" 누른 상태에서만 의미 있게 동작.
        try {
            startService(intent)
        } catch (_: IllegalStateException) {
            // 백그라운드에서 startService 가 차단된 경우 — 무시 (다음 메시지에 재시도)
        }
    }
}
