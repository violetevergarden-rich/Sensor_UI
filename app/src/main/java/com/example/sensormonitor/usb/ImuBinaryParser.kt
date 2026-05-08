package com.example.sensormonitor.usb

class ImuBinaryParser {

    private var state = ParserState.WAITING_FOR_HEADER
    private var frameType: Int = 0
    private var dataBytes = mutableListOf<Byte>()
    private var checksum = 0

    private enum class ParserState {
        WAITING_FOR_HEADER,
        READING_TYPE,
        READING_DATA,
        VERIFYING_CHECKSUM,
    }

    /**
     * Feed raw bytes from the serial port. Returns a list of fully parsed frames (usually 0-1).
     */
    fun feed(bytes: ByteArray): List<ImuFrameData> {
        val results = mutableListOf<ImuFrameData>()
        for (b in bytes) {
            val byte = b.toInt() and 0xFF
            when (state) {
                ParserState.WAITING_FOR_HEADER -> {
                    if (byte == 0x55) {
                        checksum = byte
                        state = ParserState.READING_TYPE
                    }
                }

                ParserState.READING_TYPE -> {
                    checksum = (checksum + byte) and 0xFF
                    frameType = byte
                    dataBytes.clear()
                    // Types 0x56 and 0x57 use 32-bit fields, but the frame still has 8 data bytes
                    state = ParserState.READING_DATA
                }

                ParserState.READING_DATA -> {
                    checksum = (checksum + byte) and 0xFF
                    dataBytes.add(b)
                    if (dataBytes.size == 8) {
                        state = ParserState.VERIFYING_CHECKSUM
                    }
                }

                ParserState.VERIFYING_CHECKSUM -> {
                    if (byte == checksum) {
                        val frame = parseFrame(frameType, dataBytes.toByteArray())
                        if (frame != null) results.add(frame)
                    }
                    state = ParserState.WAITING_FOR_HEADER
                    checksum = 0
                }
            }
        }
        return results
    }

    private fun parseFrame(type: Int, data: ByteArray): ImuFrameData? {
        return when (type) {
            0x50 -> parseTime(data)
            0x51 -> parseAcc(data)
            0x52 -> parseGyro(data)
            0x53 -> parseAngle(data)
            0x54 -> parseMag(data)
            0x56 -> parsePress(data)
            0x57 -> parseImuGps(data)
            0x58 -> parseGpsVelocity(data)
            0x59 -> parseQuaternion(data)
            0x5A -> parseGsa(data)
            else -> null
        }
    }

    private fun readI16(data: ByteArray, offset: Int): Int {
        val low = data[offset].toInt() and 0xFF
        val high = data[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun signI16(raw: Int, maxVal: Int): Int =
        if (raw >= maxVal) raw - 2 * maxVal else raw

    private fun readI32(data: ByteArray, offset: Int): Long {
        val b0 = data[offset].toLong() and 0xFF
        val b1 = data[offset + 1].toLong() and 0xFF
        val b2 = data[offset + 2].toLong() and 0xFF
        val b3 = data[offset + 3].toLong() and 0xFF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun parseTime(data: ByteArray): ImuFrameData {
        return ImuFrameData(
            timestamp = ImuTimestamp(
                year = readI16(data, 0),
                month = readI16(data, 2),
                day = readI16(data, 4),
                hour = readI16(data, 6),
                minute = 0,
                second = 0,
                millisecond = 0,
            )
        )
    }

    private fun parseAcc(data: ByteArray): ImuFrameData {
        val kAcc = 16.0f
        val rawX = signI16(readI16(data, 0), 32768)
        val rawY = signI16(readI16(data, 2), 32768)
        val rawZ = signI16(readI16(data, 4), 32768)
        val rawTemp = readI16(data, 6)
        return ImuFrameData(
            accelX = rawX / 32768.0f * kAcc,
            accelY = rawY / 32768.0f * kAcc,
            accelZ = rawZ / 32768.0f * kAcc,
            accelTemp = rawTemp / 100.0f,
        )
    }

    private fun parseGyro(data: ByteArray): ImuFrameData {
        val kGyro = 2000.0f
        val rawX = signI16(readI16(data, 0), 32768)
        val rawY = signI16(readI16(data, 2), 32768)
        val rawZ = signI16(readI16(data, 4), 32768)
        val rawVoltage = readI16(data, 6)
        return ImuFrameData(
            gyroX = rawX / 32768.0f * kGyro,
            gyroY = rawY / 32768.0f * kGyro,
            gyroZ = rawZ / 32768.0f * kGyro,
            gyroVoltage = rawVoltage / 1000.0f,
        )
    }

    private fun parseAngle(data: ByteArray): ImuFrameData {
        val kAngle = 180.0f
        val rawX = signI16(readI16(data, 0), 32768)
        val rawY = signI16(readI16(data, 2), 32768)
        val rawZ = signI16(readI16(data, 4), 32768)
        val version = readI16(data, 6)
        return ImuFrameData(
            roll = rawX / 32768.0f * kAngle,
            pitch = rawY / 32768.0f * kAngle,
            yaw = rawZ / 32768.0f * kAngle,
            angleVersion = version,
        )
    }

    private fun parseMag(data: ByteArray): ImuFrameData {
        val rawX = signI16(readI16(data, 0), 32768)
        val rawY = signI16(readI16(data, 2), 32768)
        val rawZ = signI16(readI16(data, 4), 32768)
        val rawTemp = readI16(data, 6)
        return ImuFrameData(
            magX = rawX / 32768.0f * 2.0f,
            magY = rawY / 32768.0f * 2.0f,
            magZ = rawZ / 32768.0f * 2.0f,
            magTemp = rawTemp / 100.0f,
        )
    }

    private fun parsePress(data: ByteArray): ImuFrameData {
        val pressure = readI32(data, 0)
        val heightCm = readI32(data, 4)
        return ImuFrameData(
            pressure = pressure.toDouble(),
            height = heightCm / 100.0,
        )
    }

    private fun parseImuGps(data: ByteArray): ImuFrameData {
        val lonRaw = readI32(data, 0)
        val latRaw = readI32(data, 4)
        val lonDeg = lonRaw / 10000000.0
        val latDeg = latRaw / 10000000.0
        val lonDecimal = (lonDeg.toInt() + (lonDeg - lonDeg.toInt()) * 100.0 / 60.0)
        val latDecimal = (latDeg.toInt() + (latDeg - latDeg.toInt()) * 100.0 / 60.0)
        return ImuFrameData(
            imuGpsLongitude = lonDecimal,
            imuGpsLatitude = latDecimal,
        )
    }

    private fun parseGpsVelocity(data: ByteArray): ImuFrameData {
        val rawHeight = readI16(data, 0)
        val rawYaw = readI16(data, 2)
        val rawVel = readI16(data, 4)
        return ImuFrameData(
            gpsHeight = rawHeight / 10.0f,
            gpsYaw = rawYaw / 100.0f,
            gpsVelocity = rawVel / 1000.0f,
        )
    }

    private fun parseQuaternion(data: ByteArray): ImuFrameData {
        val rawQ0 = signI16(readI16(data, 0), 32768)
        val rawQ1 = signI16(readI16(data, 2), 32768)
        val rawQ2 = signI16(readI16(data, 4), 32768)
        val rawQ3 = signI16(readI16(data, 6), 32768)
        return ImuFrameData(
            quatW = rawQ0 / 32768.0f,
            quatX = rawQ1 / 32768.0f,
            quatY = rawQ2 / 32768.0f,
            quatZ = rawQ3 / 32768.0f,
        )
    }

    private fun parseGsa(data: ByteArray): ImuFrameData {
        val svCount = readI16(data, 0)
        val pdop = readI16(data, 2)
        val hdop = readI16(data, 4)
        val vdop = readI16(data, 6)
        return ImuFrameData(
            svCount = svCount,
            pdop = pdop / 100.0f,
            hdop = hdop / 100.0f,
            vdop = vdop / 100.0f,
        )
    }
}
