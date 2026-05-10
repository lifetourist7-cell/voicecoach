package com.jecheon.voicecoach

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * 명상 / 집중용 노이즈 생성기.
 *
 * ## 두 가지 mode
 *
 * ### [Mode.WHITE]
 * 균등 분포 화이트 노이즈. 밝고 샤아 하는 전대역 hiss.
 * cutoff 무시 (고정 풀밴드). 운동/포커스 시 ambient 백그라운드용.
 *
 * ### [Mode.BROWN_TONED]
 * Brownian/red noise generator. 흔한 "유튜브 브라운노이즈"처럼 낮고 둥근
 * rumble 을 목표로 한다. 커스텀 값은 "톤 컨트롤"로 보고 내부에서 가청 저역대로 보정.
 *
 * ## 1초 적응형 fade / smoothing (NEW)
 *
 * - 시작: PCM gain 0 → target 으로 1초 linear ramp (시작 클릭음 / 압박감 방지)
 * - 정지: target → 0 으로 1초 ramp 후 자동 release (UI 는 즉시 return)
 * - 음량 변경: 이전 gain → 새 gain 1초 ramp
 * - 톤 변경 (cutoff): 이전 → 새 cutoff 약 1초 exponential smoothing (parameter glide)
 * - 모드 변경: discrete (즉시 적용; 필요 시 외부에서 stop→start 로 cross-fade 가능)
 *
 * 시스템 미디어 볼륨은 절대 건드리지 않음. AudioTrack hardware gain 도 1.0 고정 —
 * 모든 음량 변경은 PCM 샘플 레벨에서 이뤄짐.
 */
class NoisePlayer {

    enum class Mode {
        /** 균등 분포 white noise. cutoff 무시. */
        WHITE,
        /** Brownian integrator + 2-stage LP cascade. cutoff 가 LP 차단 주파수. */
        BROWN_TONED
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_SAMPLES = 1024  // 한 번에 쓰는 샘플 수 (~23ms)
        private const val WHITE_HEADROOM = 0.85f

        // Brownian integrator 튜닝
        private const val BROWN_STEP = 0.02f
        private const val BROWN_LEAK_DIV = 1.02f
        private const val BROWN_CORE_GAIN = 3.4f
        private const val BROWN_OUTPUT_GAIN = 1.15f
        private const val BROWN_DC_BLOCK = 0.9995f

        // 커스텀 슬라이더 → 실제 brown LP cutoff 매핑 (가청 저역대로 보정)
        private const val BROWN_CONTROL_MIN = 10f
        private const val BROWN_CONTROL_MAX = 10_000f
        private const val BROWN_EFFECTIVE_MIN_FC = 260f
        private const val BROWN_EFFECTIVE_MAX_FC = 5_500f

        // ── Fade / smoothing 시간 상수 ──
        // 1초 linear ramp (gain): 샘플당 변화량 = 1 / SAMPLE_RATE
        private const val GAIN_RAMP_PER_SAMPLE = 1f / SAMPLE_RATE
        // 1초 exponential smoothing (control): tau ≈ 1s, 청크당 alpha ≈ 0.023
        private const val CONTROL_SMOOTH_PER_CHUNK = 0.023f
        // fade-out 후 실제 stop 까지 추가 여유
        private const val STOP_TAIL_MS = 150L

        private fun sliderToTrackGain(v: Float): Float {
            val x = v.coerceIn(0f, 1f)
            return x * x  // 체감 음량 곡선 (perceptual)
        }

        private fun onePoleAlpha(fc: Float): Float {
            val clamped = fc.coerceIn(10f, 22_000f)
            val dt = 1f / SAMPLE_RATE
            val rc = 1f / (2f * PI.toFloat() * clamped)
            return dt / (rc + dt)
        }

        private fun brownEffectiveCutoff(controlHz: Float): Float {
            val c = controlHz.coerceIn(BROWN_CONTROL_MIN, BROWN_CONTROL_MAX)
            val t = (ln(c / BROWN_CONTROL_MIN) /
                ln(BROWN_CONTROL_MAX / BROWN_CONTROL_MIN)).coerceIn(0f, 1f)
            return (BROWN_EFFECTIVE_MIN_FC *
                exp(ln(BROWN_EFFECTIVE_MAX_FC / BROWN_EFFECTIVE_MIN_FC) * t))
        }
    }

    @Volatile private var running = false
    @Volatile private var stopRequested = false       // true 면 audio thread 가 fade-out 후 종료
    @Volatile private var cutoffHz: Float = 250f      // 외부 입력 (target)
    @Volatile private var targetGain: Float = 0f      // 외부 입력 — 0~1
    @Volatile private var mode: Mode = Mode.WHITE
    private var track: AudioTrack? = null
    private var producer: Thread? = null

    /** 모드 + 컷오프 + 음량 설정 후 시작. 이미 켜져 있으면 setVolume 등 호출로 갱신만. */
    @Synchronized
    fun start() {
        if (running) {
            // 이미 재생 중이면 stopRequested 만 취소 — fade-out 중이라면 다시 fade-in 으로 복귀
            stopRequested = false
            return
        }
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBuf, CHUNK_SAMPLES * 2 * 4)

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
        // hardware gain 은 1.0 고정 — 모든 fade/볼륨은 PCM 샘플에서 처리.
        t.setVolume(1.0f)
        t.play()
        running = true
        stopRequested = false

        producer = thread(name = "NoisePlayer", isDaemon = true) {
            val buf = ShortArray(CHUNK_SAMPLES)
            val rand = java.util.Random()

            // ── 필터 / 생성기 state ──
            var brownState = 0f
            var brownLp1 = 0f
            var brownLp2 = 0f
            var dcPrevIn = 0f
            var dcPrevOut = 0f

            // ── Fade / smoothing state ──
            // 시작 시 currentGain 은 0 — 1초 동안 ramp up.
            var currentGain = 0f
            // Control 은 시작값 = 외부 target 으로 초기화 (튐 방지).
            var currentControlHz = cutoffHz

            try {
                outer@ while (running) {
                    val currentMode = mode
                    // Control parameter exponential smoothing — 청크 단위.
                    currentControlHz += CONTROL_SMOOTH_PER_CHUNK *
                        (cutoffHz - currentControlHz)
                    val brownAlpha = onePoleAlpha(brownEffectiveCutoff(currentControlHz))

                    // Stop fade target — stopRequested 면 0, 아니면 외부 targetGain.
                    val effectiveTarget = if (stopRequested) 0f else targetGain

                    for (i in 0 until CHUNK_SAMPLES) {
                        // Per-sample gain ramp (linear, 1초당 1.0 step).
                        val diff = effectiveTarget - currentGain
                        if (diff > GAIN_RAMP_PER_SAMPLE) currentGain += GAIN_RAMP_PER_SAMPLE
                        else if (diff < -GAIN_RAMP_PER_SAMPLE) currentGain -= GAIN_RAMP_PER_SAMPLE
                        else currentGain = effectiveTarget

                        val white = rand.nextFloat() * 2f - 1f

                        val sample = when (currentMode) {
                            Mode.WHITE -> white * WHITE_HEADROOM
                            Mode.BROWN_TONED -> {
                                brownState = (brownState + BROWN_STEP * white) / BROWN_LEAK_DIV
                                val brownCore = brownState * BROWN_CORE_GAIN
                                brownLp1 += brownAlpha * (brownCore - brownLp1)
                                brownLp2 += brownAlpha * (brownLp1 - brownLp2)
                                val dcBlocked = brownLp2 - dcPrevIn + BROWN_DC_BLOCK * dcPrevOut
                                dcPrevIn = brownLp2
                                dcPrevOut = dcBlocked
                                dcBlocked * BROWN_OUTPUT_GAIN
                            }
                        }

                        val out = (sample * currentGain).coerceIn(-0.98f, 0.98f)
                        buf[i] = (out * Short.MAX_VALUE).toInt().toShort()
                    }
                    if (running) t.write(buf, 0, CHUNK_SAMPLES)

                    // Fade-out 완료 체크 — gain 이 0 에 도달했고 stopRequested 면 종료.
                    if (stopRequested && currentGain <= 0.0001f) {
                        // STOP_TAIL_MS 만큼 더 silence 재생해 audio buffer 가 비워질 시간 확보.
                        val tailChunks = (STOP_TAIL_MS * SAMPLE_RATE / 1000 / CHUNK_SAMPLES).toInt()
                        java.util.Arrays.fill(buf, 0)
                        repeat(tailChunks) {
                            if (running) t.write(buf, 0, CHUNK_SAMPLES)
                        }
                        running = false
                        break@outer
                    }
                }
            } catch (_: Exception) {
                // AudioTrack 이 release 후에도 write 호출되면 IllegalState — 종료 신호로 간주
            } finally {
                try { track?.pause() } catch (_: Exception) {}
                try { track?.flush() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                track = null
            }
        }
    }

    /** 모드 변경 — 다음 chunk (~23ms) 부터 적용. */
    fun setMode(m: Mode) {
        mode = m
    }

    /**
     * 톤 조정값 (Hz scale). BROWN_TONED 에서 사용 — 1초 동안 부드럽게 이전 값에서 새 값으로 glide.
     * WHITE 에서는 무시.
     */
    fun setCutoff(hz: Float) {
        cutoffHz = hz.coerceIn(10f, 22_000f)
    }

    /**
     * 음량 0~1. 1초 linear ramp 으로 부드럽게 수렴.
     * 시스템 미디어 볼륨은 변경되지 않음.
     */
    fun setVolume(v: Float) {
        targetGain = sliderToTrackGain(v)
    }

    fun isPlaying(): Boolean = running && !stopRequested

    /**
     * 정지. 기본은 1초 fade-out 후 자동 release (UI 는 즉시 return).
     * @param immediate true 면 fade 없이 즉시 release — Service.onDestroy 등에서 사용.
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
            // producer thread 가 fade-out + release 후 자체 종료
        }
    }
}
