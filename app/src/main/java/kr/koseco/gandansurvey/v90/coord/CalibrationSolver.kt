package kr.koseco.gandansurvey.v90.coord

import kr.koseco.gandansurvey.v90.model.CalibrationPair
import kr.koseco.gandansurvey.v90.model.KoreanCoordinateSystem
import kr.koseco.gandansurvey.v90.model.PlaneCoordinate
import kr.koseco.gandansurvey.v90.model.ResidualDistributeMode
import kr.koseco.gandansurvey.v90.model.SiteCalibrationParams
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

object CalibrationSolver {

    fun solveHelmert2DAndVertical(
        pairs: List<CalibrationPair>,
        distributeMode: ResidualDistributeMode = ResidualDistributeMode.UNIFORM
    ): Pair<SiteCalibrationParams, List<CalibrationPair>> {
        val active = pairs.filter { it.enabled }
        if (active.isEmpty()) {
            return Pair(SiteCalibrationParams(enabled = false, residualDistributeMode = distributeMode), pairs)
        }

        val meanMeasN = active.sumOf { it.measuredN } / active.size
        val meanMeasE = active.sumOf { it.measuredE } / active.size
        val meanKnownN = active.sumOf { it.knownN } / active.size
        val meanKnownE = active.sumOf { it.knownE } / active.size
        val meanDeltaZ = active.sumOf { it.knownZ - it.measuredZ } / active.size

        if (active.size == 1) {
            val p = active.first()
            val dN = p.knownN - p.measuredN
            val dE = p.knownE - p.measuredE
            val dZ = p.knownZ - p.measuredZ
            val params = SiteCalibrationParams(
                enabled = true,
                originN = p.measuredN,
                originE = p.measuredE,
                translationN = dN,
                translationE = dE,
                rotationDeg = 0.0,
                scaleFactor = 1.0,
                verticalShiftZ = dZ,
                rmseHorizontal = 0.0,
                rmseVertical = 0.0,
                residualDistributeMode = distributeMode
            )
            val updated = pairs.map { it.copy(residualN = 0.0, residualE = 0.0, residualZ = 0.0) }
            return Pair(params, updated)
        }

        var sumNumA = 0.0
        var sumNumB = 0.0
        var sumDen = 0.0

        for (p in active) {
            val dMeasN = p.measuredN - meanMeasN
            val dMeasE = p.measuredE - meanMeasE
            val dKnownN = p.knownN - meanKnownN
            val dKnownE = p.knownE - meanKnownE

            sumNumA += (dMeasE * dKnownE + dMeasN * dKnownN)
            sumNumB += (dMeasE * dKnownN - dMeasN * dKnownE)
            sumDen += (dMeasE * dMeasE + dMeasN * dMeasN)
        }

        val aCoeff = if (sumDen > 1e-9) sumNumA / sumDen else 1.0
        val bCoeff = if (sumDen > 1e-9) sumNumB / sumDen else 0.0
        val scale = hypot(aCoeff, bCoeff).coerceIn(0.995, 1.005)
        val rotRad = atan2(bCoeff, aCoeff)
        val rotDeg = Math.toDegrees(rotRad)

        val transN = meanKnownN - meanMeasN
        val transE = meanKnownE - meanMeasE

        var sumSqH = 0.0
        var sumSqV = 0.0
        val updatedPairs = pairs.map { p ->
            val dMeasN = p.measuredN - meanMeasN
            val dMeasE = p.measuredE - meanMeasE
            val predE = meanKnownE + scale * (dMeasE * cos(rotRad) - dMeasN * sin(rotRad))
            val predN = meanKnownN + scale * (dMeasE * sin(rotRad) + dMeasN * cos(rotRad))
            val predZ = p.measuredZ + meanDeltaZ
            val resN = p.knownN - predN
            val resE = p.knownE - predE
            val resZ = p.knownZ - predZ
            if (p.enabled) {
                sumSqH += (resN * resN + resE * resE)
                sumSqV += (resZ * resZ)
            }
            p.copy(residualN = resN, residualE = resE, residualZ = resZ)
        }

        val rmseH = sqrt(sumSqH / active.size)
        val rmseV = sqrt(sumSqV / active.size)

        val params = SiteCalibrationParams(
            enabled = true,
            originN = meanMeasN,
            originE = meanMeasE,
            translationN = transN,
            translationE = transE,
            rotationDeg = rotDeg,
            scaleFactor = scale,
            verticalShiftZ = meanDeltaZ,
            rmseHorizontal = rmseH,
            rmseVertical = rmseV,
            residualDistributeMode = distributeMode
        )
        return Pair(params, updatedPairs)
    }

    fun applyCalibration(
        raw: PlaneCoordinate,
        params: SiteCalibrationParams,
        pairs: List<CalibrationPair> = emptyList()
    ): PlaneCoordinate {
        if (!params.enabled) return raw
        val rotRad = Math.toRadians(params.rotationDeg)
        val dN = raw.northing - params.originN
        val dE = raw.easting - params.originE
        var calE = params.originE + params.translationE + params.scaleFactor * (dE * cos(rotRad) - dN * sin(rotRad))
        var calN = params.originN + params.translationN + params.scaleFactor * (dE * sin(rotRad) + dN * cos(rotRad))
        var calZ = raw.orthometricHeight + params.verticalShiftZ

        if (params.residualDistributeMode == ResidualDistributeMode.INVERSE_DISTANCE) {
            val active = pairs.filter { it.enabled }
            if (active.isNotEmpty()) {
                var wSum = 0.0
                var wResN = 0.0
                var wResE = 0.0
                var wResZ = 0.0
                for (p in active) {
                    val dist = hypot(raw.northing - p.measuredN, raw.easting - p.measuredE).coerceAtLeast(0.1)
                    val w = 1.0 / (dist * dist)
                    wSum += w
                    wResN += w * p.residualN
                    wResE += w * p.residualE
                    wResZ += w * p.residualZ
                }
                if (wSum > 0.0) {
                    calN += wResN / wSum
                    calE += wResE / wSum
                    calZ += wResZ / wSum
                }
            }
        }

        return raw.copy(
            northing = calN,
            easting = calE,
            orthometricHeight = calZ
        )
    }

    fun buildTrfFileContent(
        system: KoreanCoordinateSystem,
        params: SiteCalibrationParams,
        pairs: List<CalibrationPair>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# GandanSurvey V90 Site Calibration (.trf)")
        sb.appendLine("COORD_SYSTEM=${system.name}")
        sb.appendLine(String.format(Locale.US, "PARAMS=%.6f,%.6f,%.6f,%.6f,%.8f,%.8f,%.6f",
            params.originN, params.originE, params.translationN, params.translationE,
            params.rotationDeg, params.scaleFactor, params.verticalShiftZ))
        for (p in pairs) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "PAIR=%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.8f,%.8f,%.4f,%b",
                    p.pointName, p.knownN, p.knownE, p.knownZ,
                    p.measuredN, p.measuredE, p.measuredZ,
                    p.measuredLat, p.measuredLon, p.measuredEllipH, p.enabled
                )
            )
        }
        return sb.toString()
    }
}
