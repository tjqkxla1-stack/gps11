package kr.koseco.gandansurvey.v90.gnss

import kr.koseco.gandansurvey.v90.model.FixQuality
import kr.koseco.gandansurvey.v90.model.GnssStatus
import kr.koseco.gandansurvey.v90.model.SatelliteDetail
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class NmeaParser {

    private var currentStatus = GnssStatus()
    private val satMap = mutableMapOf<Int, SatelliteDetail>()
    private var lastValidGgaTimeMs: Long = 0L

    fun reset() {
        currentStatus = GnssStatus()
        satMap.clear()
        lastValidGgaTimeMs = 0L
    }

    private fun isValidNmeaChecksum(sentence: String): Boolean {
        val trimmed = sentence.trim()
        if (!trimmed.startsWith("$")) return false
        val starIdx = trimmed.lastIndexOf('*')
        if (starIdx <= 1 || starIdx + 3 > trimmed.length) return false
        val expected = trimmed.substring(starIdx + 1, starIdx + 3).toIntOrNull(16) ?: return false
        var actual = 0
        for (i in 1 until starIdx) {
            actual = actual xor trimmed[i].code
        }
        return actual == expected
    }

    fun parseLine(rawLine: String): GnssStatus? {
        val line = rawLine.trim()
        if (!line.startsWith("$")) return null
        if (!isValidNmeaChecksum(line)) return null

        val clean = line.substringBefore("*")
        val parts = clean.split(",")
        if (parts.isEmpty()) return null
        val type = parts[0].uppercase()

        try {
            when {
                type.endsWith("GGA") -> {
                    if (parts.size >= 10) {
                        val lat = parseNmeaLatLon(parts.getOrNull(2) ?: "", parts.getOrNull(3) ?: "N")
                        val lon = parseNmeaLatLon(parts.getOrNull(4) ?: "", parts.getOrNull(5) ?: "E")
                        val qualCode = parts.getOrNull(6)?.toIntOrNull() ?: 0
                        val satsUsed = parts.getOrNull(7)?.toIntOrNull() ?: currentStatus.satellitesUsed
                        val hdopVal = parts.getOrNull(8)?.toDoubleOrNull() ?: currentStatus.hdop
                        val orthoH = parts.getOrNull(9)?.toDoubleOrNull() ?: 0.0
                        val geoidSep = parts.getOrNull(11)?.toDoubleOrNull() ?: currentStatus.nmeaGeoidSeparation
                        val diffAge = parts.getOrNull(13)?.toDoubleOrNull() ?: 0.0
                        val baseId = parts.getOrNull(14) ?: ""
                        val ellipH = orthoH + geoidSep
                        val fixQual = FixQuality.fromCode(qualCode)

                        if (lat != 0.0 && lon != 0.0) {
                            lastValidGgaTimeMs = System.currentTimeMillis()
                            val estHrms = if (currentStatus.hrms > 0.0 && currentStatus.hrms < 5.0) {
                                currentStatus.hrms
                            } else {
                                when (fixQual) {
                                    FixQuality.RTK_FIXED -> 0.008
                                    FixQuality.RTK_FLOAT -> 0.180
                                    FixQuality.DGPS -> 0.450
                                    FixQuality.SINGLE -> 1.500
                                    else -> 9.999
                                }
                            }
                            val estVrms = if (currentStatus.vrms > 0.0 && currentStatus.vrms < 5.0) {
                                currentStatus.vrms
                            } else {
                                estHrms * 1.6
                            }
                            currentStatus = currentStatus.copy(
                                latitude = lat,
                                longitude = lon,
                                fixQuality = fixQual,
                                satellitesUsed = satsUsed,
                                satellitesTracked = maxOf(currentStatus.satellitesTracked, satsUsed, satMap.size),
                                hdop = hdopVal,
                                ellipsoidalHeight = ellipH,
                                nmeaGeoidSeparation = geoidSep,
                                diffAgeSec = diffAge,
                                baseStationId = baseId,
                                hrms = estHrms,
                                vrms = estVrms,
                                lastNmeaSentence = clean
                            )
                            return currentStatus
                        }
                    }
                }
                type.endsWith("RMC") -> {
                    // GGA 정밀 좌표가 수신 중일 때는 RMC의 보조 좌표로 덮어쓰지 않고 속도/방향만 갱신 (6~10m 점프 차단!)
                    if (parts.size >= 9) {
                        val knots = parts.getOrNull(7)?.toDoubleOrNull() ?: 0.0
                        val course = parts.getOrNull(8)?.toDoubleOrNull() ?: currentStatus.headingDeg
                        val ggaActive = (System.currentTimeMillis() - lastValidGgaTimeMs) < 3500L
                        if (ggaActive) {
                            currentStatus = currentStatus.copy(
                                speedKmh = knots * 1.852,
                                headingDeg = course
                            )
                            return null
                        } else {
                            val lat = parseNmeaLatLon(parts.getOrNull(3) ?: "", parts.getOrNull(4) ?: "N")
                            val lon = parseNmeaLatLon(parts.getOrNull(5) ?: "", parts.getOrNull(6) ?: "E")
                            if (lat != 0.0 && lon != 0.0) {
                                currentStatus = currentStatus.copy(
                                    latitude = lat,
                                    longitude = lon,
                                    speedKmh = knots * 1.852,
                                    headingDeg = course,
                                    lastNmeaSentence = clean
                                )
                                return currentStatus
                            }
                        }
                    }
                }
                type.endsWith("GST") -> {
                    if (parts.size >= 9) {
                        val sigmaLat = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.01
                        val sigmaLon = parts.getOrNull(7)?.toDoubleOrNull() ?: 0.01
                        val sigmaAlt = parts.getOrNull(8)?.toDoubleOrNull() ?: 0.02
                        val hrms = hypot(sigmaLat, sigmaLon)
                        currentStatus = currentStatus.copy(
                            hrms = hrms,
                            vrms = sigmaAlt
                        )
                        return currentStatus
                    }
                }
                type.endsWith("GSA") -> {
                    if (parts.size >= 18) {
                        val pdop = parts.getOrNull(15)?.toDoubleOrNull() ?: currentStatus.pdop
                        val hdop = parts.getOrNull(16)?.toDoubleOrNull() ?: currentStatus.hdop
                        val vdop = parts.getOrNull(17)?.toDoubleOrNull() ?: currentStatus.vdop
                        currentStatus = currentStatus.copy(pdop = pdop, hdop = hdop, vdop = vdop)
                    }
                }
                type.endsWith("GSV") -> {
                    val talker = type.take(3)
                    val constellation = when {
                        talker.contains("GP") -> "GPS"
                        talker.contains("GL") -> "GLONASS"
                        talker.contains("GA") -> "Galileo"
                        talker.contains("GB") || talker.contains("BD") -> "BeiDou"
                        else -> "GNSS"
                    }
                    var idx = 4
                    while (idx + 3 < parts.size) {
                        val prn = parts.getOrNull(idx)?.toIntOrNull()
                        val elev = parts.getOrNull(idx + 1)?.toIntOrNull() ?: 0
                        val azim = parts.getOrNull(idx + 2)?.toIntOrNull() ?: 0
                        val snr = parts.getOrNull(idx + 3)?.toIntOrNull() ?: 0
                        if (prn != null && prn > 0) {
                            satMap[prn] = SatelliteDetail(
                                prn = prn,
                                constellation = constellation,
                                elevationDeg = elev,
                                azimuthDeg = azim,
                                snrDb = snr,
                                usedInFix = snr > 0
                            )
                        }
                        idx += 4
                    }
                    currentStatus = currentStatus.copy(
                        satellitesTracked = maxOf(currentStatus.satellitesUsed, satMap.size),
                        satelliteList = satMap.values.sortedByDescending { it.snrDb }.take(32)
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseNmeaLatLon(raw: String, hemi: String): Double {
        if (raw.isBlank()) return 0.0
        val dotIdx = raw.indexOf('.')
        if (dotIdx < 2) return 0.0
        val degPart = raw.substring(0, dotIdx - 2).toDoubleOrNull() ?: return 0.0
        val minPart = raw.substring(dotIdx - 2).toDoubleOrNull() ?: return 0.0
        var dec = degPart + (minPart / 60.0)
        if (hemi.equals("S", true) || hemi.equals("W", true)) {
            dec = -dec
        }
        return dec
    }

    fun computeTiltCorrection(
        tiltAngleDeg: Double,
        tiltAzimuthDeg: Double,
        antennaHeightMeters: Double
    ): Triple<Double, Double, Double> {
        if (tiltAngleDeg <= 0.05 || antennaHeightMeters <= 0.0) {
            return Triple(0.0, 0.0, 0.0)
        }
        val tiltRad = Math.toRadians(tiltAngleDeg.coerceIn(0.0, 60.0))
        val azRad = Math.toRadians(tiltAzimuthDeg)
        val horizShift = antennaHeightMeters * sin(tiltRad)
        // 안테나 상단이 기울어진 반대 방향에 폴대 끝(팁)이 위치함
        val dN = -horizShift * cos(azRad)
        val dE = -horizShift * sin(azRad)
        val dH = antennaHeightMeters * (1.0 - cos(tiltRad))
        return Triple(dN, dE, dH)
    }
}
