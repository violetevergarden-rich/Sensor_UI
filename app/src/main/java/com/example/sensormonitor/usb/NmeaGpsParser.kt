package com.example.sensormonitor.usb

object NmeaGpsParser {

    fun parse(sentence: String): NmeaGpsData? {
        val s = sentence.trim { it <= ' ' }
        if (!s.startsWith("$")) return null

        val starIdx = s.lastIndexOf('*')
        if (starIdx == -1) return null

        val body = s.substring(1, starIdx)
        val expectedChecksum = s.substring(starIdx + 1)
        if (!checksumValid(body, expectedChecksum)) return null

        val parts = body.split(",")
        if (parts.isEmpty()) return null

        val talkerAndType = parts[0]
        val type = if (talkerAndType.length >= 5) talkerAndType.takeLast(3) else return null

        return when (type) {
            "GGA" -> parseGga(parts)
            "RMC" -> parseRmc(parts)
            "VTG" -> parseVtg(parts)
            "GSA" -> parseGsa(parts)
            "GSV" -> parseGsv(parts)
            else -> null
        }
    }

    private fun checksumValid(body: String, expected: String): Boolean {
        var xor = 0
        for (c in body) xor = xor xor c.code
        return try {
            Integer.parseInt(expected, 16) == xor
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun parseGga(fields: List<String>): NmeaGpsData {
        return NmeaGpsData(
            utcTime = fields.getOrNull(1)?.takeIf { it.isNotEmpty() },
            latitude = parseNmeaCoordinate(fields.getOrNull(2), fields.getOrNull(3)),
            longitude = parseNmeaCoordinate(fields.getOrNull(4), fields.getOrNull(5)),
            fixQuality = fields.getOrNull(6)?.toIntOrNull(),
            svCount = fields.getOrNull(7)?.toIntOrNull(),
            hdop = fields.getOrNull(8)?.toFloatOrNull(),
            altitude = fields.getOrNull(9)?.toDoubleOrNull(),
        )
    }

    private fun parseRmc(fields: List<String>): NmeaGpsData {
        val status = fields.getOrNull(2)
        if (status != "A") return NmeaGpsData()

        return NmeaGpsData(
            utcTime = fields.getOrNull(1)?.takeIf { it.isNotEmpty() },
            latitude = parseNmeaCoordinate(fields.getOrNull(3), fields.getOrNull(4)),
            longitude = parseNmeaCoordinate(fields.getOrNull(5), fields.getOrNull(6)),
            speed = fields.getOrNull(7)?.toFloatOrNull()?.let { it * 0.514444f },
            course = fields.getOrNull(8)?.toFloatOrNull(),
        )
    }

    private fun parseVtg(fields: List<String>): NmeaGpsData {
        return NmeaGpsData(
            course = fields.getOrNull(1)?.toFloatOrNull(),
            speed = fields.getOrNull(7)?.toFloatOrNull()?.let { it * 0.277778f }
                ?: fields.getOrNull(5)?.toFloatOrNull()?.let { it * 0.514444f },
        )
    }

    private fun parseGsa(fields: List<String>): NmeaGpsData {
        return NmeaGpsData(
            pdop = fields.getOrNull(15)?.toFloatOrNull(),
            hdop = fields.getOrNull(16)?.toFloatOrNull(),
            vdop = fields.getOrNull(17)?.toFloatOrNull(),
        )
    }

    private fun parseGsv(fields: List<String>): NmeaGpsData {
        // Only extract total SV count from the first message in the group
        val msgNum = fields.getOrNull(2)?.toIntOrNull() ?: return NmeaGpsData()
        if (msgNum != 1) return NmeaGpsData()
        return NmeaGpsData(
            svCount = fields.getOrNull(3)?.toIntOrNull(),
        )
    }

    private fun parseNmeaCoordinate(raw: String?, hemi: String?): Double? {
        val r = raw ?: return null
        val h = hemi ?: return null
        if (r.isEmpty() || h.isEmpty()) return null

        val dotIdx = r.indexOf('.')
        if (dotIdx == -1) return null

        val degLen = dotIdx - 2
        if (degLen < 2) return null

        val degrees = r.substring(0, degLen).toDoubleOrNull() ?: return null
        val minutes = r.substring(degLen).toDoubleOrNull() ?: return null
        var decimal = degrees + minutes / 60.0

        if (h == "S" || h == "W") decimal = -decimal
        return decimal
    }
}
