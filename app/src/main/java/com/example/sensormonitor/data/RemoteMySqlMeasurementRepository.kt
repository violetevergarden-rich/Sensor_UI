package com.example.sensormonitor.data

import android.util.Log
import com.example.sensormonitor.model.IotMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.format.DateTimeFormatter

object RemoteMySqlMeasurementRepository {
    private const val TAG = "RemoteMySqlRepo"

    private const val INGEST_API_URL = "http://47.104.147.148/sensor-api/measurements"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private val sqlDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun insertMeasurement(measurement: IotMeasurement): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("record_time", measurement.recordTime.format(sqlDateTimeFormatter))
                put("device_id", measurement.deviceId)
                put("device_name", measurement.deviceName)
                put("path_id", measurement.pathId)
                put("latitude", measurement.latitude)
                put("longitude", measurement.longitude)
                put("altitude", measurement.altitude)
                put("accel_x", measurement.accelX)
                put("accel_y", measurement.accelY)
                put("accel_z", measurement.accelZ)
                put("gyro_x", measurement.gyroX)
                put("gyro_y", measurement.gyroY)
                put("gyro_z", measurement.gyroZ)
                put("pitch", measurement.pitch)
                put("roll", measurement.roll)
                put("yaw", measurement.yaw)
                put("speed", measurement.speed)
                put("mag_x", measurement.magX)
                put("mag_y", measurement.magY)
                put("mag_z", measurement.magZ)
                put("pressure", measurement.pressure)
                put("height", measurement.height)
                put("quat_w", measurement.quatW)
                put("quat_x", measurement.quatX)
                put("quat_y", measurement.quatY)
                put("quat_z", measurement.quatZ)
                put("sv_count", measurement.svCount)
                put("pdop", measurement.pdop)
                put("hdop", measurement.hdop)
                put("vdop", measurement.vdop)
            }

            val connection = URL(INGEST_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val responseText = try {
                if (statusCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }
            } catch (_: Exception) {
                ""
            }
            connection.disconnect()

            if (statusCode !in 200..299) {
                return@withContext Result.failure(
                    IOException("Upload failed with HTTP $statusCode: $responseText")
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "insertMeasurement failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
