package supercurio.eucalarm.feedback

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.collect
import supercurio.eucalarm.data.WheelData

class Alert(private val context: Context, private val wheelData: WheelData) {

    private val audioManager = context.getSystemService<AudioManager>()!!
    private val vibrator = context.getSystemService<Vibrator>()!!

    suspend fun setup() {
        val lowLatency = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val audioPro = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)


        val outputSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val outputFramesPerBuffer =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)

        val stream = AudioManager.STREAM_MUSIC

        Log.i(
            TAG,
            "lowLatency: $lowLatency, " +
                    "audioPro: $audioPro, " +
                    "outputSampleRate: $outputSampleRate, " +
                    "outputFramesPerBuffer: $outputFramesPerBuffer"
        )

        wheelData.beeper.collect {
            when (it) {
                true -> play()
                false -> stop()
            }
        }
    }

    private fun play() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 55, 20),
                    0
                )
            )
        } else {
            vibrator.vibrate(200)
        }

    }

    private fun stop() {
        vibrator.cancel()
    }

    companion object {
        private const val TAG = "Alert"
    }

}