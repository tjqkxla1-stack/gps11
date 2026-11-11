package kr.koseco.gandansurvey.v90.coord

import kr.koseco.gandansurvey.v90.model.CadEntityType
import kr.koseco.gandansurvey.v90.model.CadLayerEntity
import kr.koseco.gandansurvey.v90.model.CadSnapTarget
import kr.koseco.gandansurvey.v90.model.CadSnapType
import kr.koseco.gandansurvey.v90.model.KoreanCoordinateSystem
import kr.koseco.gandansurvey.v90.model.PlaneCoordinate
import kr.koseco.gandansurvey.v90.model.SurveyPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object CoordinateConverter {

    private const val GRS80_A = 6378137.0
    private const val GRS80_F = 1.0 / 298.257222101

    private const val BESSEL_A = 6377397.155
    private const val BESSEL_F = 1.0 / 299.1528128

    fun wgs84ToPlane(
        latDeg: Double,
        lonDeg: Double,
        ellipsoidalHeight: Double = 0.0,
        system: KoreanCoordinateSystem = KoreanCoordinateSystem.CENTRAL_GRS80,
        useKnGeoid18: Boolean = true,
        nmeaGeoidFallback: Double = 0.0
    ): PlaneCoordinate {
        if (latDeg == 0.0 && lonDeg == 0.0) {
            return PlaneCoordinate()
        }

        val a = if (system.isBessel) BESSEL_A else GRS80_A
        val f = if (system.isBessel) BESSEL_F else GRS80_F
        val e2 = 2.0 * f - f * f
        val ep2 = e2 / (1.0 - e2)

        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg)
        val phi0 = Math.toRadians(system.latitudeOfOriginDeg)
        val lam0 = Math.toRadians(system.centralMeridianDeg)
        val k0 = system.scaleFactor

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val nVal = a / sqrt(1.0 - e2 * sinPhi * sinPhi)
        val tVal = tanPhi * tanPhi
        val cVal = ep2 * cosPhi * cosPhi
        val aVal = (lam - lam0) * cosPhi

        val mVal = meridionalArc(phi, a, e2)
        val m0Val = meridionalArc(phi0, a, e2)

        val a2 = aVal * aVal
        val a3 = a2 * aVal
        val a4 = a2 * a2
        val a5 = a4 * aVal
        val a6 = a4 * a2

        val easting = system.falseEasting + k0 * nVal * (
            aVal +
                (1.0 - tVal + cVal) * (a3 / 6.0) +
                (5.0 - 18.0 * tVal + tVal * tVal + 72.0 * cVal - 58.0 * ep2) * (a5 / 120.0)
            )

        val northing = system.falseNorthing + k0 * (
            (mVal - m0Val) +
                nVal * tanPhi * (
                    (a2 / 2.0) +
                        (5.0 - tVal + 9.0 * cVal + 4.0 * cVal * cVal) * (a4 / 24.0) +
                        (61.0 - 58.0 * tVal + tVal * tVal + 600.0 * cVal - 330.0 * ep2) * (a6 / 720.0)
                    )
            )

        val geoidSep = when {
            useKnGeoid18 -> KnGeoid18Grid.getGeoidSeparation(latDeg, lonDeg)
            nmeaGeoidFallback != 0.0 -> nmeaGeoidFallback
            else -> KnGeoid18Grid.getGeoidSeparation(latDeg, lonDeg)
        }
        val orthoH = ellipsoidalHeight - geoidSep
        val convDeg = Math.toDegrees((lam - lam0) * sinPhi)

        return PlaneCoordinate(
            northing = northing,
            easting = easting,
            orthometricHeight = orthoH,
            geoidSeparation = geoidSep,
            convergenceDeg = convDeg,
            pointScaleFactor = k0
        )
    }

    fun planeToWgs84(
        northing: Double,
        easting: Double,
        system: KoreanCoordinateSystem = KoreanCoordinateSystem.CENTRAL_GRS80
    ): Pair<Double, Double> {
        val a = if (system.isBessel) BESSEL_A else GRS80_A
        val f = if (system.isBessel) BESSEL_F else GRS80_F
        val e2 = 2.0 * f - f * f
        val ep2 = e2 / (1.0 - e2)
        val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))

        val phi0 = Math.toRadians(system.latitudeOfOriginDeg)
        val lam0 = Math.toRadians(system.centralMeridianDeg)
        val k0 = system.scaleFactor

        val m0 = meridionalArc(phi0, a, e2)
        val m = m0 + (northing - system.falseNorthing) / k0
        val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0))

        val phi1 = mu +
            (1.5 * e1 - 27.0 * e1.pow(3) / 32.0) * sin(2.0 * mu) +
            (21.0 * e1 * e1 / 16.0 - 55.0 * e1.pow(4) / 32.0) * sin(4.0 * mu) +
            (151.0 * e1.pow(3) / 96.0) * sin(6.0 * mu) +
            (1097.0 * e1.pow(4) / 512.0) * sin(8.0 * mu)

        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)

        val n1 = a / sqrt(1.0 - e2 * sinPhi1 * sinPhi1)
        val r1 = a * (1.0 - e2) / (1.0 - e2 * sinPhi1 * sinPhi1).pow(1.5)
        val t1 = tanPhi1 * tanPhi1
        val c1 = ep2 * cosPhi1 * cosPhi1
        val d = (easting - system.falseEasting) / (n1 * k0)

        val d2 = d * d
        val d3 = d2 * d
        val d4 = d2 * d2
        val d5 = d4 * d
        val d6 = d4 * d2

        val phi = phi1 - (n1 * tanPhi1 / r1) * (
            d2 / 2.0 -
                (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * ep2) * (d4 / 24.0) +
                (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * ep2 - 3.0 * c1 * c1) * (d6 / 720.0)
            )

        val lam = lam0 + (
            d -
                (1.0 + 2.0 * t1 + c1) * (d3 / 6.0) +
                (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * ep2 + 24.0 * t1 * t1) * (d5 / 120.0)
            ) / cosPhi1

        return Pair(Math.toDegrees(phi), Math.toDegrees(lam))
    }

    private fun meridionalArc(phi: Double, a: Double, e2: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        val a0 = 1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0
        val a2 = 3.0 / 8.0 * (e2 + e4 / 4.0 + 15.0 * e6 / 128.0)
        val a4 = 15.0 / 256.0 * (e4 + 3.0 * e6 / 4.0)
        val a6 = 35.0 * e6 / 3072.0
        return a * (a0 * phi - a2 * sin(2.0 * phi) + a4 * sin(4.0 * phi) - a6 * sin(6.0 * phi))
    }

    fun formatDms(deg: Double): String {
        val absDeg = abs(deg)
        val d = absDeg.toInt()
        val minFull = (absDeg - d) * 60.0
        val m = minFull.toInt()
        val s = (minFull - m) * 60.0
        val sign = if (deg < 0) "-" else ""
        return String.format(Locale.US, "%s%d°%02d'%07.4f\"", sign, d, m, s)
    }

    fun findNearestCadSnap(
        tapN: Double,
        tapE: Double,
        entities: List<CadLayerEntity>,
        maxSnapRadiusMeters: Double,
        surveyPoints: List<SurveyPoint> = emptyList(),
        hiddenLayers: Set<String> = emptySet()
    ): CadSnapTarget? {
        var bestEndpoint: CadSnapTarget? = null
        var bestMidpoint: CadSnapTarget? = null
        var bestNearest: CadSnapTarget? = null

        // 1. Check SurveyPoints first (as POINT_NODE)
        for (pt in surveyPoints) {
            if (hiddenLayers.contains(pt.code)) continue
            val d = hypot(pt.northing - tapN, pt.easting - tapE)
            if (d <= maxSnapRadiusMeters) {
                if (bestEndpoint == null || d < bestEndpoint.distanceToTapMeters) {
                    bestEndpoint = CadSnapTarget(
                        northing = pt.northing,
                        easting = pt.easting,
                        elevationZ = pt.elevation,
                        snapType = CadSnapType.POINT_NODE,
                        layerName = pt.code,
                        label = "#${pt.seqNumber} ${pt.name}",
                        distanceToTapMeters = d
                    )
                }
            }
        }

        // 2. Check CAD Entities
        for (ent in entities) {
            if (hiddenLayers.contains(ent.layerName)) continue
            if (ent.type == CadEntityType.TEXT) continue
            val verts = ent.verticesNE
            if (verts.isEmpty()) continue

            for (v in verts) {
                val d = hypot(v.first - tapN, v.second - tapE)
                if (d <= maxSnapRadiusMeters) {
                    if (bestEndpoint == null || d < bestEndpoint.distanceToTapMeters) {
                        bestEndpoint = CadSnapTarget(
                            northing = v.first,
                            easting = v.second,
                            elevationZ = ent.elevationZ,
                            snapType = if (ent.type == CadEntityType.POINT) CadSnapType.POINT_NODE else CadSnapType.ENDPOINT,
                            layerName = ent.layerName,
                            label = ent.textLabel.ifBlank { ent.layerName },
                            distanceToTapMeters = d
                        )
                    }
                }
            }

            if (verts.size >= 2) {
                val segCount = if (ent.isClosed && verts.size >= 3) verts.size else verts.size - 1
                for (i in 0 until segCount) {
                    val a = verts[i]
                    val b = verts[(i + 1) % verts.size]
                    val midN = (a.first + b.first) * 0.5
                    val midE = (a.second + b.second) * 0.5
                    val dMid = hypot(midN - tapN, midE - tapE)
                    if (dMid <= maxSnapRadiusMeters * 0.75) {
                        if (bestMidpoint == null || dMid < bestMidpoint.distanceToTapMeters) {
                            bestMidpoint = CadSnapTarget(
                                northing = midN,
                                easting = midE,
                                elevationZ = ent.elevationZ,
                                snapType = CadSnapType.MIDPOINT,
                                layerName = ent.layerName,
                                label = "${ent.layerName}(중간점)",
                                distanceToTapMeters = dMid,
                                segmentStartNE = a,
                                segmentEndNE = b
                            )
                        }
                    }

                    val dn = b.first - a.first
                    val de = b.second - a.second
                    val lenSq = dn * dn + de * de
                    if (lenSq > 1e-8) {
                        val t = (((tapN - a.first) * dn + (tapE - a.second) * de) / lenSq).coerceIn(0.0, 1.0)
                        val projN = a.first + t * dn
                        val projE = a.second + t * de
                        val dNear = hypot(projN - tapN, projE - tapE)
                        if (dNear <= maxSnapRadiusMeters) {
                            if (bestNearest == null || dNear < bestNearest.distanceToTapMeters) {
                                bestNearest = CadSnapTarget(
                                    northing = projN,
                                    easting = projE,
                                    elevationZ = ent.elevationZ,
                                    snapType = CadSnapType.NEAREST,
                                    layerName = ent.layerName,
                                    label = "${ent.layerName}(선분위)",
                                    distanceToTapMeters = dNear,
                                    segmentStartNE = a,
                                    segmentEndNE = b
                                )
                            }
                        }
                    }
                }
            }
        }

        // Priority: ENDPOINT/POINT_NODE (within 0.85 * maxRadius) > MIDPOINT > NEAREST
        if (bestEndpoint != null && bestEndpoint.distanceToTapMeters <= maxSnapRadiusMeters * 0.85) {
            return bestEndpoint
        }
        if (bestMidpoint != null && (bestEndpoint == null || bestMidpoint.distanceToTapMeters < bestEndpoint.distanceToTapMeters)) {
            return bestMidpoint
        }
        return bestEndpoint ?: bestMidpoint ?: bestNearest
    }
}
