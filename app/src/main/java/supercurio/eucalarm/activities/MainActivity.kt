package supercurio.eucalarm.activities

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionRequired
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.*
import supercurio.eucalarm.ble.*
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.feedback.AlertFeedback
import supercurio.eucalarm.power.PowerManagement
import supercurio.eucalarm.service.AppService
import supercurio.eucalarm.ui.theme.EUCAlarmTheme
import supercurio.eucalarm.utils.RecordingProvider
import java.text.DecimalFormat


class MainActivity : ComponentActivity() {

    /**
     * TODO:
     *  * a picker for the device connection
     *  * show something that represents the current connection status
     *  * look into Bluetooth connection receiver to sync with EUC World / WheelLog
     *  * find potential Settings-type UI in Compose
     */

    private val activityScope = MainScope() + CoroutineName(TAG)

    private val wheelData = WheelDataStateFlows.getInstance()
    private lateinit var powerManagement: PowerManagement
    private lateinit var wheelConnection: WheelConnection
    private lateinit var alert: AlertFeedback
    private lateinit var wheelBleRecorder: WheelBleRecorder
    private lateinit var simulator: WheelBleSimulator

    private lateinit var findWheel: FindWheel

    private var player: WheelBlePlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.action == Intent.ACTION_VIEW) getSharedRecordingFile(intent)

        AppService.enable(applicationContext, true)

        powerManagement = PowerManagement.getInstance(applicationContext)
        wheelConnection = WheelConnection.getInstance(wheelData, powerManagement)
        alert = AlertFeedback.getInstance(wheelData, wheelConnection)
        wheelBleRecorder = WheelBleRecorder.getInstance(wheelConnection)
        simulator = WheelBleSimulator.getInstance(applicationContext, powerManagement)

        super.onCreate(savedInstanceState)
        setContent {
            PermissionsLayout {}
        }

        findWheel = FindWheel(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")

        findWheel.stopLeScan()
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

    @Composable
    fun MyLayout() {

        Column(modifier = Modifier.padding(16.dp)) {
            val scanningState by findWheel.isScanning.collectAsState()
            if (!scanningState)
                Button(onClick = { findWheel.find() }) { Text("Find Wheel") }
            else
                Button(onClick = { findWheel.stopLeScan() }) { Text("Stop Wheel Scan") }

            Button(onClick = { manualStop() }) { Text("Stop app") }

            Button(onClick = { alert.toggle() }) { Text(text = "AlertFeedback Test") }
            var playingState by remember { mutableStateOf(false) }
            if (!playingState)
                Button(onClick = {
                    playLastRecording()
                    playingState = true
                }) { Text("Play last recording") }
            else
                Button(onClick = {
                    playingState = false
                    player?.stop()
                }) { Text("Stop player") }

            var simulationState by remember { mutableStateOf(false) }
            if (!simulationState)
                Button(onClick = {
                    simulateLastRecording()
                    simulationState = true
                }) { Text("Simulate last recording") }
            else
                Button(onClick = {
                    simulator?.stop()
                    simulationState = false
                }) { Text("Stop simulation") }

            findWheel.foundWheel.collectAsState().value?.let {
                Text(
                    modifier = Modifier.clickable(onClick = {
                        wheelConnection.connectDevice(applicationContext, it)
                    }),
                    text = "Device name: ${it.device.name}, addr:${it.device.address}"
                )
            }

            val bleConnectionReady by wheelConnection.bleConnectionReady.collectAsState()
            if (bleConnectionReady) {
                Button(onClick = {
                    wheelConnection.disconnectDevice()
                }) { Text("Disconnect from ${wheelConnection.device?.name}") }

                val recordingState by wheelBleRecorder.isRecording.collectAsState()
                if (!recordingState)
                    Button(onClick = { record() }) { Text("Record") }
                else
                    Button(onClick = { stopRecording() }) { Text("Stop recording") }

            }

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
        val recordingFile = RecordingProvider.getLastRecordingFile(applicationContext) ?: return

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

        player = WheelBlePlayer(recording)

        activityScope.launch {
            // player?.printAsJson()
            player?.decode(wheelData)
        }
    }

    private fun simulateLastRecording() {
        val recording = RecordingProvider.getLastRecordingOrSample(applicationContext)

        simulator.start(applicationContext, recording)
    }

    private fun manualStop() {
        AppService.enable(applicationContext, false)
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
