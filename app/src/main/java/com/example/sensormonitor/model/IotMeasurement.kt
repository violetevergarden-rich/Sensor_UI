package com.example.sensormonitor.model

import java.time.LocalDateTime

data class IotMeasurement(
    val recordTime: LocalDateTime,
    val deviceId: String,
    val deviceName: String,
    val speed: Double?,
    val angle: Double?,
    val distance: Double?
)
