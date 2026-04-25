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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sensormonitor.data.RemoteMySqlMeasurementRepository
import com.example.sensormonitor.databinding.ActivityMainBinding
import com.example.sensormonitor.model.AltitudeUnit
import com.example.sensormonitor.model.AppSettings
import com.example.sensormonitor.model.ChartSensorType
import com.example.sensormonitor.model.IotMeasurement
import com.example.sensormonitor.model.SensorUiState
import com.example.sensormonitor.model.SpeedUnit
import com.example.sensormonitor.model.UiRefreshRate
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
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
import java.util.Locale
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
    private var smoothedChartValue: Float? = null

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
    }

    private fun setupControls() {
        binding.btnAccel.setOnClickListener { selectChartType(ChartSensorType.ACCELEROMETER) }
        binding.btnGyro.setOnClickListener { selectChartType(ChartSensorType.GYROSCOPE) }
        binding.btnLocation.setOnClickListener { selectChartType(ChartSensorType.LOCATION) }
        binding.btnAngle.setOnClickListener { selectChartType(ChartSensorType.ANGLE) }
        binding.btnSpeed.setOnClickListener { selectChartType(ChartSensorType.SPEED) }
        binding.sidebarHandleTouch.setOnClickListener { toggleSidebar() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        binding.switchMeasure.setOnCheckedChangeListener { _, checked ->
            if (checked) startCollection() else stopCollection()
        }
    }

    private fun setupChart() {
        binding.lineChart.data = LineData(createDataSet(ChartSensorType.ACCELEROMETER))
        binding.lineChart.description.isEnabled = false
        binding.lineChart.legend.isEnabled = true
        binding.lineChart.legend.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        binding.lineChart.axisRight.isEnabled = false
        binding.lineChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        binding.lineChart.xAxis.setDrawGridLines(false)
        binding.lineChart.xAxis.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        binding.lineChart.axisLeft.setDrawGridLines(false)
        binding.lineChart.axisLeft.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        binding.lineChart.setNoDataText("")
        binding.lineChart.setBackgroundColor(ContextCompat.getColor(this, R.color.panel_mid))
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
                    appendChartPoint(state.chartSample)
                }
            }
        }
    }

    private fun selectChartType(type: ChartSensorType) {
        if (uiState.value.chartType == type) return
        uiState.value = uiState.value.copy(chartType = type)
        smoothedChartValue = null
        resetChart(type)
    }

    private fun startCollection() {
        if (isCollectionStarted) return
        isCollectionStarted = true
        applyRuntimeSettings()

        accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVectorSensor?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        if (hasLocationPermission()) startLocationUpdates() else requestLocationPermission()

        startMeasurementLogging()

        uiTickerJob?.cancel()
        uiTickerJob = lifecycleScope.launch {
            while (isActive && isCollectionStarted) {
                val snapshot = synchronized(lock) {
                    val currentType = uiState.value.chartType
                    val rawSample = resolveChartSample(currentType)
                    val chartSample = applyChartSmoothing(rawSample)
                    SensorUiState(
                        isRunning = true,
                        statusText = if (appSettings.autoSaveCsv) getString(R.string.status_csv_ready) else getString(R.string.status_running),
                        chartType = currentType,
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
                        chartSample = chartSample
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
        uiTickerJob?.cancel()
        uiTickerJob = null
        stopMeasurementLogging()
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

    private fun resolveChartSample(type: ChartSensorType): Float {
        return when (type) {
            ChartSensorType.ACCELEROMETER -> sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
            ChartSensorType.GYROSCOPE -> sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
            ChartSensorType.LOCATION -> (latitude ?: 0.0).toFloat()
            ChartSensorType.ANGLE -> yaw
            ChartSensorType.SPEED -> resolveSpeed()
        }
    }

    private fun applyChartSmoothing(raw: Float): Float {
        if (!appSettings.smoothingEnabled) {
            smoothedChartValue = null
            return raw
        }
        val alpha = appSettings.smoothingAlpha
        val prev = smoothedChartValue ?: raw
        val smoothed = alpha * prev + (1f - alpha) * raw
        smoothedChartValue = smoothed
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

    private fun appendChartPoint(value: Float) {
        val data = binding.lineChart.data ?: return
        val dataSet = data.getDataSetByIndex(0) ?: return
        dataSet.addEntry(Entry(sampleIndex, value))
        sampleIndex += 1f

        while (dataSet.entryCount > appSettings.chartWindowSize) {
            dataSet.removeFirst()
        }

        updateDynamicYAxis(dataSet)
        data.notifyDataChanged()
        binding.lineChart.notifyDataSetChanged()
        binding.lineChart.setVisibleXRangeMaximum(appSettings.chartWindowSize.toFloat())
        binding.lineChart.moveViewToX(sampleIndex)
    }

    private fun resetChart(type: ChartSensorType) {
        sampleIndex = 0f
        binding.lineChart.clear()
        binding.lineChart.data = LineData(createDataSet(type))
        binding.lineChart.invalidate()
    }

    private fun trimChartIfNeeded() {
        val dataSet = binding.lineChart.data?.getDataSetByIndex(0) ?: return
        while (dataSet.entryCount > appSettings.chartWindowSize) {
            dataSet.removeFirst()
        }
        updateDynamicYAxis(dataSet)
        binding.lineChart.data?.notifyDataChanged()
        binding.lineChart.notifyDataSetChanged()
    }

    private fun createDataSet(type: ChartSensorType): LineDataSet {
        val labelRes = when (type) {
            ChartSensorType.ACCELEROMETER -> R.string.chart_label_accel
            ChartSensorType.GYROSCOPE -> R.string.chart_label_gyro
            ChartSensorType.LOCATION -> R.string.chart_label_location
            ChartSensorType.ANGLE -> R.string.chart_label_angle
            ChartSensorType.SPEED -> R.string.chart_label_speed
        }
        val colorRes = when (type) {
            ChartSensorType.ACCELEROMETER -> R.color.chart_accel
            ChartSensorType.GYROSCOPE -> R.color.chart_gyro
            ChartSensorType.LOCATION -> R.color.chart_location
            ChartSensorType.ANGLE -> R.color.chart_angle
            ChartSensorType.SPEED -> R.color.chart_speed
        }
        return LineDataSet(mutableListOf(), getString(labelRes)).apply {
            color = ContextCompat.getColor(this@MainActivity, colorRes)
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 1.8f
        }
    }

    private fun updateDynamicYAxis(dataSet: ILineDataSet) {
        if (dataSet.entryCount == 0) return
        val yMin = dataSet.yMin
        val yMax = dataSet.yMax
        val baseRange = (yMax - yMin).coerceAtLeast(MIN_Y_RANGE)
        val margin = baseRange * 0.2f
        binding.lineChart.axisLeft.axisMinimum = yMin - margin
        binding.lineChart.axisLeft.axisMaximum = yMax + margin
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
            writer.write("timestamp,lat,lon,altitude,speed,accelMag,gyroMag,yaw,pitch")
            writer.newLine()
            writer.flush()
            writer
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCsvRow() {
        val snapshot = synchronized(lock) {
            val speedText = when (appSettings.speedUnit) {
                SpeedUnit.MS -> resolveSpeed().toDouble()
                SpeedUnit.KMH -> resolveSpeed().toDouble() * 3.6
            }
            val altitudeValue = when (appSettings.altitudeUnit) {
                AltitudeUnit.METER -> altitude
                AltitudeUnit.FEET -> altitude?.times(3.28084)
            }
            val accelMag = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
            val gyroMag = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
            val row = "${System.currentTimeMillis()},${latitude ?: ""},${longitude ?: ""},${altitudeValue ?: ""},$speedText,$accelMag,$gyroMag,$yaw,$pitch"
            val measurement = IotMeasurement(
                recordTime = LocalDateTime.now(),
                deviceId = deviceId,
                deviceName = deviceName,
                speed = speedText,
                angle = yaw.toDouble(),
                distance = altitudeValue
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
        lifecycleScope.launch {
            val result = RemoteMySqlMeasurementRepository.insertMeasurement(measurement)
            if (result.isFailure) {
                Log.e(TAG, "uploadMeasurementToRemote failed", result.exceptionOrNull())
                uiState.value = uiState.value.copy(statusText = getString(R.string.status_csv_error))
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
            uiRefreshRate = UiRefreshRate.valueOf(sp.getString(KEY_UI_REFRESH_RATE, UiRefreshRate.HZ_10.name) ?: UiRefreshRate.HZ_10.name)
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