package kama.atom.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.distance.ModelSpecificDistanceCalculator
import java.util.ArrayList
import java.util.HashSet

class BeaconScannerService : LifecycleService(), BeaconScannerDelegate {

    companion object {
        private const val NOTIFICATION_ID = 5001
        private const val NOTIFICATION_CHANNEL_ID = "beacon_scanner_channel"
        const val ACTION_PERMISSION_ERROR = "ACTION_PERMISSION_ERROR_BEACON_SCANNER"
        const val ACTION_CLOSE_EVENT = "ACTION_CLOSE_EVENT_BEACON_SCANNER"
        const val ACTION_FAR_EVENT = "ACTION_FAR_EVENT_BEACON_SCANNER"
        const val EVENT_MANY_METERS = 0
        const val EVENT_10_METERS = 1
        const val EVENT_1_METER = 2
        var instance: BeaconScannerService? = null
    }

    private val localBinder = BeaconScannerServiceBinder(this)
    private var beaconParsers = HashSet<BeaconParser>()
    private var serviceRunningInForeground = false
    private var time = 0L

    private lateinit var notificationManager: NotificationManager

    private var job: Job? = null
    private var job2: Job? = null
    private var job3: Job? = null
    private var listener: BeaconScannerListener? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private val leScanCallback: ScanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d("BeaconService", "onScanResult $result")
            processScan(result)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.d("BeaconService", "onScanFailed $errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceRunningInForeground = false
        return localBinder
    }

    override fun onRebind(intent: Intent) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceRunningInForeground = false
        super.onRebind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        instance = this
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            stopSelf()
        } else {
            val bm: BeaconManager = BeaconManager.getInstanceForApplication(this)
            val defaultDistanceCalculator =
                ModelSpecificDistanceCalculator(this, BeaconManager.getDistanceModelUpdateUrl())
            Beacon.setDistanceCalculator(defaultDistanceCalculator)
            val newBeaconParsers = HashSet<BeaconParser>()
            newBeaconParsers.addAll(bm.beaconParsers)
            for (beaconParser in bm.beaconParsers) {
                if (beaconParser.extraDataParsers.size > 0) {
                    newBeaconParsers.addAll(beaconParser.extraDataParsers)
                }
            }
            beaconParsers = newBeaconParsers
            val btAdapter = run {
                val bluetoothManager: BluetoothManager? = getSystemService(BluetoothManager::class.java)
                bluetoothManager?.adapter
            }
            bluetoothLeScanner = btAdapter?.bluetoothLeScanner
            val filters = arrayListOf<ScanFilter>(
                ScanFilter.Builder().build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            startScan(filters, settings, leScanCallback)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val notification = generateNotification()
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_STICKY
    }

    override fun onUnbind(intent: Intent): Boolean {
        serviceRunningInForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val notification = generateNotification()
                startForeground(NOTIFICATION_ID, notification)
                true
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun stopScan() {
        Log.d("BeaconService", "stopScan")
        job?.cancel()
        job3?.cancel()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothLeScanner?.stopScan(leScanCallback)
        }
        stopSelf()
    }

    fun setListener(listener: BeaconScannerListener?) {
        this.listener = listener
    }

    private fun generateNotification(): Notification {
        val mainNotificationText = "Searching for driver..."
        val titleText = "Scan service"
        val notificationChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            titleText,
            NotificationManager.IMPORTANCE_MIN
        )
        notificationManager.createNotificationChannel(notificationChannel)
        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(mainNotificationText)
            .setBigContentTitle(titleText)

        val notificationCompatBuilder = NotificationCompat.Builder(
            applicationContext,
            NOTIFICATION_CHANNEL_ID
        )

        return notificationCompatBuilder
            .setStyle(bigTextStyle)
            .setContentTitle(titleText)
            .setContentText(mainNotificationText)
            .setSmallIcon(R.drawable.icon)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startScan(
        filters: ArrayList<ScanFilter>,
        settings: ScanSettings,
        leScanCallback: ScanCallback
    ) {
        Log.d("BeaconService", "startScan")
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendBroadcast(Intent(ACTION_PERMISSION_ERROR))
            return
        }
        job?.cancel()
        job3?.cancel()
        job = lifecycleScope.launch {
            delay(10000)
            bluetoothLeScanner?.stopScan(leScanCallback)
            startScan(filters, settings, leScanCallback)
        }
        job3 = lifecycleScope.launch {
            delay(500)
            Log.d("BeaconService", "startScan process")
            bluetoothLeScanner?.startScan(filters, settings, leScanCallback)
        }

    }

    private fun processScan(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val scanRecord = result.scanRecord?.bytes
        val timestampMs =
            System.currentTimeMillis() - SystemClock.elapsedRealtime() + (result.timestampNanos / 1000000)
        job2?.cancel()
        job2 = lifecycleScope.launch(Dispatchers.IO) {
            var beacon: Beacon? = null
            for (parser in beaconParsers) {
                beacon = parser.fromScanData(scanRecord, rssi, device, timestampMs)
                if (beacon != null) {
                    break
                }
            }
            if (beacon != null) {
                val d = beacon.distance
                val id = beacon.id1
                if (id == Identifier.parse("2f234454-cf6d-4a0f-adf2-f4911ba9ffa6")) {
                    val new = System.currentTimeMillis()
                    val period = new - time
                    Log.d("BeaconService", "found $id, dist $d, period $period")
                    time = new
                    listener?.onEvent(
                        distance = d,
                        event = if (d < 1.2)
                            EVENT_1_METER
                        else if (d < 10.0)
                            EVENT_10_METERS
                        else
                            EVENT_MANY_METERS
                    )
                    if (d < 1.2) {
                        sendBroadcast(Intent(ACTION_CLOSE_EVENT))
                    } else if (d < 10.0) {
                        sendBroadcast(Intent(ACTION_FAR_EVENT))
                    }
                }
            }
        }
    }
}