package com.example.sensormonitor.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class UsbDeviceId(val vid: Int, val pid: Int)

class UsbSerialManager(
    private val context: Context,
    private val onGpsData: (NmeaGpsData) -> Unit,
    private val onImuData: (ImuFrameData) -> Unit,
    private val onConnectionChanged: (gpsConnected: Boolean, imuConnected: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "UsbSerialManager"
        private val SUPPORTED_DEVICES = listOf(
            UsbDeviceId(0x1A86, 0x7523),  // CH340X (ATGM336H-5N GPS)
            UsbDeviceId(0x10C4, 0xEA60),  // CP2102 (10-axis IMU)
        )
        private const val BAUD_RATE = 9600
        private const val RECONNECT_INTERVAL_MS = 10_000L
        private const val ACTION_USB_PERMISSION = "com.example.sensormonitor.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val gpsParser = NmeaGpsParser
    private val imuParser = ImuBinaryParser()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val portMutex = Mutex()

    private var gpsPort: UsbSerialPort? = null
    private var imuPort: UsbSerialPort? = null
    private var gpsReaderJob: Job? = null
    private var imuReaderJob: Job? = null
    private var reconnectJob: Job? = null
    private var gpsDeviceName: String? = null
    private var imuDeviceName: String? = null
    @Volatile private var gpsConnected = false
    @Volatile private var imuConnected = false

    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION)
        intent.setPackage(context.packageName)
        PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        connectDevice(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        connectDevice(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        handleDeviceDetached(device)
                    }
                }
            }
        }
    }

    fun start(scope: CoroutineScope) {
        Log.d(TAG, "start: registering USB receiver and probing devices")
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)

        probeAndConnect(scope)

        reconnectJob = scope.launch {
            while (isActive) {
                delay(RECONNECT_INTERVAL_MS)
                if (!gpsConnected || !imuConnected) {
                    probeAndConnect(this@launch)
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop: unregistering receiver and closing ports")
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        reconnectJob?.cancel()
        reconnectJob = null
        closeGpsPort()
        closeImuPort()
    }

    private fun probeAndConnect(scope: CoroutineScope) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.d(TAG, "probe: found ${drivers.size} USB serial devices")

        for (driver in drivers) {
            val device = driver.device
            val vid = device.vendorId
            val pid = device.productId
            Log.d(TAG, "probe: device VID=0x${vid.toString(16)} PID=0x${pid.toString(16)} name=${device.deviceName}")

            val supported = SUPPORTED_DEVICES.any { it.vid == vid && it.pid == pid }
            if (!supported) {
                Log.d(TAG, "probe: ignoring unsupported device VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}")
                continue
            }

            if (usbManager.hasPermission(device)) {
                connectDevice(device, "probe")
            } else {
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun connectDevice(device: UsbDevice, source: String = "broadcast") {
        val deviceName = device.deviceName
        Log.d(TAG, "connectDevice: name=$deviceName source=$source")

        // Reconnect: if we already know this device, skip sniffing
        val knownRole = when (deviceName) {
            gpsDeviceName -> "gps"
            imuDeviceName -> "imu"
            else -> null
        }

        // If both slots occupied by different devices, ignore
        if (knownRole == null && gpsConnected && imuConnected) {
            Log.d(TAG, "connectDevice: both slots occupied, ignoring $deviceName")
            return
        }

        openDevicePort(device, deviceName, knownRole)
    }

    private fun openDevicePort(device: UsbDevice, deviceName: String, knownRole: String?) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null || driver.ports.isEmpty()) {
            Log.e(TAG, "openDevicePort: no driver ($deviceName)")
            return
        }
        val port = driver.ports[0]
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "openDevicePort: failed to open ($deviceName)")
            return
        }
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // Auto-detect role by sniffing data
            val role = knownRole ?: detectDeviceRole(port, deviceName)
            if (role == null) {
                Log.d(TAG, "openDevicePort: could not detect role, closing ($deviceName)")
                port.close()
                return
            }

            when (role) {
                "gps" -> {
                    gpsPort = port
                    gpsDeviceName = deviceName
                    gpsConnected = true
                    Log.d(TAG, "openDevicePort: GPS port opened ($deviceName)")
                    try {
                        port.setDTR(true)
                        port.setRTS(false)
                        Log.d(TAG, "openDevicePort: GPS DTR=true RTS=false set")
                    } catch (e: Exception) {
                        Log.d(TAG, "openDevicePort: GPS DTR/RTS not supported", e)
                    }
                    notifyConnectionChanged()
                    startGpsReader(port)
                }
                "imu" -> {
                    imuPort = port
                    imuDeviceName = deviceName
                    imuConnected = true
                    Log.d(TAG, "openDevicePort: IMU port opened ($deviceName)")
                    notifyConnectionChanged()
                    startImuReader(port)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openDevicePort: port open failed ($deviceName)", e)
            try { port.close() } catch (_: Exception) {}
        }
    }

    private fun detectDeviceRole(port: UsbSerialPort, deviceName: String): String? {
        // Try sniffing twice to handle slow-starting GPS modules
        for (attempt in 1..2) {
            val sniff = ByteArray(64)
            val len = try {
                port.read(sniff, 500)
            } catch (e: Exception) {
                Log.e(TAG, "detectDeviceRole: read failed for $deviceName (attempt $attempt)", e)
                -1
            }
            if (len > 0) {
                Log.d(TAG, "detectDeviceRole: sniffed $len bytes from $deviceName (attempt $attempt)")
                for (i in 0 until len) {
                    val b = sniff[i].toInt() and 0xFF
                    when (b) {
                        0x24 -> { // '$' = NMEA GPS
                            Log.d(TAG, "detectDeviceRole: $deviceName → GPS (NMEA, byte $i)")
                            return "gps"
                        }
                        0x55 -> { // IMU binary frame header
                            Log.d(TAG, "detectDeviceRole: $deviceName → IMU (binary, byte $i)")
                            return "imu"
                        }
                    }
                }
                Log.d(TAG, "detectDeviceRole: no signature byte in $len bytes from $deviceName (attempt $attempt)")
            } else {
                Log.d(TAG, "detectDeviceRole: no data from $deviceName (attempt $attempt)")
            }
        }
        // Fallback: first-come, first-serve
        Log.d(TAG, "detectDeviceRole: using fallback for $deviceName (gpsConnected=$gpsConnected imuConnected=$imuConnected)")
        return when {
            !gpsConnected -> "gps"
            !imuConnected -> "imu"
            else -> null
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        val deviceName = device.deviceName
        Log.d(TAG, "handleDeviceDetached: name=$deviceName")

        when (deviceName) {
            gpsDeviceName -> closeGpsPort()
            imuDeviceName -> closeImuPort()
        }
    }

    private fun startGpsReader(port: UsbSerialPort) {
        gpsReaderJob?.cancel()
        gpsReaderJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(256)
            val lineBuffer = StringBuilder()
            var readCount = 0
            var lineCount = 0
            var parsedCount = 0
            try {
                // Send CASIC init command to ensure continuous 1Hz output
                try {
                    val initCmd = "\$PCAS04,1*18\r\n".toByteArray()
                    port.write(initCmd, 500)
                    Log.d(TAG, "GPS reader: sent init command \$PCAS04,1")
                } catch (e: Exception) {
                    Log.d(TAG, "GPS reader: init command failed (non-critical)", e)
                }

                Log.d(TAG, "GPS reader: started reading")
                while (isActive) {
                    val len = port.read(buffer, 1000)
                    if (len < 0) {
                        Log.d(TAG, "GPS reader: port.read returned $len, breaking")
                        break
                    }
                    readCount++
                    if (readCount <= 5 || readCount % 10 == 0) {
                        Log.d(TAG, "GPS reader: read #$readCount got $len bytes")
                    }
                    for (i in 0 until len) {
                        val c = buffer[i].toInt().toChar()
                        if (c == '\n') {
                            val line = lineBuffer.toString().trim()
                            lineBuffer.clear()
                            if (line.startsWith("$")) {
                                val data = gpsParser.parse(line)
                                if (data != null) {
                                    parsedCount++
                                    onGpsData(data)
                                    if (parsedCount <= 3) {
                                        Log.d(TAG, "GPS reader: parsed #$parsedCount: ${line.take(60)}")
                                    }
                                } else if (lineCount < 10) {
                                    Log.d(TAG, "GPS reader: parse failed for: ${line.take(50)}")
                                }
                            } else if (line.isNotEmpty() && lineCount < 5) {
                                Log.d(TAG, "GPS reader: non-NMEA line: ${line.take(50)}")
                            }
                            lineCount++
                        } else if (c != '\r') {
                            lineBuffer.append(c)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "GPS reader error", e)
                }
            } finally {
                Log.d(TAG, "GPS reader ended (reads=$readCount lines=$lineCount parsed=$parsedCount)")
                closeGpsPort()
            }
        }
    }

    private fun startImuReader(port: UsbSerialPort) {
        imuReaderJob?.cancel()
        imuReaderJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(256)
            try {
                while (isActive) {
                    val len = port.read(buffer, 200)
                    if (len < 0) break
                    if (len > 0) {
                        val frames = imuParser.feed(buffer.copyOf(len))
                        for (frame in frames) {
                            onImuData(frame)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "IMU reader error", e)
                }
            } finally {
                Log.d(TAG, "IMU reader ended")
                closeImuPort()
            }
        }
    }

    private fun closeGpsPort() {
        gpsReaderJob?.cancel()
        gpsReaderJob = null
        try {
            gpsPort?.close()
        } catch (_: Exception) {
        }
        gpsPort = null
        gpsDeviceName = null
        if (gpsConnected) {
            gpsConnected = false
            notifyConnectionChanged()
        }
    }

    private fun closeImuPort() {
        imuReaderJob?.cancel()
        imuReaderJob = null
        try {
            imuPort?.close()
        } catch (_: Exception) {
        }
        imuPort = null
        imuDeviceName = null
        if (imuConnected) {
            imuConnected = false
            notifyConnectionChanged()
        }
    }

    private fun notifyConnectionChanged() {
        mainHandler.post {
            onConnectionChanged(gpsConnected, imuConnected)
        }
    }
}
