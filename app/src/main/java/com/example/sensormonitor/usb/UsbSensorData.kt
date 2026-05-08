package com.example.sensormonitor.usb

data class NmeaGpsData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val speed: Float? = null,
    val course: Float? = null,
    val fixQuality: Int? = null,
    val svCount: Int? = null,
    val hdop: Float? = null,
    val pdop: Float? = null,
    val vdop: Float? = null,
    val utcTime: String? = null,
)

data class ImuTimestamp(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millisecond: Int,
)

data class ImuFrameData(
    // 0x50 TIME
    val timestamp: ImuTimestamp? = null,
    // 0x51 ACC
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val accelTemp: Float? = null,
    // 0x52 GYRO
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val gyroVoltage: Float? = null,
    // 0x53 ANGLE
    val roll: Float? = null,
    val pitch: Float? = null,
    val yaw: Float? = null,
    val angleVersion: Int? = null,
    // 0x54 MAG
    val magX: Float? = null,
    val magY: Float? = null,
    val magZ: Float? = null,
    val magTemp: Float? = null,
    // 0x56 PRESS
    val pressure: Double? = null,
    val height: Double? = null,
    // 0x57 IMU GPS (lon/lat in ddmm.mmmmm format converted to decimal)
    val imuGpsLongitude: Double? = null,
    val imuGpsLatitude: Double? = null,
    // 0x58 GPS VELOCITY
    val gpsHeight: Float? = null,
    val gpsYaw: Float? = null,
    val gpsVelocity: Float? = null,
    // 0x59 QUATERNION
    val quatW: Float? = null,
    val quatX: Float? = null,
    val quatY: Float? = null,
    val quatZ: Float? = null,
    // 0x5A GSA
    val svCount: Int? = null,
    val pdop: Float? = null,
    val hdop: Float? = null,
    val vdop: Float? = null,
)
