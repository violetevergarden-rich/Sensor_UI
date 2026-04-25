package com.example.sensormonitor.model

enum class SpeedUnit {
    MS,
    KMH
}

enum class AltitudeUnit {
    METER,
    FEET
}

enum class UiRefreshRate {
    HZ_10,
    HZ_30
}

data class AppSettings(
    val chartWindowSize: Int = 150,
    val sampleIntervalMs: Int = 100,
    val autoSaveCsv: Boolean = false,
    val recordIntervalMs: Int = 1000,
    val languageCode: String = "zh",
    val keepScreenOn: Boolean = true,
    val speedUnit: SpeedUnit = SpeedUnit.MS,
    val altitudeUnit: AltitudeUnit = AltitudeUnit.METER,
    val smoothingEnabled: Boolean = false,
    val smoothingAlpha: Float = 0.85f,
    val uiRefreshRate: UiRefreshRate = UiRefreshRate.HZ_10
)
