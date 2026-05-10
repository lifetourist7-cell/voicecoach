package com.jecheon.voicecoach

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 40Hz 포커스 비트 (binaural beat) 생성기.
 *
 * ## 원리
 * 좌/우 채널에 40Hz 차이가 나는 순음 (예: L 220Hz / R 260Hz) 을 출력한다.
 * 이어폰/헤드폰으로 들으면 청각 시스템에서 두 톤의 차주파수 (40Hz) 가 인지된다.
 * 스피커로 들으면 두 톤이 공기에서 섞여 binaural 효과 없음 — 살짝 코러스 같은 단일 음.
 *
 * ## 안전 / 경고
 * - 의학적 효과를 보장하지 않음 (실험적 보조 기능)
 * - 운전 중 / 주변 소리를 들어야 하는 상황에서 사용 금지
 * - 두통 / 어지러움 / 불편감 시 즉시 중지
 * - 신경학적 질환 (간질 등) 병력 있다면 전문가와 상담
 *
 * ## 1초 fade
 * - 시작: gain 0 → target 으로 1초 linear ramp
 * - 정지: target → 0 으로 1초 ramp 후 release
 * - strength 변경: 이전 → 새 gain 1초 ramp
 *
 * 시스템 미디어 볼륨은 변경하지 않음. AudioTrack hardware gain 1.0 고정.
 */
class BinauralBeatPlayer {

    enum class Strength { WEAK, MEDIUM, STRONG }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_FRAMES = 1024  // stereo 라 샘플 수는 frames * 2
        // L/R freq — 차주파수 40Hz, 캐리어는 mid-low (덜 거슬림)
        private const val LEFT_FREQ = 220.0
        private const val RIGHT_FREQ = 260.0  // 260 - 220 = 40Hz beat
        // 강도별 PCM gain (순음이라 작아도 충분)
        private const val GAIN_WEAK = 0.04f
        private const val GAIN_MEDIUM = 0.10f
        private const val GAIN_STRONG = 0.18f
        // 1초 linear ramp
        private const val GAIN_RAMP_PER_SAMPLE = 1f / SAMPLE_RATE
        private const val STOP_TAIL_MS = 150L

        private fun strengthToGain(s: Strength): Float = when (s) {
            Strength.WEAK -> GAIN_WEAK
            Strength.MEDIUM -> GAIN_MEDIUM
            Strength.STRONG -> GAIN_STRONG
        }
    }

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var targetGain: Float = GAIN_WEAK
    private var track: AudioTrack? = null
    private var producer: Thread? = null

    /** 시작. 이미 켜져 있으면 stopRequested 만 취소. */
    @Synchronized
    fun start() {
        if (running) {
            stopRequested = false
            return
        }
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // stereo: frames * 2 channels * 2 bytes
        val bufBytes = max(minBuf, CHUNK_FRAMES * 2 * 2 * 4)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(1.0f)
        t.play()
        running = true
        stopRequested = false

        producer = thread(name = "BinauralBeat", isDaemon = true) {
            // Stereo interleaved buffer — [L0, R0, L1, R1, ...].
            val buf = ShortArray(CHUNK_FRAMES * 2)

            // Phase tracking — chunk 경계에서 sine 이 끊기지 않게 누적.
            var leftPhase = 0.0
            var rightPhase = 0.0
            val leftPhaseInc = 2.0 * PI * LEFT_FREQ / SAMPLE_RATE
            val rightPhaseInc = 2.0 * PI * RIGHT_FREQ / SAMPLE_RATE

            // Fade 시작 — gain 0 부터 ramp.
            var currentGain = 0f

            try {
                outer@ while (running) {
                    val effectiveTarget = if (stopRequested) 0f else targetGain

                    for (frame in 0 until CHUNK_FRAMES) {
                        // Per-sample gain ramp.
                        val diff = effectiveTarget - currentGain
                        if (diff > GAIN_RAMP_PER_SAMPLE) currentGain += GAIN_RAMP_PER_SAMPLE
                        else if (diff < -GAIN_RAMP_PER_SAMPLE) currentGain -= GAIN_RAMP_PER_SAMPLE
                        else currentGain = effectiveTarget

                        val l = (sin(leftPhase) * currentGain).toFloat().coerceIn(-0.98f, 0.98f)
                        val r = (sin(rightPhase) * currentGain).toFloat().coerceIn(-0.98f, 0.98f)
                        leftPhase += leftPhaseInc
                        rightPhase += rightPhaseInc
                        // 2π 넘어가면 wrap (정밀도 유지) — 매번 안 해도 되지만 가끔.
                        if (leftPhase > 2 * PI) leftPhase -= 2 * PI
                        if (rightPhase > 2 * PI) rightPhase -= 2 * PI

                        buf[frame * 2] = (l * Short.MAX_VALUE).toInt().toShort()
                        buf[frame * 2 + 1] = (r * Short.MAX_VALUE).toInt().toShort()
                    }
                    if (running) t.write(buf, 0, buf.size)

                    if (stopRequested && currentGain <= 0.0001f) {
                        val tailChunks = (STOP_TAIL_MS * SAMPLE_RATE / 1000 / CHUNK_FRAMES).toInt()
                        java.util.Arrays.fill(buf, 0)
                        repeat(tailChunks) {
                            if (running) t.write(buf, 0, buf.size)
                        }
                        running = false
                        break@outer
                    }
                }
            } catch (_: Exception) {
                // AudioTrack 이 release 후 write 호출 → IllegalState — 정상 종료 신호.
            } finally {
                try { track?.pause() } catch (_: Exception) {}
                try { track?.flush() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                track = null
            }
        }
    }

    /** 강도 변경 — 1초 ramp 으로 부드럽게 적용. */
    fun setStrength(s: Strength) {
        targetGain = strengthToGain(s)
    }

    fun isPlaying(): Boolean = running && !stopRequested

    /**
     * 정지. 기본 1초 fade-out 후 자동 release.
     * @param immediate true 면 fade 없이 즉시 release.
     */
    @Synchronized
    fun stop(immediate: Boolean = false) {
        if (!running) return
        if (immediate) {
            running = false
            stopRequested = true
            producer?.interrupt()
            producer = null
            try { track?.pause() } catch (_: Exception) {}
            try { track?.flush() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            track = null
        } else {
            stopRequested = true
        }
    }
}
