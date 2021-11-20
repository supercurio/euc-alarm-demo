package supercurio.eucalarm.activities

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import supercurio.eucalarm.BuildConfig
import supercurio.eucalarm.GeneralConfig
import supercurio.eucalarm.R
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.OnStateDefault
import supercurio.eucalarm.ble.*
import supercurio.eucalarm.ble.find.FindWheels
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.di.AppLifecycle
import supercurio.eucalarm.feedback.AlertFeedback
import supercurio.eucalarm.log.AppLog
import supercurio.eucalarm.parsers.GotwayConfig
import supercurio.eucalarm.power.PowerManagement
import supercurio.eucalarm.service.AppService
import supercurio.eucalarm.ui.theme.EUCAlarmTheme
import supercurio.eucalarm.ui.theme.GreenAlert
import supercurio.eucalarm.ui.theme.RedAlert
import supercurio.eucalarm.utils.RecordingProvider
import supercurio.eucalarm.utils.Units.kmToMi
import supercurio.eucalarm.utils.btManager
import supercurio.eucalarm.utils.directBootContext
import supercurio.eucalarm.utils.locationEnabled
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // TODO:
    //  Make a better presentation to highlight Speed, Voltage, Motor current and main actions
    //  Show event log in UI, allow to share it or clear it
    //  Allow to choose view in mph

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
    lateinit var wheelBleProxy: WheelBleProxy

    @Inject
    lateinit var simulator: WheelBleSimulator

    @Inject
    lateinit var appLog: AppLog

    @Inject
    lateinit var generalConfig: GeneralConfig

    private lateinit var player: WheelBlePlayer

    private lateinit var findWheels: FindWheels

    private lateinit var btManager: BluetoothManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appStateStore.setState(OnStateDefault)

        btManager = applicationContext.btManager

        appLifeCycle.on()

        if (intent.action == Intent.ACTION_VIEW) getSharedRecordingFile(intent)

        player = WheelBlePlayer(wheelConnection)

        setContent {
            EUCAlarmTheme {
                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {
                    PermissionsLayout()
                }
            }
        }

        findWheels = FindWheels(applicationContext)

        registerReceiver(shutdownReceiver, IntentFilter(AppService.STOP_BROADCAST))
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) getSharedRecordingFile(intent)
    }

    override fun onResume() {
        super.onResume()
        simulator.detectSupport()
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
    private fun PermissionsLayout() {
        val locationPermissionState =
            rememberPermissionState(android.Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionRequired(
            permissionState = locationPermissionState,
            permissionNotGrantedContent = {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize()
                ) {
                    Text(stringResource(R.string.permission_request_explanation))
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { locationPermissionState.launchPermissionRequest() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.permission_request_button))
                    }
                }
            },
            permissionNotAvailableContent = {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize()
                ) {
                    Text(stringResource(R.string.permission_denied_explanation))
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .apply { data = Uri.fromParts("package", packageName, null) })
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.open_app_settings_button)) }
                }
            }
        ) {
            MyScaffold()
        }
    }


    @Composable
    private fun MyScaffold() {
        val scaffoldState = rememberScaffoldState()

        Scaffold(
            scaffoldState = scaffoldState,
            topBar = { MyTopAppBar(scaffoldState) },
            content = { MyLayout(scaffoldState) }
        )
    }


    @Composable
    private fun MyTopAppBar(scaffoldState: ScaffoldState) {

        val showExitDialog = remember { mutableStateOf(false) }
        val snackBarCoroutineScope = rememberCoroutineScope()

        TopAppBar(
            title = { Text("${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME} ") },
            actions = {
                IconButton(onClick = {
                    if (!shareRecording()) snackBarCoroutineScope.launch {
                        scaffoldState.snackbarHostState
                            .showSnackbar("No wheel data recording to share")
                    }
                }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share",
                    )
                }

                IconButton(onClick = { showExitDialog.value = true }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.stop_exit),
                    )
                }
            }
        )

        ConfirmExit(showExitDialog)
    }

    @Composable
    private fun MyLayout(scaffoldState: ScaffoldState? = null) {
        Column {
            val imperial = remember { mutableStateOf(generalConfig.unitsDistanceImperial) }

            ButtonsAndSettings(scaffoldState, imperial)
            WheelDataView(imperial)
            BeeperView()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun ButtonsAndSettings(
        scaffoldState: ScaffoldState?,
        imperialState: MutableState<Boolean>
    ) {
        var imperial by imperialState
        Column(modifier = Modifier.padding(8.dp)) {

            val bleConnectionState by wheelConnection.connectionStateFlow.collectAsState()
            var openDialog by remember { mutableStateOf(false) }

            if (openDialog) {
                fun dismiss() {
                    Log.i(TAG, "Dismiss")
                    findWheels.stop()
                    openDialog = false
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
                                                appLog.log("User action → Connect device")
                                                wheelConnection.connectDeviceFound(it)
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
                            Text(stringResource(R.string.cancel))
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
                        when {
                            !locationEnabled ->
                                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

                            !btManager.adapter.isEnabled ->
                                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

                            else -> {
                                openDialog = true
                                findWheels.find()
                            }
                        }
                    },
                    enabled = !scanningState,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Find Wheel") }
            } else {
                Crossfade(targetState = bleConnectionState) { state ->
                    val stateText = when (state) {
                        BleConnectionState.CONNECTED_READY -> "connected"
                        BleConnectionState.DISCONNECTED_RECONNECTING -> "reconnecting"
                        BleConnectionState.CONNECTING -> "connecting 1/2"
                        BleConnectionState.CONNECTED -> "connecting 2/2"
                        BleConnectionState.SCANNING -> "scanning"
                        else -> ""
                    }

                    Button(
                        onClick = { wheelConnection.disconnectDevice() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect ${wheelConnection.deviceName} ($stateText)")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))


            Row {
                Spacer(modifier = Modifier.width(8.dp))
                RadioButton(selected = !imperial, onClick = {
                    imperial = false
                    generalConfig.unitsDistanceImperial = false
                })
                Spacer(modifier = Modifier.width(4.dp))
                Text("Metric")

                Spacer(modifier = Modifier.width(8.dp))
                RadioButton(selected = imperial, onClick = {
                    imperial = true
                    generalConfig.unitsDistanceImperial = true
                })
                Spacer(modifier = Modifier.width(4.dp))
                Text("Imperial")

                Spacer(modifier = Modifier.width(16.dp))

                var wheelProxyChecked by remember { mutableStateOf(generalConfig.wheelProxy) }
                Checkbox(checked = wheelProxyChecked, onCheckedChange = {
                    wheelProxyChecked = it
                    enableWheelProxy(it)
                })
                Spacer(modifier = Modifier.width(8.dp))
                Text("Wheel Proxy")
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (bleConnectionState.canDisconnect) {
                Button(onClick = { alert.toggle() }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Toggle Alert")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!bleConnectionState.canDisconnect) {
                val playingState by player.playingState.collectAsState()

                Crossfade(targetState = !playingState) { state ->
                    when (state) {
                        true -> Button(
                            onClick = { playLastRecording(scaffoldState) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Replay last recording") }

                        false -> Button(
                            onClick = { player.stop() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Stop replay") }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (BuildConfig.DEBUG && simulator.isSupported) {
                    var simulationState by remember { mutableStateOf(false) }
                    if (!simulationState)
                        Button(onClick = {
                            simulateLastRecording()
                            simulationState = true
                        }, modifier = Modifier.fillMaxWidth()) { Text("Simulate last recording") }
                    else
                        Button(onClick = {
                            simulator.stop()
                            simulationState = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Stop simulation") }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }


            val recordingState by wheelBleRecorder.isRecording.collectAsState()
            Crossfade(targetState = recordingState) { state ->
                if (bleConnectionState == BleConnectionState.CONNECTED_READY && !state) {
                    Button(onClick = { record() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Record")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (state) {
                    Button(onClick = { stopRecording() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop recording")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            wheelConnection.parserConfigFlow.collectAsState().value.let { config ->
                if (config is GotwayConfig) {
                    Row {
                        Text("Voltage:")
                        Spacer(modifier = Modifier.width(8.dp))
                        RadioButtons(67.2f, config)
                        RadioButtons(84f, config)
                        RadioButtons(100.8f, config)
                    }
                }
            }
        }
    }

    @Composable
    private fun WheelDataView(imperialState: MutableState<Boolean>) {
        val imperial by imperialState
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {

            wheelData.speedFlow.collectAsState().value?.let {
                val speed = if (imperial) it.kmToMi else it
                val unit = if (imperial) " mph" else "km/h"
                BigTextData("Speed", "%2.1f", unit, speed)
            }
            wheelData.currentFlow.collectAsState().value?.let {
                BigTextData("Current", "%.2f", "A", it)
            }
            wheelData.voltageFlow.collectAsState().value?.let {
                BigTextData("Voltage", "%.2f", "V", it)
            }
            wheelData.temperatureFlow.collectAsState().value?.let {
                BigTextData("Temperature", "%.2f", "°C", it)
            }

            val distanceUnit = if (imperial) "mi" else "km"

            wheelData.tripDistanceFlow.collectAsState().value?.let {
                SmallTextData(
                    "Trip Distance", "%.3f", distanceUnit,
                    if (imperial) it.kmToMi else it
                )
            }
            wheelData.totalDistanceFlow.collectAsState().value?.let {
                SmallTextData(
                    "Total Distance", "%.3f", distanceUnit,
                    if (imperial) it.kmToMi else it
                )
            }
        }
    }

    @Composable
    private fun BeeperView() {
        wheelData.beeperFlow.collectAsState().value?.let { beeper ->
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = if (beeper) RedAlert else GreenAlert)
                    .padding(8.dp)
            ) {
                Text(
                    "Beeper: ${(if (beeper) "ON" else "OFF / Unknown")}",
                    fontSize = 30.sp,
                )
            }
        }
    }

    @Composable
    private fun BigTextData(title: String, format: String, unit: String, value: Double) {
        Row {
            Text(title)
            Text(
                String.format(format, value) + " $unit".padEnd(4),
                fontSize = 46.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Right,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
        }
    }

    @Composable
    private fun SmallTextData(title: String, format: String, unit: String, value: Double) {
        Row {
            Text(title)
            Text(
                String.format(format, value) + " $unit".padEnd(2),
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun RadioButtons(voltage: Float, gotwayConfig: GotwayConfig) {
        RadioButton(selected = gotwayConfig.voltage == voltage,
            onClick = {
                wheelConnection.parserConfigFlow.value = GotwayConfig(
                    address = gotwayConfig.address,
                    voltage = voltage
                )
            })
        Spacer(modifier = Modifier.width(4.dp))
        Text("${voltage}V")
        Spacer(modifier = Modifier.width(8.dp))
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

    @Composable
    private fun ConfirmExit(showConfirmExitDialog: MutableState<Boolean>) {
        var show by showConfirmExitDialog
        if (show) {
            AlertDialog(
                onDismissRequest = { show = false },
                title = { Text("Exit confirmation") },
                text = {
                    Text(
                        "Exiting will stop the app, its alarms and disable follow-connect.\n\n" +
                                "Leaving this app in the background does not use extra battery."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { manualStop() }) {
                        Text(stringResource(R.string.stop_exit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { show = false }) {
                        Text(stringResource(id = R.string.cancel))
                    }
                },
            )
        }
    }

    private fun enableWheelProxy(enabled: Boolean) {
        generalConfig.wheelProxy = true
        when {
            enabled && wheelConnection.connectionState == BleConnectionState.CONNECTED_READY ->
                wheelBleProxy.start()

            !enabled -> wheelBleProxy.stop()
        }
    }

    private fun record() {
        wheelBleRecorder.start()
    }

    private fun stopRecording() {
        wheelBleRecorder.shutDown()
        shareRecording()
    }

    private fun shareRecording(): Boolean {
        var recordingFile = RecordingProvider.getLastRecordingFile(
            applicationContext.directBootContext
        ) ?: return false

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

        return true
    }

    private fun playLastRecording(scaffoldState: ScaffoldState?) {
        val recording = RecordingProvider.getLastRecordingOrSample(applicationContext)

        activityScope.launch {
            scaffoldState?.snackbarHostState
                ?.showSnackbar(
                    message = "Replay from ${recording.fileName}",
                    duration = SnackbarDuration.Short
                )
        }
        activityScope.launch { player.replay(recording, wheelData) }
    }

    private fun simulateLastRecording() {
        val recording = RecordingProvider.getLastRecordingOrSample(applicationContext)

        simulator.start(applicationContext, recording)
    }

    private fun manualStop() {
        player.stop()
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
