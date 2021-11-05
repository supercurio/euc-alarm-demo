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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import supercurio.eucalarm.Notifications
import supercurio.eucalarm.ble.BleConnectionState
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.di.CoroutineScopeProvider
import supercurio.eucalarm.log.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class AlertFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelDataStateFlows: WheelDataStateFlows,
    private val wheelConnection: WheelConnection,
    private val notifications: Notifications,
    private val scopes: CoroutineScopeProvider,
    private val appLog: AppLog,
) {

    /*
     * TODO:
     *  - Audio alert on disconnection
     */

    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null
    private var alertTrack: AudioTrack? = null
    private var keepAliveTrack: AudioTrack? = null
    private var connectionLostTrack: AudioTrack? = null

    private var tracksRunning = false
    private var isPlaying = false

    private var currentVibrationPattern: LongArray? = null

    fun setup() {
        Log.i(TAG, "Setup instance")
        audioManager = context.getSystemService()!!
        vibrator = context.getSystemService()!!

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)

        scopes.app.launch {
            wheelDataStateFlows.beeperFlow.collect {
                when (it) {
                    true -> playAlert()
                    false -> stopAlert()
                }
            }
        }

        scopes.app.launch {
            var previousState = BleConnectionState.UNSET
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
                    BleConnectionState.CONNECTING,
                    BleConnectionState.CONNECTED,
                    BleConnectionState.RECEIVING_DATA,
                    BleConnectionState.REPLAY,
                    BleConnectionState.DISCONNECTED_RECONNECTING -> runTracks()

                    BleConnectionState.UNSET,
                    BleConnectionState.DISCONNECTING,
                    BleConnectionState.DISCONNECTED -> stopTracks()

                    BleConnectionState.SYSTEM_ALREADY_CONNECTED,
                    BleConnectionState.BLUETOOTH_OFF,
                    BleConnectionState.SCANNING -> {
                        // No change
                    }
                }
            }
        }

        // receive screen off events
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(screenOffReceiver, filter)

        logAudioInfo()
    }

    fun toggle() = if (!isPlaying) playAlert() else stopAlert()

    fun shutdown() {
        Log.i(TAG, "Shutdown")
        stopTracks()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        notifications.cancelAlerts()
    }

    private fun playAlert() {
        if (!tracksRunning) return
        isPlaying = true

        requestAudioFocus(FOCUS_GAIN_ALERT)

        if (VIBRATE) vibratePattern(ALERT_VIBRATION_PATTERN)

        alertTrack?.apply {
            setVolume(0f)
            stop()
            setLoopPoints(0, AudioHelper.ALERT_TRACK_FRAMES, -1)
            setVolume(1f)
            play()
        }

        notifications.notifyAlert()
        appLog.log("Alert: start")
    }

    private fun stopAlert() {
        if (!tracksRunning) return
        if (!isPlaying) return
        releaseAudioFocus(FOCUS_GAIN_ALERT)

        if (VIBRATE) stopVibration()
        alertTrack?.apply {
            setVolume(0f)
            pause()
        }
        isPlaying = false
        notifications.rollbackOngoing()
        appLog.log("Alert: stop")
    }

    private fun runTracks() {
        if (tracksRunning) return
        tracksRunning = true
        Log.i(TAG, "Run tracks")

        keepAliveTrack = AudioHelper.setupKeepAliveTrack()
        alertTrack = AudioHelper.setupAlertTrack().apply {
            appLog.log("Audio setup done ($sampleRate Hz)")
        }
    }

    private fun stopTracks() {
        if (!tracksRunning) return
        tracksRunning = false
        Log.i(TAG, "Stop Tracks")

        stopAlert()

        alertTrack?.apply {
            alertTrack = null
            pause()
            flush()
            stop()
            release()
        }

        keepAliveTrack?.apply {
            pause()
            flush()
            stop()
            release()
            keepAliveTrack = null
        }
    }

    private fun requestAudioFocus(focusGain: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioFocusRequest = audioFocusRequest(focusGain) ?: return
            audioManager?.requestAudioFocus(audioFocusRequest)
        } else {
            audioManager?.requestAudioFocus(
                afChangeListener,
                AudioManager.STREAM_MUSIC,
                focusGain
            )
        }
    }

    private fun releaseAudioFocus(focusGain: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioFocusRequest = audioFocusRequest(focusGain) ?: return
            audioManager?.abandonAudioFocusRequest(audioFocusRequest)
        } else
            audioManager?.abandonAudioFocus(afChangeListener)
    }

    private fun reconfigureAudioTracks() {
        Log.i(TAG, "Reconfigure audio tracks for new audio output")
        stopTracks()
        runTracks()
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {

            addedDevices?.forEach {
                Log.i(TAG, "Added device ${audioDeviceInfoText(it)}")
                if (tracksRunning && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    appLog.log("Bluetooth audio device added: ${it.productName}")
                    reconfigureAudioTracks()
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            removedDevices?.forEach {
                Log.i(TAG, "Removed device device ${audioDeviceInfoText(it)}")
                if (tracksRunning && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    appLog.log("Bluetooth audio device removed: ${it.productName}")
                    reconfigureAudioTracks()
                }
            }
        }
    }

    private fun onConnectionLoss(status: Boolean) {
        Log.i(TAG, "onConnectionLoss: $status")

        if (status) {
            requestAudioFocus(FOCUS_GAIN_CONNECTION_LOSS)
            vibratePattern(CONNECTION_LOSS_PATTERN)
            connectionLostTrack = AudioHelper.setupConnectionLossTrack()

            scopes.app.launch {
                var measuredTimeMs: Long
                while (connectionLostTrack != null) {
                    measuredTimeMs = measureTimeMillis {
                        connectionLostTrack?.apply {
                            pause()
                            flush()
                            stop()
                            play()
                        }
                    }
                    delay(CONNECTION_LOSS_DELAY - measuredTimeMs)
                }
            }
        } else {
            releaseAudioFocus(FOCUS_GAIN_CONNECTION_LOSS)
            stopVibration()
            connectionLostTrack?.apply {
                connectionLostTrack = null
                setVolume(0f)
                stop()
                flush()
                release()
            }
        }
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

        Log.i(TAG, "lowLatency: $lowLatency, audioPro: $audioPro")

        Log.i(TAG, "Audio devices info:")
        audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.forEach { info ->
                Log.i(TAG, audioDeviceInfoText(info))
            }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (currentVibrationPattern != null) {
                Log.i(TAG, "Screen turned off, resume active vibration")
                vibrate()
            }
        }
    }

    private fun audioFocusRequest(focusGain: Int) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            AudioFocusRequest
                .Builder(focusGain)
                .setWillPauseWhenDucked(false)
                .build()
        else
            null


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

        private const val VIBRATE = true

        private const val FOCUS_GAIN_ALERT =
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        private const val FOCUS_GAIN_CONNECTION_LOSS =
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

        private val ALERT_VIBRATION_PATTERN = longArrayOf(0, 55, 20)
        private val CONNECTION_LOSS_PATTERN = longArrayOf(0, 30, 1970)
        private const val CONNECTION_LOSS_DELAY = 4000L
    }
}
