package com.example.sensormonitor.model

import java.time.LocalDateTime

data class IotMeasurement(
    val recordTime: LocalDateTime,
    val deviceId: String,
    val deviceName: String,
    val pathId: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val accelX: Double?,
    val accelY: Double?,
    val accelZ: Double?,
    val gyroX: Double?,
    val gyroY: Double?,
    val gyroZ: Double?,
    val pitch: Double?,
    val roll: Double?,
    val yaw: Double?,
    val speed: Double?,
)
