package com.example.sensormonitor

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sensormonitor.data.RemoteMySqlMeasurementRepository
import com.example.sensormonitor.databinding.ActivityMainBinding
import com.example.sensormonitor.model.AltitudeUnit
import com.example.sensormonitor.model.AppSettings
import com.example.sensormonitor.model.IotMeasurement
import com.example.sensormonitor.model.SensorUiState
import com.example.sensormonitor.model.SpeedUnit
import com.example.sensormonitor.model.UiRefreshRate
import com.example.sensormonitor.usb.ImuFrameData
import com.example.sensormonitor.usb.NmeaGpsData
import com.example.sensormonitor.usb.UsbSerialManager
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LegendEntry
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, SettingsDialogFragment.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_NAME = "sensor_monitor_prefs"
        private const val KEY_LANGUAGE = "language_code"
        private const val DEFAULT_LANGUAGE = "zh"
        private const val MIN_Y_RANGE = 0.5f
        private const val SIDEBAR_ANIM_DURATION = 200L

        private const val KEY_CHART_WINDOW = "chart_window"
        private const val KEY_SAMPLE_INTERVAL = "sample_interval"
        private const val KEY_AUTO_SAVE = "auto_save"
        private const val KEY_RECORD_INTERVAL = "record_interval"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SPEED_UNIT = "speed_unit"
        private const val KEY_ALTITUDE_UNIT = "altitude_unit"
        private const val KEY_SMOOTHING_ENABLED = "smoothing_enabled"
        private const val KEY_SMOOTHING_ALPHA = "smoothing_alpha"
        private const val KEY_UI_REFRESH_RATE = "ui_refresh_rate"
        private const val KEY_UPLOAD_ENABLED = "upload_enabled"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "http://47.104.147.148:18080/measurements"
    }

    private enum class ChartMetric(
        val labelRes: Int,
        val colorRes: Int,
    ) {
        LATITUDE(R.string.chart_metric_latitude, R.color.chart_latitude),
        LONGITUDE(R.string.chart_metric_longitude, R.color.chart_longitude),
        ALTITUDE(R.string.chart_metric_altitude, R.color.chart_altitude),
        ACCEL_X(R.string.chart_metric_accel_x, R.color.chart_accel_x),
        ACCEL_Y(R.string.chart_metric_accel_y, R.color.chart_accel_y),
        ACCEL_Z(R.string.chart_metric_accel_z, R.color.chart_accel_z),
        GYRO_X(R.string.chart_metric_gyro_x, R.color.chart_gyro_x),
        GYRO_Y(R.string.chart_metric_gyro_y, R.color.chart_gyro_y),
        GYRO_Z(R.string.chart_metric_gyro_z, R.color.chart_gyro_z),
        SPEED(R.string.chart_metric_speed, R.color.chart_speed)
    }

    private lateinit var binding: ActivityMainBinding

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVectorSensor: Sensor? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest

    private var appSettings = AppSettings()
    private var isCollectionStarted = false
    private var isSidebarCollapsed = false
    private var sampleIndex = 0f
    private val chartDataSets = EnumMap<ChartMetric, LineDataSet>(ChartMetric::class.java)
    private val smoothedSeriesValues = EnumMap<ChartMetric, Float>(ChartMetric::class.java)

    private var uiTickerJob: Job? = null
    private var csvWriterJob: Job? = null
    private var csvWriter: BufferedWriter? = null
    private var csvUri: Uri? = null

    private val lock = Any()
    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }
    private val deviceName: String by lazy {
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "unknown_device" }
    }
    private var currentPathId: String = ""

    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var altitude: Double? = null
    private var yaw = 0f
    private var pitch = 0f
    private var roll = 0f
    private var gpsSpeed: Float? = null
    private var estimatedSpeed = 0f
    private var filteredAccel = 0f
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var lastAccelTimestampNs = 0L
    private var currentLanguageCode = DEFAULT_LANGUAGE

    private var usbSerialManager: UsbSerialManager? = null
    private var usbGpsConnected = false
    private var usbImuConnected = false
    private var magX = 0f
    private var magY = 0f
    private var magZ = 0f
    private var pressure = 0f
    private var height = 0f
    private var quatW = 0f
    private var quatX = 0f
    private var quatY = 0f
    private var quatZ = 0f
    private var extSvCount = 0
    private var extHdop = 0f
    private var extPdop = 0f
    private var extVdop = 0f

    private var uploadSuccessCount = 0
    private var uploadFailCount = 0

    private val uiState = kotlinx.coroutines.flow.MutableStateFlow(SensorUiState())

    override fun attachBaseContext(newBase: Context) {
        val savedLanguage = newBase.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        currentLanguageCode = savedLanguage
        super.attachBaseContext(wrapContextWithLocale(newBase, savedLanguage))
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted && isCollectionStarted) {
                startLocationUpdates()
            } else if (!granted) {
                uiState.value = uiState.value.copy(
                    statusText = getString(R.string.status_location_permission_missing)
                )
            }
        }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            synchronized(lock) {
                latitude = location.latitude
                longitude = location.longitude
                altitude = location.altitude
                gpsSpeed = if (location.hasSpeed()) location.speed else null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = loadSettings()
        currentLanguageCode = appSettings.languageCode

        setupServices()
        setupChart()
        setupControls()
        applyRuntimeSettings()
        binding.bodyFrame.doOnLayout { updateSidebarHandlePosition(animated = false) }
        collectUiState()

        uiState.value = uiState.value.copy(statusText = getString(R.string.status_idle))
    }

    override fun onResume() {
        super.onResume()
        if (binding.switchMeasure.isChecked) {
            startCollection()
        }
    }

    override fun onPause() {
        super.onPause()
        stopCollection()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiTickerJob?.cancel()
        csvWriterJob?.cancel()
        closeCsvWriter()
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(lock) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = event.values[0]
                    accelY = event.values[1]
                    accelZ = event.values[2]
                    updateEstimatedSpeed(event)
                }

                Sensor.TYPE_GYROSCOPE -> {
                    gyroX = event.values[0]
                    gyroY = event.values[1]
                    gyroZ = event.values[2]
                }

                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationMatrix = FloatArray(9)
                    val orientationValues = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationValues)
                    yaw = Math.toDegrees(orientationValues[0].toDouble()).toFloat().let { if (it < 0f) it + 360f else it }
                    pitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
                    roll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onNmeaGpsData(data: NmeaGpsData) {
        synchronized(lock) {
            data.latitude?.let { latitude = it }
            data.longitude?.let { longitude = it }
            data.altitude?.let { altitude = it }
            data.speed?.let { gpsSpeed = it }
        }
    }

    private fun onImuFrameData(data: ImuFrameData) {
        Log.d(TAG, "onImuFrameData: accelX=${data.accelX}, gyroX=${data.gyroX}, magX=${data.magX}, roll=${data.roll}")
        synchronized(lock) {
            data.accelX?.let { accelX = it }
            data.accelY?.let { accelY = it }
            data.accelZ?.let { accelZ = it }
            data.gyroX?.let { gyroX = it }
            data.gyroY?.let { gyroY = it }
            data.gyroZ?.let { gyroZ = it }
            data.roll?.let { roll = it }
            data.pitch?.let { pitch = it }
            data.yaw?.let { yaw = it }
            data.magX?.let { magX = it }
            data.magY?.let { magY = it }
            data.magZ?.let { magZ = it }
            data.pressure?.let { pressure = it.toFloat() }
            data.height?.let { height = it.toFloat() }
            data.quatW?.let { quatW = it }
            data.quatX?.let { quatX = it }
            data.quatY?.let { quatY = it }
            data.quatZ?.let { quatZ = it }
            data.svCount?.let { extSvCount = it }
            data.hdop?.let { extHdop = it }
            data.pdop?.let { extPdop = it }
            data.vdop?.let { extVdop = it }
        }
    }

    private fun onUsbConnectionChanged(gpsConnected: Boolean, imuConnected: Boolean) {
        usbGpsConnected = gpsConnected
        usbImuConnected = imuConnected
    }

    override fun onSettingsUpdated(settings: AppSettings) {
        val oldLanguage = appSettings.languageCode
        appSettings = settings
        saveSettings(settings)
        locationRequest = buildLocationRequest(settings.sampleIntervalMs)
        applyRuntimeSettings()

        if (isCollectionStarted) {
            stopMeasurementLogging()
            startMeasurementLogging()
            if (hasLocationPermission()) {
                stopLocationUpdates()
                startLocationUpdates()
            }
        }

        if (oldLanguage != settings.languageCode) {
            currentLanguageCode = settings.languageCode
            recreate()
        }
        trimChartIfNeeded()
    }

    fun showSettingsDialog() {
        SettingsDialogFragment.newInstance(appSettings)
            .show(supportFragmentManager, "settings_dialog")
    }

    private fun setupServices() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = buildLocationRequest(appSettings.sampleIntervalMs)

        usbSerialManager = UsbSerialManager(
            context = this,
            onGpsData = { onNmeaGpsData(it) },
            onImuData = { onImuFrameData(it) },
            onConnectionChanged = { gps, imu -> onUsbConnectionChanged(gps, imu) },
        )
    }

    private fun setupControls() {
        bindSeriesSwitch(binding.switchLatitude, ChartMetric.LATITUDE, false)
        bindSeriesSwitch(binding.switchLongitude, ChartMetric.LONGITUDE, false)
        bindSeriesSwitch(binding.switchAltitude, ChartMetric.ALTITUDE, false)
        bindSeriesSwitch(binding.switchAccelX, ChartMetric.ACCEL_X, true)
        bindSeriesSwitch(binding.switchAccelY, ChartMetric.ACCEL_Y, true)
        bindSeriesSwitch(binding.switchAccelZ, ChartMetric.ACCEL_Z, true)
        bindSeriesSwitch(binding.switchGyroX, ChartMetric.GYRO_X, false)
        bindSeriesSwitch(binding.switchGyroY, ChartMetric.GYRO_Y, false)
        bindSeriesSwitch(binding.switchGyroZ, ChartMetric.GYRO_Z, false)
        bindSeriesSwitch(binding.switchSpeed, ChartMetric.SPEED, true)

        binding.switchTable.setOnCheckedChangeListener(null)
        binding.switchTable.isChecked = true
        binding.dataPanel.isVisible = true
        binding.switchTable.setOnCheckedChangeListener { _, checked ->
            binding.dataPanel.isVisible = checked
        }

        binding.sidebarHandleTouch.setOnClickListener { toggleSidebar() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        binding.switchMeasure.setOnCheckedChangeListener { _, checked ->
            if (checked) startCollection() else stopCollection()
        }

        refreshChartPresentation()
    }

    private fun setupChart() {
        val lineData = LineData()
        ChartMetric.values().forEach { metric ->
            val dataSet = createDataSet(metric)
            chartDataSets[metric] = dataSet
            lineData.addDataSet(dataSet)
        }
        binding.lineChart.data = lineData
        binding.lineChart.description.isEnabled = false
        binding.lineChart.legend.isEnabled = true
        binding.lineChart.legend.isWordWrapEnabled = true
        binding.lineChart.legend.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        binding.lineChart.legend.form = Legend.LegendForm.LINE
        binding.lineChart.axisRight.isEnabled = false
        binding.lineChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        binding.lineChart.xAxis.setDrawGridLines(false)
        binding.lineChart.xAxis.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        binding.lineChart.axisLeft.setDrawGridLines(false)
        binding.lineChart.axisLeft.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        binding.lineChart.setNoDataText("")
        binding.lineChart.setBackgroundColor(ContextCompat.getColor(this, R.color.panel_mid))
        refreshChartPresentation()
    }

    private fun collectUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                uiState.collect { state ->
                    binding.tvStatus.text = state.statusText
                    binding.tvLat.text = state.latitude?.let { "%.6f".format(it) } ?: "--"
                    binding.tvLon.text = state.longitude?.let { "%.6f".format(it) } ?: "--"
                    binding.tvAltitude.text = formatAltitude(state.altitude)
                    binding.tvSpeed.text = formatSpeed(state.speed)
                    binding.tvAccelMag.text = "%.3f".format(sqrt(state.accelX * state.accelX + state.accelY * state.accelY + state.accelZ * state.accelZ))
                    binding.tvGyroMag.text = "%.3f".format(sqrt(state.gyroX * state.gyroX + state.gyroY * state.gyroY + state.gyroZ * state.gyroZ))
                    binding.tvYaw.text = "%.1f°".format(state.yaw)
                    binding.tvPitch.text = "%.1f°".format(state.pitch)
                    binding.tvRoll.text = "%.1f°".format(state.roll)

                    binding.tvUsbStatus.text = when {
                        state.usbGpsConnected && state.usbImuConnected -> "USB: GPS+IMU"
                        state.usbGpsConnected -> "USB: GPS"
                        state.usbImuConnected -> "USB: IMU"
                        else -> getString(R.string.status_usb_disconnected)
                    }
                    binding.tvUsbStatus.setTextColor(
                        if (state.usbGpsConnected || state.usbImuConnected)
                            ContextCompat.getColor(this@MainActivity, R.color.usb_connected)
                        else
                            ContextCompat.getColor(this@MainActivity, R.color.usb_disconnected)
                    )

                    binding.tvMagX.text = "%.2f".format(state.magX)
                    binding.tvMagY.text = "%.2f".format(state.magY)
                    binding.tvMagZ.text = "%.2f".format(state.magZ)
                    binding.tvPressure.text = if (state.pressure > 0f) "%.1f hPa".format(state.pressure / 100f) else "--"
                    binding.tvHeight.text = if (state.height > 0f) "%.1f m".format(state.height) else "--"
                    binding.tvSvCount.text = if (state.svCount > 0) "${state.svCount}" else "--"
                    binding.tvHdop.text = if (state.hdop > 0f) "%.1f".format(state.hdop) else "--"
                    binding.tvGpsFix.text = if (state.usbGpsConnected && state.latitude != null) "3D Fix" else "--"

                    if (state.isRunning) {
                        appendChartPoints(state)
                    }
                }
            }
        }
    }

    private fun bindSeriesSwitch(switchView: SwitchCompat, metric: ChartMetric, defaultVisible: Boolean) {
        switchView.setOnCheckedChangeListener(null)
        switchView.isChecked = defaultVisible
        chartDataSets[metric]?.isVisible = defaultVisible
        switchView.setOnCheckedChangeListener { _, checked ->
            setSeriesVisible(metric, checked)
        }
    }

    private fun startCollection() {
        if (isCollectionStarted) return
        isCollectionStarted = true
        applyRuntimeSettings()
        currentPathId = UUID.randomUUID().toString()
        smoothedSeriesValues.clear()

        accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVectorSensor?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        if (hasLocationPermission()) startLocationUpdates() else requestLocationPermission()

        usbSerialManager?.start(lifecycleScope)

        startMeasurementLogging()

        uiTickerJob?.cancel()
        uiTickerJob = lifecycleScope.launch {
            while (isActive && isCollectionStarted) {
                val snapshot = synchronized(lock) {
                    SensorUiState(
                        isRunning = true,
                        statusText = if (appSettings.autoSaveCsv) getString(R.string.status_csv_ready) else getString(R.string.status_running),
                        latitude = latitude,
                        longitude = longitude,
                        altitude = altitude,
                        accelX = accelX,
                        accelY = accelY,
                        accelZ = accelZ,
                        gyroX = gyroX,
                        gyroY = gyroY,
                        gyroZ = gyroZ,
                        yaw = yaw,
                        pitch = pitch,
                        roll = roll,
                        speed = resolveSpeed(),
                        usbGpsConnected = usbGpsConnected,
                        usbImuConnected = usbImuConnected,
                        magX = magX,
                        magY = magY,
                        magZ = magZ,
                        pressure = pressure,
                        height = height,
                        quatW = quatW,
                        quatX = quatX,
                        quatY = quatY,
                        quatZ = quatZ,
                        svCount = extSvCount,
                        pdop = extPdop,
                        hdop = extHdop,
                        vdop = extVdop,
                    )
                }
                uiState.value = snapshot
                delay(uiRefreshDelayMs())
            }
        }
    }

    private fun stopCollection() {
        if (!isCollectionStarted) return
        isCollectionStarted = false
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        usbSerialManager?.stop()
        uiTickerJob?.cancel()
        uiTickerJob = null
        stopMeasurementLogging()
        uploadSuccessCount = 0
        uploadFailCount = 0
        applyRuntimeSettings()

        uiState.value = uiState.value.copy(isRunning = false, statusText = getString(R.string.status_idle))
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        locationRequest = buildLocationRequest(appSettings.sampleIntervalMs)
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun buildLocationRequest(intervalMs: Int): LocationRequest {
        val safe = intervalMs.coerceIn(50, 2000).toLong()
        return LocationRequest.Builder(safe).setMinUpdateIntervalMillis(safe).build()
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun seriesValue(metric: ChartMetric, state: SensorUiState): Float {
        return when (metric) {
            ChartMetric.LATITUDE -> state.latitude?.toFloat() ?: 0f
            ChartMetric.LONGITUDE -> state.longitude?.toFloat() ?: 0f
            ChartMetric.ALTITUDE -> state.altitude?.toFloat() ?: 0f
            ChartMetric.ACCEL_X -> state.accelX
            ChartMetric.ACCEL_Y -> state.accelY
            ChartMetric.ACCEL_Z -> state.accelZ
            ChartMetric.GYRO_X -> state.gyroX
            ChartMetric.GYRO_Y -> state.gyroY
            ChartMetric.GYRO_Z -> state.gyroZ
            ChartMetric.SPEED -> state.speed
        }
    }

    private fun smoothSeriesValue(metric: ChartMetric, raw: Float): Float {
        if (!appSettings.smoothingEnabled) {
            smoothedSeriesValues.remove(metric)
            return raw
        }
        val alpha = appSettings.smoothingAlpha
        val prev = smoothedSeriesValues[metric] ?: raw
        val smoothed = alpha * prev + (1f - alpha) * raw
        smoothedSeriesValues[metric] = smoothed
        return smoothed
    }

    private fun resolveSpeed(): Float = gpsSpeed ?: estimatedSpeed

    private fun formatSpeed(speedMs: Float): String {
        return when (appSettings.speedUnit) {
            SpeedUnit.MS -> "%.2f m/s".format(speedMs)
            SpeedUnit.KMH -> "%.2f km/h".format(speedMs * 3.6f)
        }
    }

    private fun formatAltitude(altitudeMeter: Double?): String {
        if (altitudeMeter == null) return "--"
        return when (appSettings.altitudeUnit) {
            AltitudeUnit.METER -> "%.2f m".format(altitudeMeter)
            AltitudeUnit.FEET -> "%.2f ft".format(altitudeMeter * 3.28084)
        }
    }

    private fun uiRefreshDelayMs(): Long {
        return when (appSettings.uiRefreshRate) {
            UiRefreshRate.HZ_10 -> 100L
            UiRefreshRate.HZ_30 -> 33L
        }
    }

    private fun appendChartPoints(state: SensorUiState) {
        val data = binding.lineChart.data ?: return
        ChartMetric.values().forEach { metric ->
            val dataSet = chartDataSets[metric] ?: return@forEach
            val value = smoothSeriesValue(metric, seriesValue(metric, state))
            dataSet.addEntry(Entry(sampleIndex, value))
        }
        sampleIndex += 1f

        trimChartIfNeeded()
        data.notifyDataChanged()
        binding.lineChart.notifyDataSetChanged()
        binding.lineChart.setVisibleXRangeMaximum(appSettings.chartWindowSize.toFloat())
        binding.lineChart.moveViewToX(sampleIndex)
        binding.lineChart.invalidate()
    }

    private fun setSeriesVisible(metric: ChartMetric, visible: Boolean) {
        chartDataSets[metric]?.isVisible = visible
        refreshChartPresentation()
    }

    private fun trimChartIfNeeded() {
        chartDataSets.values.forEach { dataSet ->
            while (dataSet.entryCount > appSettings.chartWindowSize) {
                dataSet.removeFirst()
            }
        }
        updateDynamicYAxis()
    }

    private fun refreshChartPresentation() {
        updateChartLegend()
        updateDynamicYAxis()
        binding.lineChart.data?.notifyDataChanged()
        binding.lineChart.notifyDataSetChanged()
        binding.lineChart.invalidate()
    }

    private fun createDataSet(metric: ChartMetric): LineDataSet {
        return LineDataSet(mutableListOf(), getString(metric.labelRes)).apply {
            color = ContextCompat.getColor(this@MainActivity, metric.colorRes)
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 1.8f
            mode = LineDataSet.Mode.LINEAR
        }
    }

    private fun updateDynamicYAxis() {
        val visibleDataSets = chartDataSets.values.filter { it.isVisible && it.entryCount > 0 }
        if (visibleDataSets.isEmpty()) return

        val yMin = visibleDataSets.minOf { it.yMin }
        val yMax = visibleDataSets.maxOf { it.yMax }
        val baseRange = (yMax - yMin).coerceAtLeast(MIN_Y_RANGE)
        val margin = baseRange * 0.2f
        binding.lineChart.axisLeft.axisMinimum = yMin - margin
        binding.lineChart.axisLeft.axisMaximum = yMax + margin
    }

    private fun updateChartLegend() {
        val legendEntries = chartDataSets.values
            .filter { it.isVisible }
            .map { dataSet ->
                LegendEntry().apply {
                    label = dataSet.label
                    formColor = dataSet.color
                    form = Legend.LegendForm.LINE
                }
            }
        binding.lineChart.legend.isEnabled = legendEntries.isNotEmpty()
        binding.lineChart.legend.setCustom(legendEntries)
    }

    private fun updateEstimatedSpeed(event: SensorEvent) {
        val alphaGravity = if (appSettings.smoothingEnabled) appSettings.smoothingAlpha else 0.85f
        gravityX = alphaGravity * gravityX + (1f - alphaGravity) * event.values[0]
        gravityY = alphaGravity * gravityY + (1f - alphaGravity) * event.values[1]
        gravityZ = alphaGravity * gravityZ + (1f - alphaGravity) * event.values[2]

        val linearX = event.values[0] - gravityX
        val linearY = event.values[1] - gravityY
        val linearZ = event.values[2] - gravityZ
        val linearMag = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)
        filteredAccel = alphaGravity * filteredAccel + (1f - alphaGravity) * linearMag

        if (lastAccelTimestampNs != 0L) {
            val dt = (event.timestamp - lastAccelTimestampNs) / 1_000_000_000f
            if (filteredAccel < 0.08f) {
                estimatedSpeed = (estimatedSpeed * 0.85f).coerceAtLeast(0f)
                if (estimatedSpeed < 0.05f) estimatedSpeed = 0f
            } else {
                estimatedSpeed = (estimatedSpeed + filteredAccel * dt).coerceAtMost(30f)
            }
        }
        lastAccelTimestampNs = event.timestamp
    }

    private fun startMeasurementLogging() {
        if (appSettings.autoSaveCsv) {
            val writer = createCsvWriter() ?: run {
                uiState.value = uiState.value.copy(statusText = getString(R.string.status_csv_error))
                return
            }
            csvWriter = writer
        }

        csvWriterJob?.cancel()
        csvWriterJob = lifecycleScope.launch {
            while (isActive && isCollectionStarted) {
                writeCsvRow()
                delay(appSettings.recordIntervalMs.toLong())
            }
        }
    }

    private fun stopMeasurementLogging() {
        csvWriterJob?.cancel()
        csvWriterJob = null
        closeCsvWriter()
    }

    private fun createCsvWriter(): BufferedWriter? {
        return try {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "sensor_$time.csv"
            val writer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SensorLog")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                csvUri = uri
                val os = uri?.let { contentResolver.openOutputStream(it, "w") }
                if (os == null) return null
                BufferedWriter(OutputStreamWriter(os))
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SensorLog")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                BufferedWriter(FileWriter(file, false))
            }
            writer.write("timestamp,path_id,lat,lon,altitude,speed,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,pitch,roll,yaw,mag_x,mag_y,mag_z,pressure,height,quat_w,quat_x,quat_y,quat_z,sv_count,pdop,hdop,vdop")
            writer.newLine()
            writer.flush()
            writer
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCsvRow() {
        val snapshot = synchronized(lock) {
            val speedMs = resolveSpeed().toDouble()
            val speedText = when (appSettings.speedUnit) {
                SpeedUnit.MS -> resolveSpeed().toDouble()
                SpeedUnit.KMH -> resolveSpeed().toDouble() * 3.6
            }
            val altitudeValue = when (appSettings.altitudeUnit) {
                AltitudeUnit.METER -> altitude
                AltitudeUnit.FEET -> altitude?.times(3.28084)
            }
            val row = "${System.currentTimeMillis()},$currentPathId,${latitude ?: ""},${longitude ?: ""},${altitudeValue ?: ""},$speedText,$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ,$pitch,$roll,$yaw,$magX,$magY,$magZ,$pressure,$height,$quatW,$quatX,$quatY,$quatZ,$extSvCount,$extPdop,$extHdop,$extVdop"
            val measurement = IotMeasurement(
                recordTime = LocalDateTime.now(),
                deviceId = deviceId,
                deviceName = deviceName,
                pathId = currentPathId,
                latitude = latitude,
                longitude = longitude,
                altitude = altitudeValue,
                accelX = accelX.toDouble(),
                accelY = accelY.toDouble(),
                accelZ = accelZ.toDouble(),
                gyroX = gyroX.toDouble(),
                gyroY = gyroY.toDouble(),
                gyroZ = gyroZ.toDouble(),
                pitch = pitch.toDouble(),
                roll = roll.toDouble(),
                yaw = yaw.toDouble(),
                speed = speedMs,
                magX = magX.toDouble(),
                magY = magY.toDouble(),
                magZ = magZ.toDouble(),
                pressure = pressure.toDouble(),
                height = height.toDouble(),
                quatW = quatW.toDouble(),
                quatX = quatX.toDouble(),
                quatY = quatY.toDouble(),
                quatZ = quatZ.toDouble(),
                svCount = extSvCount,
                pdop = extPdop.toDouble(),
                hdop = extHdop.toDouble(),
                vdop = extVdop.toDouble(),
            )
            row to measurement
        }
        try {
            csvWriter?.let { writer ->
                writer.write(snapshot.first)
                writer.newLine()
                writer.flush()
            }
            uploadMeasurementToRemote(snapshot.second)
        } catch (_: Exception) {
            uiState.value = uiState.value.copy(statusText = getString(R.string.status_csv_error))
            stopMeasurementLogging()
        }
    }

    private fun uploadMeasurementToRemote(measurement: IotMeasurement) {
        if (!appSettings.uploadEnabled) return
        lifecycleScope.launch {
            val result = RemoteMySqlMeasurementRepository.insertMeasurement(measurement, appSettings.serverUrl)
            if (result.isSuccess) {
                uploadSuccessCount++
                uiState.value = uiState.value.copy(
                    statusText = getString(R.string.status_upload_ok, uploadSuccessCount)
                )
            } else {
                uploadFailCount++
                Log.e(TAG, "uploadMeasurementToRemote failed", result.exceptionOrNull())
                uiState.value = uiState.value.copy(
                    statusText = getString(R.string.status_upload_error, uploadFailCount)
                )
            }
        }
    }

    private fun closeCsvWriter() {
        try {
            csvWriter?.flush()
            csvWriter?.close()
        } catch (_: Exception) {
        }
        csvWriter = null
        csvUri = null
    }

    private fun loadSettings(): AppSettings {
        val sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        return AppSettings(
            chartWindowSize = sp.getInt(KEY_CHART_WINDOW, 150),
            sampleIntervalMs = sp.getInt(KEY_SAMPLE_INTERVAL, 100),
            autoSaveCsv = sp.getBoolean(KEY_AUTO_SAVE, false),
            recordIntervalMs = sp.getInt(KEY_RECORD_INTERVAL, 1000),
            languageCode = sp.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE,
            keepScreenOn = sp.getBoolean(KEY_KEEP_SCREEN_ON, true),
            speedUnit = SpeedUnit.valueOf(sp.getString(KEY_SPEED_UNIT, SpeedUnit.MS.name) ?: SpeedUnit.MS.name),
            altitudeUnit = AltitudeUnit.valueOf(sp.getString(KEY_ALTITUDE_UNIT, AltitudeUnit.METER.name) ?: AltitudeUnit.METER.name),
            smoothingEnabled = sp.getBoolean(KEY_SMOOTHING_ENABLED, false),
            smoothingAlpha = sp.getFloat(KEY_SMOOTHING_ALPHA, 0.85f),
            uiRefreshRate = UiRefreshRate.valueOf(sp.getString(KEY_UI_REFRESH_RATE, UiRefreshRate.HZ_10.name) ?: UiRefreshRate.HZ_10.name),
            uploadEnabled = sp.getBoolean(KEY_UPLOAD_ENABLED, false),
            serverUrl = sp.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
        )
    }

    private fun saveSettings(settings: AppSettings) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putInt(KEY_CHART_WINDOW, settings.chartWindowSize)
            .putInt(KEY_SAMPLE_INTERVAL, settings.sampleIntervalMs)
            .putBoolean(KEY_AUTO_SAVE, settings.autoSaveCsv)
            .putInt(KEY_RECORD_INTERVAL, settings.recordIntervalMs)
            .putString(KEY_LANGUAGE, settings.languageCode)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putString(KEY_SPEED_UNIT, settings.speedUnit.name)
            .putString(KEY_ALTITUDE_UNIT, settings.altitudeUnit.name)
            .putBoolean(KEY_SMOOTHING_ENABLED, settings.smoothingEnabled)
            .putFloat(KEY_SMOOTHING_ALPHA, settings.smoothingAlpha)
            .putString(KEY_UI_REFRESH_RATE, settings.uiRefreshRate.name)
            .putBoolean(KEY_UPLOAD_ENABLED, settings.uploadEnabled)
            .putString(KEY_SERVER_URL, settings.serverUrl)
            .apply()
    }

    private fun applyRuntimeSettings() {
        if (appSettings.keepScreenOn && isCollectionStarted) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun wrapContextWithLocale(baseContext: Context, languageCode: String): Context {
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(locale)
        return baseContext.createConfigurationContext(config)
    }

    private fun toggleSidebar() {
        val transition = AutoTransition().apply {
            duration = SIDEBAR_ANIM_DURATION
            interpolator = AccelerateDecelerateInterpolator()
        }
        TransitionManager.beginDelayedTransition(binding.bodyFrame, transition)
        isSidebarCollapsed = !isSidebarCollapsed
        binding.leftSidebar.visibility = if (isSidebarCollapsed) android.view.View.GONE else android.view.View.VISIBLE
        binding.ivSidebarHandle.setImageResource(
            if (isSidebarCollapsed) android.R.drawable.ic_media_next else android.R.drawable.ic_media_previous
        )
        binding.bodyFrame.post { updateSidebarHandlePosition(animated = true) }
    }

    private fun updateSidebarHandlePosition(animated: Boolean) {
        val halfTouchWidth = binding.sidebarHandleTouch.width / 2f
        val targetX = if (isSidebarCollapsed) 0f else (binding.leftSidebar.right - halfTouchWidth).coerceAtLeast(0f)

        if (animated) {
            binding.sidebarHandleTouch.animate()
                .translationX(targetX)
                .setDuration(SIDEBAR_ANIM_DURATION)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            binding.sidebarHandleTouch.translationX = targetX
        }
    }
}