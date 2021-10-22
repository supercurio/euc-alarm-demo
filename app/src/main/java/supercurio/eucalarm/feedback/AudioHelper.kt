package supercurio.eucalarm.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sin

object AudioHelper {

    private val audioAttributes
        get() = AudioAttributes.Builder()
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

    private val audioFormat
        get() = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

    fun setupAlertTrack(): AudioTrack {

        val track = AudioTrack.Builder()
            .setAudioFormat(audioFormat)
            .setAudioAttributes(audioAttributes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(ALERT_TRACK_FRAMES * Short.SIZE_BYTES)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            .build()

        Log.i(TAG, "Got Track samplerate: ${track.sampleRate}")
        val bufferSizeInFrames = track.bufferSizeInFrames

        Log.i(TAG, "buffer size in frames: $bufferSizeInFrames")

        val envelope = generateEnvelope(0.01, 0.5, 0.53)

        val buf1 = generateAudioBuffer(
            sampleRate = track.sampleRate,
            frequency =
            ALERT_FREQUENCY1,
            gain = 0.8,
            len = bufferSizeInFrames,
            envelope = envelope
        )
        val buf2 = generateAudioBuffer(
            sampleRate = track.sampleRate,
            frequency =
            ALERT_FREQUENCY2,
            gain = 0.2,
            len = bufferSizeInFrames,
            envelope = envelope
        )
        val buf = mix(buf1, buf2)
        track.write(buf, 0, buf.size)

        return track
    }

    fun setupKeepAliveTrack(): AudioTrack {
        val frames = 1024

        val track = AudioTrack.Builder()
            .setAudioFormat(audioFormat)
            .setAudioAttributes(audioAttributes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * Short.SIZE_BYTES)
            .build()

        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames - 1, -1)

        track.play()

        return track
    }

    fun setupConnectionLossTrack(): AudioTrack {
        val frames = CONNECTION_LOSS_TRACK_FRAMES

        val track = AudioTrack.Builder()
            .setAudioFormat(audioFormat)
            .setAudioAttributes(audioAttributes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * Short.SIZE_BYTES)
            .build()

        val buf1 = generateAudioBuffer(
            sampleRate = track.sampleRate,
            frequency = CONNECTION_LOSS_FREQUENCY1,
            gain = 0.7,
            len = frames,
            envelope = generateEnvelope(0.05, 0.5)
        )
        val buf2 = generateAudioBuffer(
            sampleRate = track.sampleRate,
            frequency = CONNECTION_LOSS_FREQUENCY2,
            gain = 0.3,
            len = frames,
            envelope = generateEnvelope(0.4, 0.6)
        )
        val buffer = mix(buf1, buf2)
        track.write(buffer, 0, frames)

        return track
    }

    private fun generateAudioBuffer(
        sampleRate: Int,
        frequency: Double,
        gain: Double,
        len: Int,
        envelope: (size: Int, index: Int) -> Double,
    ): FloatArray {
        val buf = FloatArray(len)
        val twoPi = 8.0 * atan(1.0)
        var phase = 0.0

        for (i in 0 until len) {
            val amplitude = envelope(len, i)
            buf[i] = (sin(phase) * amplitude * gain).toFloat()
            phase += twoPi * frequency / sampleRate

            if (phase > twoPi) phase -= twoPi
        }

        return buf
    }

    private fun mix(vararg buffers: FloatArray): ShortArray {
        val out = ShortArray(buffers[0].size)

        buffers.forEach { buffer ->
            buffer.forEachIndexed { index, value ->
                out[index] =
                    (out[index] + (value * Short.MAX_VALUE).roundToInt()).toShort()
            }
        }

        return out
    }

    private fun transition(a: Double, b: Double, value: Double): Double {
        return (value - a) / (b - a)
    }

    private fun generateEnvelope(fadeIn: Double, fadeOut: Double, end: Double = 1.0) =
        fun(len: Int, i: Int): Double {
            val last = len - 1
            return when {
                i < (last * fadeIn) -> transition(0.0, last * fadeIn, i.toDouble() - 1) // fade in
                i > (last * fadeOut) -> (1 - transition(last * fadeOut, last * end, i.toDouble()))
                    .coerceAtLeast(0.0) // fade out
                else -> 1.0
            }
        }

    private const val TAG = "AudioHelper"

    const val ALERT_TRACK_FRAMES = 1024 * 5
    private const val CONNECTION_LOSS_TRACK_FRAMES = 1024 * 6

    private const val ALERT_FREQUENCY1 = 1046.5022612023945
    private const val ALERT_FREQUENCY2 = 523.2511306011972

    private const val CONNECTION_LOSS_FREQUENCY1 = 329.6275569128699
    private const val CONNECTION_LOSS_FREQUENCY2 = 440.0

}
