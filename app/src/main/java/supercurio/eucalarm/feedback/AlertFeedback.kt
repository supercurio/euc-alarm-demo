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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import supercurio.eucalarm.ble.BleConnectionState
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.data.WheelDataStateFlows
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sin

class AlertFeedback(
    private val wheelDataStateFlows: WheelDataStateFlows,
    private val wheelConnection: WheelConnection,
) {

    /*
     * TODO:
     *  - Listen to changes in audio output configuration and reconfigure the AudioTrack
     *    with lowest-latency parameters
     *  - Re-generate alarm audio buffer according to the new samplerate
     */

    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator
    private lateinit var alertTrack: AudioTrack
    private lateinit var keepAliveTrack: AudioTrack

    private var setupComplete = false
    private var tracksRunning = false
    private var isPlaying = false

    private var currentVibrationPattern: LongArray? = null

    fun setup(context: Context, scope: CoroutineScope) {
        Log.i(TAG, "Setup instance")
        audioManager = context.getSystemService()!!
        vibrator = context.getSystemService()!!

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
                    BleConnectionState.CONNECTED_READY,
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

        setupComplete = true
    }

    fun playAlert() {
        if (!setupComplete) return
        if (!tracksRunning) return
        isPlaying = true

        requestAudioFocus()

        if (VIBRATE) vibratePattern(alertVibrationPattern)

        alertTrack.stop()
        alertTrack.setLoopPoints(0, BUFFER_FRAMES, -1)
        alertTrack.play()
    }

    fun stopAlert() {
        if (!setupComplete) return
        if (!tracksRunning) return
        releaseAudioFocus()

        if (VIBRATE) stopVibration()
        alertTrack.pause()
        isPlaying = false
    }

    fun toggle() {
        if (!setupComplete) return
        if (!isPlaying) playAlert() else stopAlert()
    }

    fun shutdown() {
        Log.i(TAG, "Shutdown")
        stopTracks()
        instance = null
    }

    /**
     * private methods
     */

    private fun runTracks() {
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

        alertTrack.pause()
        keepAliveTrack.pause()

        alertTrack.flush()
        keepAliveTrack.flush()

        alertTrack.stop()
        keepAliveTrack.stop()

        alertTrack.release()
        keepAliveTrack.release()

    }

    private fun requestAudioFocus() =
        audioManager.requestAudioFocus(
            afChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        )

    private fun releaseAudioFocus() {
        audioManager.abandonAudioFocus(afChangeListener)
    }

    private fun setupAlertTrack(): AudioTrack {
        val audioAttributes = AudioAttributes.Builder()
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
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
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addOnRoutingChangedListener(
                        { routing ->
                            Log.i(
                                TAG,
                                "routed device: ${audioDeviceInfoText(routing.routedDevice)}, " +
                                        "preferredDevice: ${audioDeviceInfoText(routing.routedDevice)}"
                            )
                        }, null
                    )
                } else {
                    addOnRoutingChangedListener(AudioTrack.OnRoutingChangedListener { routing ->
                        Log.i(
                            TAG, "routed device: ${audioDeviceInfoText(routing.routedDevice)}, " +
                                    "preferredDevice: ${audioDeviceInfoText(routing.routedDevice)}"
                        )
                    }, null)
                }
            }


        val bufferSizeInFrames = track.bufferSizeInFrames

        Log.i(TAG, "buffer size in frames: $bufferSizeInFrames")

        val buf = getAudioBuffer(FREQUENCY, bufferSizeInFrames, SAMPLE_RATE)
        track.write(buf, 0, buf.size)

        return track
    }

    private fun setupKeepAliveTrack(): AudioTrack {
        val frames = 1024

        val audioAttributes = AudioAttributes.Builder()
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
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
                Log.i(TAG, "Added devices ${audioDeviceInfoText(it)}")
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            removedDevices?.forEach {
                Log.i(TAG, "Removed device devices ${audioDeviceInfoText(it)}")
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
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else
            vibrator.vibrate(pattern, 0)
    }


    private fun stopVibration() {
        if (currentVibrationPattern != null) {
            currentVibrationPattern = null
            vibrator.cancel()
        }
    }

    private fun stuff(context: Context) {

        val lowLatency = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val audioPro = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)


        val outputSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val outputFramesPerBuffer =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)

        val deviceInfo = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        deviceInfo.forEach { info ->
            Log.i(TAG, audioDeviceInfoText(info))
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        Log.i(
            TAG,
            "lowLatency: $lowLatency, " +
                    "audioPro: $audioPro, " +
                    "outputSampleRate: $outputSampleRate, " +
                    "outputFramesPerBuffer: $outputFramesPerBuffer"
        )
    }

    private fun getAudioBuffer(frequency: Int, len: Int, fs: Int): ShortArray {
        val buf = ShortArray(len)
        val twoPi = 8.0 * atan(1.0)
        var phase = 0.0

        for (i in 0 until len) {
            buf[i] = (sin(phase) * Short.MAX_VALUE).roundToInt().toShort()
            phase += twoPi * frequency / fs

            if (i > len * 0.5 && phase < 0.2) break

            if (phase > twoPi) {
                phase -= twoPi
            }
        }

        return buf
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

    companion object {
        private const val TAG = "AlertFeedback"

        private const val BUFFER_FRAMES = 1024 * 5
        private const val SAMPLE_RATE = 44100
        private const val FREQUENCY = 1000
        private const val VIBRATE = true

        private val alertVibrationPattern = longArrayOf(0, 55, 20)
        private val connectionLossPattern = longArrayOf(0, 30, 2000)

        private var instance: AlertFeedback? = null
        fun getInstance(
            wheelDataStateFlows: WheelDataStateFlows,
            wheelConnection: WheelConnection
        ) = instance ?: AlertFeedback(wheelDataStateFlows, wheelConnection).also { instance = it }
    }
}
