package supercurio.eucalarm.activities

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionRequired
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import supercurio.eucalarm.AppLifecycle
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.ClosedState
import supercurio.eucalarm.ble.*
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.feedback.AlertFeedback
import supercurio.eucalarm.power.PowerManagement
import supercurio.eucalarm.service.AppService
import supercurio.eucalarm.ui.theme.EUCAlarmTheme
import supercurio.eucalarm.utils.RecordingProvider
import supercurio.eucalarm.utils.directBootContext
import java.text.DecimalFormat
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * TODO:
     *  * look into Bluetooth connection receiver to sync with EUC World / WheelLog
     */

    private val activityScope = MainScope() + CoroutineName(TAG)

    @Inject
    lateinit var appLifeCycle: AppLifecycle

    @Inject
    lateinit var wheelData: WheelDataStateFlows

    @Inject
    lateinit var appStateStore: AppStateStore

    @Inject
    lateinit var powerManagement: PowerManagement

    @Inject
    lateinit var wheelConnection: WheelConnection

    @Inject
    lateinit var alert: AlertFeedback

    @Inject
    lateinit var wheelBleRecorder: WheelBleRecorder

    @Inject
    lateinit var simulator: WheelBleSimulator

    private lateinit var player: WheelBlePlayer

    private lateinit var findWheels: FindWheels


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appLifeCycle.on()

        if (intent.action == Intent.ACTION_VIEW) getSharedRecordingFile(intent)

        player = WheelBlePlayer(wheelConnection)

        setContent {
            PermissionsLayout {}
        }

        findWheels = FindWheels(applicationContext)

        registerReceiver(shutdownReceiver, IntentFilter(AppService.STOP_BROADCAST))
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) getSharedRecordingFile(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")

        unregisterReceiver(shutdownReceiver)
        findWheels.stop()
        player.stop()
        activityScope.cancel()
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    private fun PermissionsLayout(navigateToSettingsScreen: () -> Unit) {
        // Track if the user doesn't want to see the rationale any more.
        var doNotShowRationale by rememberSaveable { mutableStateOf(false) }

        val locationPermissionState =
            rememberPermissionState(android.Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionRequired(
            permissionState = locationPermissionState,
            permissionNotGrantedContent = {
                if (doNotShowRationale) {
                    Text("Feature not available")
                } else {
                    Rationale(
                        onDoNotShowRationale = { doNotShowRationale = true },
                        onRequestPermission = { locationPermissionState.launchPermissionRequest() }
                    )
                }
            },
            permissionNotAvailableContent = {
                Log.e(TAG, "Permission denied")
            }
        ) {
            EUCAlarmTheme {
                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {
                    MyLayout()
                }
            }
        }
    }

    @Composable
    private fun Rationale(
        onDoNotShowRationale: () -> Unit,
        onRequestPermission: () -> Unit
    ) {
        Column {
            Text("The Location permission is required to scan for wheels.")
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(onClick = onRequestPermission) {
                    Text("Request permission")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onDoNotShowRationale) {
                    Text("Don't show again")
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun MyLayout() {

        Column(modifier = Modifier.padding(16.dp)) {

            val bleConnectionState by wheelConnection.connectionStateFlow.collectAsState()
            val openDialog = remember { mutableStateOf(false) }

            if (openDialog.value) {
                fun dismiss() {
                    Log.i(TAG, "Dismiss")
                    findWheels.stop()
                    openDialog.value = false
                }

                AlertDialog(
                    onDismissRequest = { dismiss() },
                    title = { Text(text = "Scanning for wheels...") },
                    text = {
                        findWheels.foundWheels.collectAsState().value.let { list ->
                            Column {
                                if (list.isEmpty()) Text("No wheel found")

                                list.forEach {
                                    Row(Modifier.padding(4.dp)) {
                                        Text(
                                            modifier = Modifier.clickable(onClick = {
                                                dismiss()
                                                wheelConnection.connectDevice(it)
                                            }),
                                            text = "${it.device.name}\n${it.device.address}: " +
                                                    "found: ${it.from}${it.rssi?.let { ", ${it}dBm" } ?: ""}"
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { dismiss() }) {
                            Text("Cancel")
                        }
                    },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                    modifier = Modifier.padding(32.dp)
                )
            }
            val scanningState by findWheels.isScanning.collectAsState()

            if (!bleConnectionState.canDisconnect) {
                Button(
                    onClick = {
                        if (BluetoothAdapter.getDefaultAdapter().isEnabled) {
                            openDialog.value = true
                            findWheels.find()
                        } else startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

                    }, enabled = !scanningState
                ) { Text("Find Wheel") }
            } else {

                val stateText = when (bleConnectionState) {
                    BleConnectionState.RECEIVING_DATA -> "receiving data"
                    BleConnectionState.DISCONNECTED_RECONNECTING -> "reconnecting"
                    BleConnectionState.CONNECTING -> "connecting"
                    BleConnectionState.CONNECTED -> "connected"
                    else -> ""
                }

                Button(onClick = {
                    wheelConnection.disconnectDevice()
                    appStateStore.setState(ClosedState)
                }) { Text("Disconnect ${wheelConnection.deviceName} ($stateText)") }
            }

            Button(onClick = { manualStop() }) { Text("Stop and exit app") }

            if (bleConnectionState.canDisconnect)
                Button(onClick = { alert.toggle() }) { Text(text = "AlertFeedback Test") }

            if (!bleConnectionState.canDisconnect) {
                val playingState by player.playingState.collectAsState()
                if (!playingState)
                    Button(onClick = { playLastRecording() }) { Text("Play last recording") }
                else
                    Button(onClick = { player.stop() }) { Text("Stop player") }

                if (simulator.isSupported) {
                    var simulationState by remember { mutableStateOf(false) }
                    if (!simulationState)
                        Button(onClick = {
                            simulateLastRecording()
                            simulationState = true
                        }) { Text("Simulate last recording") }
                    else
                        Button(onClick = {
                            simulator.stop()
                            simulationState = false
                        }) { Text("Stop simulation") }
                }
            }


            val recordingState by wheelBleRecorder.isRecording.collectAsState()
            if (bleConnectionState == BleConnectionState.RECEIVING_DATA && !recordingState)
                Button(onClick = { record() }) { Text("Record") }
            if (recordingState)
                Button(onClick = { stopRecording() }) { Text("Stop recording") }


            val df = DecimalFormat("#.###")
            wheelData.voltageFlow.collectAsState().value?.let {
                Text("Voltage: ${df.format(it)} V ", fontSize = 30.sp)
            }
            wheelData.speedFlow.collectAsState().value?.let {
                Text("Speed: ${df.format(it)} km/h ", fontSize = 30.sp)
            }
            wheelData.tripDistanceFlow.collectAsState().value?.let {
                Text("Trip Distance: $it km ", fontSize = 30.sp)
            }
            wheelData.totalDistanceFlow.collectAsState().value?.let {
                Text("Total Distance: $it km ", fontSize = 30.sp)
            }
            wheelData.currentFlow.collectAsState().value?.let {
                Text("Current: $it A ", fontSize = 30.sp)
            }
            wheelData.temperatureFlow.collectAsState().value?.let {
                Text("Temperature: ${df.format(it)} °C ", fontSize = 30.sp)
            }
            val beeper = wheelData.beeperFlow.collectAsState().value
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = if (beeper) Color.Red else Color.Green)
                    .padding(20.dp)
            ) {
                Text(
                    "Beeper: ${(if (beeper) "ON" else "OFF / Unknown")}",
                    fontSize = 30.sp,
                )
            }
        }
    }

    @Preview(name = "Light Mode")
    @Preview(
        uiMode = Configuration.UI_MODE_NIGHT_YES,
        showBackground = true,
        name = "Dark Mode"
    )
    @Composable
    fun PreviewMessageCard() {
        EUCAlarmTheme {
            MyLayout()
        }
    }

    private fun record() {
        wheelBleRecorder.start(applicationContext)
    }

    private fun stopRecording() {
        wheelBleRecorder.shutDown()
        shareRecording()
    }

    private fun shareRecording() {
        var recordingFile = RecordingProvider.getLastRecordingFile(
            applicationContext.directBootContext
        ) ?: return

        // copy the file to internal storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recordingFile = RecordingProvider.copyToAppStandardStorage(
                this,
                recordingFile
            )
        }

        Log.i(TAG, "Share file: ${recordingFile.absolutePath}")

        val uri = FileProvider.getUriForFile(
            applicationContext,
            "${packageName}.fileprovider",
            recordingFile
        )

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/octet-stream"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            data = uri
        }

        startActivity(Intent.createChooser(shareIntent, "Recording"))
    }

    private fun playLastRecording() {
        val recording = RecordingProvider.getLastRecordingOrSample(applicationContext)
        activityScope.launch {
            // player?.printAsJson()
            player.replay(recording, wheelData)
        }
    }

    private fun simulateLastRecording() {
        val recording = RecordingProvider.getLastRecordingOrSample(applicationContext)

        simulator.start(applicationContext, recording)
    }

    private fun manualStop() {
        appLifeCycle.off()
        finish()
    }

    private fun getSharedRecordingFile(intent: Intent) {
        val inputStream = intent.data?.let { contentResolver.openInputStream(it) }
        inputStream?.let {
            val out = RecordingProvider
                .generateImportedFilename(applicationContext)
                .outputStream()

            val copied = it.copyTo(out)
            out.close()
            Log.i(TAG, "Copied: $copied bytes")
            it.close()
        }
    }

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AppService.STOP_BROADCAST) manualStop()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
