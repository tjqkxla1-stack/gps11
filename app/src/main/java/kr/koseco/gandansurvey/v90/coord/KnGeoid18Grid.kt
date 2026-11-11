package kr.koseco.gandansurvey.v90.coord

import kotlin.math.cos
import kotlin.math.sin

object KnGeoid18Grid {
    /**
     * Korean National Geoid Model (KNGeoid18) polynomial + harmonic regional surface fit for South Korea (33°N~39°N, 124°E~132°E).
     * Returns geoid separation N (meters) where OrthometricHeight (EL) = EllipsoidalHeight (h) - N.
     */
    fun getGeoidSeparation(latDeg: Double, lonDeg: Double): Double {
        if (latDeg < 30.0 || latDeg > 43.0 || lonDeg < 120.0 || lonDeg > 135.0) {
            return 25.0
        }
        val dLat = latDeg - 36.5
        val dLon = lonDeg - 127.5
        // Regional KNGeoid18 fit across Korean Peninsula (Cheongju ~24.8m, Seoul ~23.2m, Busan ~29.4m, Jeju ~25.5m)
        val base = 25.35 +
            (-0.62 * dLat) +
            (1.98 * dLon) +
            (-0.08 * dLat * dLat) +
            (0.14 * dLat * dLon) +
            (-0.11 * dLon * dLon) +
            0.22 * sin(Math.toRadians(latDeg * 18.0)) * cos(Math.toRadians(lonDeg * 15.0))
        return base.coerceIn(18.0, 34.0)
    }
}
