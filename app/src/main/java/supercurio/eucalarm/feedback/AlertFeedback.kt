package supercurio.eucalarm.feedback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import supercurio.eucalarm.Notifications
import supercurio.eucalarm.ble.BleConnectionState
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.di.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sin

@Singleton
class AlertFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelDataStateFlows: WheelDataStateFlows,
    private val wheelConnection: WheelConnection,
    private val notifications: Notifications,
    private val scopesProvider: CoroutineScopeProvider,
) {

    /*
     * TODO:
     *  - Audio alert on disconnection
     */

    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null
    private var alertTrack: AudioTrack? = null
    private var keepAliveTrack: AudioTrack? = null

    private var tracksRunning = false
    private var isPlaying = false

    private var currentVibrationPattern: LongArray? = null

    private val scope get() = scopesProvider.appScope

    fun setup() {

        Log.i(TAG, "Setup instance")
        audioManager = context.getSystemService()!!
        vibrator = context.getSystemService()!!

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)

        Log.i(TAG, "Scope: $scope")
        scope.launch {
            wheelDataStateFlows.beeperFlow.collect {
                when (it) {
                    true -> playAlert()
                    false -> stopAlert()
                }
            }
        }

        scope.launch {
            var previousState = BleConnectionState.UNKNOWN
            wheelConnection.connectionStateFlow.collect { state ->
                // Handle connection loss
                val connectionLoss = when (state) {
                    BleConnectionState.DISCONNECTED_RECONNECTING -> true
                    BleConnectionState.CONNECTING ->
                        previousState == BleConnectionState.DISCONNECTED_RECONNECTING
                    else -> false
                }
                previousState = state
                if (connectionLoss) stopAlert()
                onConnectionLoss(connectionLoss)

                // Run Audio Tracks only if we should be connected to the wheel
                when (state) {
                    BleConnectionState.CONNECTED,
                    BleConnectionState.RECEIVING_DATA,
                    BleConnectionState.REPLAY,
                    BleConnectionState.CONNECTING,
                    BleConnectionState.DISCONNECTED_RECONNECTING -> runTracks()
                    else -> stopTracks()
                }
            }
        }

        // receive screen off events
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(screenOffReceiver, filter)

        logAudioInfo()
    }

    fun playAlert() {
        if (!tracksRunning) return
        isPlaying = true

        requestAudioFocus()

        if (VIBRATE) vibratePattern(alertVibrationPattern)

        alertTrack?.setVolume(0f)
        alertTrack?.stop()
        alertTrack?.setLoopPoints(0, BUFFER_FRAMES, -1)
        alertTrack?.setVolume(1f)
        alertTrack?.play()
        notifications.updateOngoing("Alert!")
        notifications.notifyAlert()
    }

    fun stopAlert() {
        if (!tracksRunning) return
        releaseAudioFocus()

        if (VIBRATE) stopVibration()
        alertTrack?.setVolume(0f)
        alertTrack?.pause()
        isPlaying = false
        notifications.rollbackOngoing()
    }

    fun toggle() {
        if (!isPlaying) playAlert() else stopAlert()
    }

    fun shutdown() {
        Log.i(TAG, "Shutdown")
        stopTracks()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        scope.cancel()
        notifications.cancelAlerts()
    }

    /**
     * private methods
     */

    private fun runTracks() {
        Log.i(TAG, "Run tracks")
        if (tracksRunning) return
        tracksRunning = true

        keepAliveTrack = setupKeepAliveTrack()
        alertTrack = setupAlertTrack()
    }

    private fun stopTracks() {
        Log.i(TAG, "Stop Tracks")
        if (!tracksRunning) return
        tracksRunning = false

        stopAlert()

        alertTrack?.pause()
        keepAliveTrack?.pause()

        alertTrack?.flush()
        keepAliveTrack?.flush()

        alertTrack?.stop()
        keepAliveTrack?.stop()

        alertTrack?.release()
        alertTrack = null
        keepAliveTrack?.release()
        keepAliveTrack = null
    }

    private fun requestAudioFocus() {
        val focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest
                .Builder(focusGain)
                .build()
            audioManager?.requestAudioFocus(request)
        } else {
            audioManager?.requestAudioFocus(
                afChangeListener,
                AudioManager.STREAM_MUSIC,
                focusGain
            )
        }
    }

    private fun releaseAudioFocus() {
        audioManager?.abandonAudioFocus(afChangeListener)
    }

    private fun reconfigureAudioTracks() {
        Log.i(TAG, "Reconfigure audio tracks for new audio output")
        stopTracks()
        runTracks()
    }

    private fun setupAlertTrack(): AudioTrack {
        Log.i(TAG, "setupAlertTrack")

        val audioAttributes = AudioAttributes.Builder()
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioFormat(audioFormat)
            .setAudioAttributes(audioAttributes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(BUFFER_FRAMES * Short.SIZE_BYTES)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            .build()

        Log.i(TAG, "Got Track samplerate: ${track.sampleRate}")
        val bufferSizeInFrames = track.bufferSizeInFrames

        Log.i(TAG, "buffer size in frames: $bufferSizeInFrames")

        val buf1 = getAudioBuffer(FREQUENCY1, 0.8, bufferSizeInFrames, track.sampleRate)
        val buf2 = getAudioBuffer(FREQUENCY2, 0.2, bufferSizeInFrames, track.sampleRate)
        val buf = mix(buf1, buf2)
        track.write(buf, 0, buf.size)

        return track
    }

    private fun setupKeepAliveTrack(): AudioTrack {
        Log.i(TAG, "setupKeepAliveTrack")
        val frames = 1024

        val audioAttributes = AudioAttributes.Builder()
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

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

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {

            addedDevices?.forEach {
                Log.i(TAG, "Added device ${audioDeviceInfoText(it)}")
                if (tracksRunning && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                    reconfigureAudioTracks()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            removedDevices?.forEach {
                Log.i(TAG, "Removed device device ${audioDeviceInfoText(it)}")
                if (tracksRunning && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                    reconfigureAudioTracks()
            }
        }
    }

    private fun onConnectionLoss(status: Boolean) {
        Log.i(TAG, "onConnectionLoss: $status")
        if (status)
            vibratePattern(connectionLossPattern)
        else
            stopVibration()
    }

    private fun vibratePattern(pattern: LongArray) {
        currentVibrationPattern = pattern
        vibrate()
    }

    private fun vibrate() = currentVibrationPattern?.let { pattern ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else
            vibrator?.vibrate(pattern, 0)
    }


    private fun stopVibration() {
        if (currentVibrationPattern != null) {
            currentVibrationPattern = null
            vibrator?.cancel()
        }
    }

    private fun logAudioInfo() {

        val lowLatency = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val audioPro = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)


        audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.forEach { info ->
                Log.i(TAG, audioDeviceInfoText(info))
            }

        Log.i(TAG, "lowLatency: $lowLatency, audioPro: $audioPro")
    }

    private fun getAudioBuffer(
        frequency: Double,
        multiplier: Double,
        len: Int,
        fs: Int
    ): FloatArray {
        val buf = FloatArray(len)
        val twoPi = 8.0 * atan(1.0)
        var phase = 0.0

        for (i in 0 until len) {
            val last = (len - 1).toDouble()
            val amplitude = when {
                // i < (len / 10) -> i / len.toDouble() * 10.0 // fade in
                // i > (len / 2) -> i / 2 // fade out and blank

                i < (last * 0.01) -> transition(0.0, last * 0.01, i.toDouble() - 1) // fade in
                i > (last * 0.5) -> (1 - transition(last * 0.5, last * 0.53, i.toDouble()))
                    .coerceAtLeast(0.0) // fade out

                else -> 1.0
            }

            buf[i] = (sin(phase) * amplitude * multiplier).toFloat()
            phase += twoPi * frequency / fs

            if (phase > twoPi) {
                phase -= twoPi
            }
        }

        return buf
    }

    fun mix(vararg buffers: FloatArray): ShortArray {
        val out = ShortArray(BUFFER_FRAMES)

        buffers.forEach { buffer ->
            buffer.forEachIndexed { index, value ->
                out[index] =
                    (out[index] + (value * Short.MAX_VALUE).roundToInt()).toShort()
            }
        }

        return out
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Screen turned off, resume active vibration")
            vibrate()
        }
    }

    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.i(TAG, "Focus change: $focusChange")
    }

    private fun audioDeviceInfoText(info: AudioDeviceInfo): String {
        val type = when (info.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "TYPE_BUILTIN_SPEAKER_SAFE"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
            AudioDeviceInfo.TYPE_BUS -> "TYPE_BUS"
            AudioDeviceInfo.TYPE_DOCK -> "TYPE_DOCK"
            AudioDeviceInfo.TYPE_FM -> "TYPE_FM"
            AudioDeviceInfo.TYPE_FM_TUNER -> "TYPE_FM_TUNER"
            AudioDeviceInfo.TYPE_HDMI -> "TYPE_HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "TYPE_HDMI_ARC"
            AudioDeviceInfo.TYPE_HEARING_AID -> "TYPE_HEARING_AID"
            AudioDeviceInfo.TYPE_IP -> "TYPE_IP"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "TYPE_LINE_ANALOG"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "TYPE_LINE_DIGITAL"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
            AudioDeviceInfo.TYPE_TV_TUNER -> "TYPE_TV_TUNER"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "TYPE_USB_ACCESSORY"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
            AudioDeviceInfo.TYPE_UNKNOWN -> "TYPE_UNKNOWN"
            else -> "Unsupported (${info.type})"
        }
        return "productName: ${info.productName}, type: $type, " +
                "sampleRates: ${info.sampleRates.asList()}, encodings: ${info.encodings.asList()}"
    }

    private fun transition(a: Double, b: Double, value: Double): Double {
        return (value - a) / (b - a)
    }

    companion object {
        private const val TAG = "AlertFeedback"

        private const val BUFFER_FRAMES = 1024 * 5
        private const val FREQUENCY1 = 1046.5022612023945
        private const val FREQUENCY2 = 523.2511306011972
        private const val VIBRATE = true

        private val alertVibrationPattern = longArrayOf(0, 55, 20)
        private val connectionLossPattern = longArrayOf(0, 30, 2000)
    }
}
