package com.jecheon.voicecoach

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 명상용 노이즈 생성기.
 *
 * - 화이트 노이즈 = 균등 분포 난수
 * - 컷오프 주파수 < ~10kHz 이면 1차 IIR 저역통과 필터로 brown/pink 영역 모사
 *   - 250Hz ≈ 브라운 (저음 강조)
 *   - 5000Hz ≈ 핑크 비슷
 *   - 10000Hz+ ≈ 풀 화이트
 *
 * 백그라운드 스레드에서 PCM short 버퍼를 계산해 [AudioTrack] 에 쓴다.
 * AudioTrack 의 buffer 가 항상 일정 수준 차 있으면 끊김 없이 재생.
 */
class NoisePlayer {

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_SAMPLES = 1024  // 한 번에 쓰는 샘플 수 (~23ms)
    }

    @Volatile private var running = false
    @Volatile private var cutoffHz: Float = 10_000f
    @Volatile private var volume: Float = 1.0f
    private var track: AudioTrack? = null
    private var producer: Thread? = null

    /** 시작. 이미 켜져 있으면 무시. cutoff 0~22kHz, volume 0~1. */
    @Synchronized
    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBuf, CHUNK_SAMPLES * 2 * 4)  // ~4 chunks 의 여유

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        running = true

        producer = thread(name = "NoisePlayer", isDaemon = true) {
            val buf = ShortArray(CHUNK_SAMPLES)
            // 1차 IIR 저역통과 — y[n] = y[n-1] + α * (x[n] - y[n-1])
            //   α = dt / (RC + dt), RC = 1 / (2π * fc)
            //   화이트 노이즈는 fc 가 매우 큼 → α 가 1 에 가까워 거의 통과
            //   브라운 노이즈는 fc 가 작음 → α 가 작아 강한 저역통과
            var lastFiltered = 0f
            // 브라운에서 random walk 누적이 폭주 안 하도록 약간의 leak (high-pass 1차)
            // 그래도 안전 위해 매 샘플 clamp.
            val rand = java.util.Random()

            try {
                while (running) {
                    val fc = cutoffHz.coerceIn(10f, 22_000f)
                    val dt = 1f / SAMPLE_RATE
                    val rc = 1f / (2f * PI.toFloat() * fc)
                    val alpha = dt / (rc + dt)

                    for (i in 0 until CHUNK_SAMPLES) {
                        // 화이트 노이즈 입력 (-1 ~ 1)
                        val white = rand.nextFloat() * 2f - 1f
                        // 저역통과 적용
                        lastFiltered += alpha * (white - lastFiltered)
                        // 저역 강조 시 진폭이 작아져 음량이 줄어듦 → 보상 게인
                        // (alpha 가 작을수록 출력 진폭 감소; 대략 1/sqrt(alpha) 비례)
                        val gain = if (alpha > 0f) min(8f, 1f / kotlin.math.sqrt(alpha)) else 1f
                        val s = (lastFiltered * gain * volume).coerceIn(-1f, 1f)
                        buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
                    }
                    if (running) t.write(buf, 0, CHUNK_SAMPLES)
                }
            } catch (_: Exception) {
                // 트랙이 release 된 후 write 호출되면 IllegalState 등 — 종료 신호로 간주
            }
        }
    }

    /** 컷오프 주파수 조정 (Hz). 10 ~ 22000. 재생 중이면 즉시 다음 chunk 부터 반영. */
    fun setCutoff(hz: Float) {
        cutoffHz = hz.coerceIn(10f, 22_000f)
    }

    /** 음량 0~1. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        track?.setVolume(volume)
    }

    fun isPlaying(): Boolean = running

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        producer?.interrupt()
        producer = null
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
