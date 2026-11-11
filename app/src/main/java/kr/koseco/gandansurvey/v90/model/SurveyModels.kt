package kr.koseco.gandansurvey.v90.model

import java.util.UUID

enum class KoreanCoordinateSystem(
    val displayName: String,
    val epsgCode: Int,
    val centralMeridianDeg: Double,
    val latitudeOfOriginDeg: Double,
    val falseNorthing: Double,
    val falseEasting: Double,
    val scaleFactor: Double,
    val isBessel: Boolean = false
) {
    WEST_GRS80("서부원점 (GRS80, 125°E, 가산 60만)", 5185, 125.0, 38.0, 600000.0, 200000.0, 1.0, false),
    CENTRAL_GRS80("중부원점 (GRS80, 127°E, 가산 60만)", 5186, 127.0, 38.0, 600000.0, 200000.0, 1.0, false),
    EAST_GRS80("동부원점 (GRS80, 129°E, 가산 60만)", 5187, 129.0, 38.0, 600000.0, 200000.0, 1.0, false),
    EAST_SEA_GRS80("동해원점 (GRS80, 131°E, 가산 60만)", 5188, 131.0, 38.0, 600000.0, 200000.0, 1.0, false),
    CENTRAL_GRS80_500K("중부원점 (구 GRS80, 127°E, 가산 50만)", 5181, 127.0, 38.0, 500000.0, 200000.0, 1.0, false),
    JEJU_GRS80_550K("제주원점 (구 GRS80, 127°E, 가산 55만)", 5182, 127.0, 38.0, 550000.0, 200000.0, 1.0, false),
    CENTRAL_BESSEL("중부원점 (구 베셀 1841, 127°00'10.405\", 50만)", 5174, 127.00289027777778, 38.0, 500000.0, 200000.0, 1.0, true),
    WEST_BESSEL("서부원점 (구 베셀 1841, 125°00'10.405\", 50만)", 5173, 125.00289027777778, 38.0, 500000.0, 200000.0, 1.0, true),
    EAST_BESSEL("동부원점 (구 베셀 1841, 129°00'10.405\", 50만)", 5176, 129.00289027777778, 38.0, 500000.0, 200000.0, 1.0, true),
    UTM_K_GRS80("국가단일원점 (UTM-K GRS80, 127.5°E)", 5179, 127.5, 38.0, 2000000.0, 1000000.0, 0.9996, false)
}

enum class RequiredFixMode(val koreanLabel: String, val shortLabel: String) {
    RTK_FIXED_ONLY("RTK 고정해(FIX)만 허용", "FIX"),
    RTK_FLOAT_OR_BETTER("RTK 실수해(FLOAT) 이상 허용", "FLOAT"),
    ALL_VALID_FIX("DGPS · 단독(휴대폰GPS) 모두 허용", "DGPS")
}

enum class FixQuality(val code: Int, val koreanLabel: String, val shortBadge: String, val colorHex: Long) {
    NO_FIX(0, "NO FIX (미수신)", "NO FIX", 0xFFEF4444),
    SINGLE(1, "단독측위 (SINGLE)", "SINGLE", 0xFFF97316),
    DGPS(2, "DGPS (차분보정)", "DGPS", 0xFFFACC15),
    PPS(3, "PPS", "PPS", 0xFF38BDF8),
    RTK_FIXED(4, "RTK 고정해 (FIXED)", "RTK FIXED", 0xFF22C55E),
    RTK_FLOAT(5, "RTK 실수해 (FLOAT)", "FLOAT", 0xFFF59E0B);

    companion object {
        fun fromCode(code: Int): FixQuality = entries.find { it.code == code } ?: NO_FIX
    }
}

enum class PositioningSourceMode(val koreanLabel: String, val shortLabel: String) {
    BLUETOOTH_GNSS("블루투스 RTK 수신기 (V90 등)", "블루투스GPS"),
    PHONE_INTERNAL_GPS("스마트폰 내장 GPS", "내장GPS"),
    SIMULATION("현장 시뮬레이션 (데모)", "시뮬레이션")
}

data class SatelliteDetail(
    val prn: Int,
    val constellation: String,
    val elevationDeg: Int,
    val azimuthDeg: Int,
    val snrDb: Int,
    val usedInFix: Boolean = true
)

data class GnssStatus(
    val sourceMode: PositioningSourceMode = PositioningSourceMode.BLUETOOTH_GNSS,
    val btConnected: Boolean = false,
    val connectedDeviceName: String = "미연결",
    val fixQuality: FixQuality = FixQuality.NO_FIX,
    val satellitesUsed: Int = 0,
    val satellitesTracked: Int = 0,
    val hdop: Double = 99.9,
    val pdop: Double = 99.9,
    val vdop: Double = 99.9,
    val hrms: Double = 0.0,
    val vrms: Double = 0.0,
    val diffAgeSec: Double = 0.0,
    val baseStationId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val ellipsoidalHeight: Double = 0.0,
    val nmeaGeoidSeparation: Double = 0.0,
    val speedKmh: Double = 0.0,
    val headingDeg: Double = 0.0,
    val receiverBatteryPercent: Int = 100,
    val ntripConnected: Boolean = false,
    val ntripStatusMessage: String = "대기중",
    val rtcmBytesPerSec: Int = 0,
    val imuTiltActive: Boolean = false,
    val imuTiltAngleDeg: Double = 0.0,
    val imuTiltAzimuthDeg: Double = 0.0,
    val satelliteList: List<SatelliteDetail> = emptyList(),
    val lastNmeaSentence: String = ""
)

data class PlaneCoordinate(
    val northing: Double = 0.0,
    val easting: Double = 0.0,
    val orthometricHeight: Double = 0.0,
    val geoidSeparation: Double = 0.0,
    val convergenceDeg: Double = 0.0,
    val pointScaleFactor: Double = 1.0
)

data class SurveyPoint(
    val id: String = UUID.randomUUID().toString(),
    val seqNumber: Int,
    val name: String,
    val code: String,
    val northing: Double,
    val easting: Double,
    val elevation: Double,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val ellipsoidalHeight: Double = 0.0,
    val geoidSeparation: Double = 0.0,
    val antennaHeight: Double = 1.800,
    val fixQuality: FixQuality = FixQuality.RTK_FIXED,
    val hrms: Double = 0.010,
    val vrms: Double = 0.015,
    val epochCount: Int = 1,
    val timestamp: String = "",
    val memo: String = "",
    val photoPath: String = ""
)

data class CadPolyline(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val pointIds: List<String>,
    val isClosed: Boolean = false,
    val colorHex: Long = 0xFF38BDF8
)

enum class CadEntityType {
    POINT,
    LINE,
    POLYLINE,
    CIRCLE,
    ARC,
    TEXT
}

data class CadLayerEntity(
    val id: String = UUID.randomUUID().toString(),
    val layerName: String,
    val type: CadEntityType,
    val verticesNE: List<Pair<Double, Double>>,
    val elevationZ: Double = 0.0,
    val isClosed: Boolean = false,
    val colorHex: Long = 0xFFFACC15,
    val textLabel: String = "",
    val sourceType: String = "DXF" // "DXF", "DWG", "SHP", "VWORLD"
)

enum class CadSnapType(val koreanLabel: String) {
    ENDPOINT("끝점(모서리)"),
    MIDPOINT("중간점"),
    INTERSECTION("교차점"),
    POINT_NODE("측점/노드"),
    NEAREST("근처점(선분위)")
}

data class CadSnapTarget(
    val northing: Double,
    val easting: Double,
    val elevationZ: Double = 0.0,
    val snapType: CadSnapType = CadSnapType.ENDPOINT,
    val layerName: String = "0",
    val label: String = "",
    val distanceToTapMeters: Double = 0.0,
    val segmentStartNE: Pair<Double, Double>? = null,
    val segmentEndNE: Pair<Double, Double>? = null
)

enum class AutoLineMode(val koreanLabel: String, val shortLabel: String) {
    NONE("연결안함 (점만 측량)", "단독점"),
    SAME_CODE("같은 코드점에 연결", "같은코드"),
    PREV_POINT("전점에 연결 (직전 측점)", "전점연결"),
    PREV_PREV_POINT("전전점에 연결 (한 칸 건너)", "전전점연결"),
    CLOSED_POLYGON("같은 코드 폐합(면) 연결", "폐합연결")
}

enum class AutoTrackMode(val koreanLabel: String) {
    OFF("자동측정 꺼짐"),
    TIME_INTERVAL("시간 간격 (일정 초마다 자동기록)"),
    STOP_AND_GO("정지 측정 (일정 시간 정지시 자동기록)"),
    DISTANCE_INTERVAL("거리 간격 (일정 거리 이동시 자동기록)")
}

data class AutoTrackConfig(
    val mode: AutoTrackMode = AutoTrackMode.OFF,
    val timeIntervalSec: Int = 5,
    val stopDurationSec: Int = 3,
    val stopToleranceMeters: Double = 0.05,
    val distanceIntervalMeters: Double = 5.0,
    val useSlopeDistance: Boolean = true
)

enum class CanvasToolMode(val koreanLabel: String) {
    NORMAL("기본 (도면이동/스냅)"),
    MANUAL_LINE_CONNECT("수동 선연결 (측점 2개 이상 터치)"),
    MANUAL_LINE_DELETE("선삭제 (삭제할 선분 터치)"),
    COGO_RULER_AREA("거리·고저차·평수(면적) 자 모드 (측점들을 차례로 터치)"),
    DRAWING_SNAP_SHIFT("🎯 도면·배경 모서리 잡고 측량 현황점으로 일괄 이동")
}

data class CadLayerInfo(
    val name: String,
    val visible: Boolean = true,
    val colorHex: Long = 0xFF38BDF8,
    val isCustom: Boolean = false
)

data class MapDisplayConfig(
    val whiteBackground: Boolean = false,
    val showPointName: Boolean = true,
    val showElevation: Boolean = true,
    val showCode: Boolean = true,
    val decimalPlaces: Int = 3,
    val showTopLatLonDms: Boolean = false,
    val cadastralOverlayEnabled: Boolean = false,
    val showCadText: Boolean = false
)

enum class StakeoutDirectionMode(val koreanLabel: String, val shortLabel: String) {
    NORTH_FIXED("북쪽 고정 기준 (상단=북쪽)", "북쪽기준"),
    MOVING_HEADING("이동/나침반 진행방향 기준", "이동방향"),
    SUN_DIRECTION("태양 방향 기준 (상단→태양)", "태양방향"),
    SUN_OPPOSITE("태양 역방향 기준 (태양 등짐)", "태양역방향")
}

data class StakeoutGuidance(
    val targetPoint: SurveyPoint,
    val targetName: String,
    val deltaN: Double,
    val deltaE: Double,
    val deltaZ: Double,
    val horizontalDistance: Double,
    val slopeDistance: Double,
    val azimuthDeg: Double,
    val forwardMeters: Double,
    val rightMeters: Double,
    val isWithinTolerance: Boolean,
    val linePerpendicularOffsetMeters: Double? = null,
    val lineStationMeters: Double? = null
)

data class QuickCodePreset(
    val code: String,
    val description: String = "",
    val colorHex: Long = 0xFF38BDF8,
    val isLineworkDefault: Boolean = false
)

data class LastMeasurePreset(
    val code: String = "지반",
    val epochs: Int = 1,
    val memo: String = "",
    val autoLineMode: AutoLineMode = AutoLineMode.NONE
)

enum class BackgroundMapType(val koreanLabel: String) {
    NONE("배경지도 없음 (CAD 전용)"),
    GOOGLE_SATELLITE("위성 항공사진 (고해상도)"),
    CADASTRAL_HYBRID("연속지적도 + 하이브리드"),
    OSM_ROAD("일반 도로지도 (OSM)")
}

enum class DxfEncodingMode(val koreanLabel: String, val charsetName: String) {
    CP949_KOREAN("한국어 AutoCAD 호환 (CP949/EUC-KR)", "EUC-KR"),
    UTF8("유니코드 최신 CAD (UTF-8)", "UTF-8")
}

data class ExportColumnConfig(
    val includeSeq: Boolean = true,
    val includeName: Boolean = true,
    val includeN: Boolean = true,
    val includeE: Boolean = true,
    val includeZ: Boolean = true,
    val includeCode: Boolean = true,
    val includeLatLon: Boolean = false,
    val includeTimestamp: Boolean = false,
    val includeMemo: Boolean = true,
    val exportPointMarkerDxf: Boolean = true,
    val exportPointNameTextDxf: Boolean = true,
    val exportElevationTextDxf: Boolean = true,
    val exportCodeTextDxf: Boolean = true,
    val exportPolylineDxf: Boolean = true,
    val textHeightMetersDxf: Double = 0.80
)

enum class ResidualDistributeMode(val koreanLabel: String) {
    UNIFORM("균등 분배 (최소제곱법)"),
    INVERSE_DISTANCE("거리 반비례 가중 분배 (IDW)"),
    NONE("잔차 미분배 (원시 변환계수만 적용)")
}

data class CalibrationPair(
    val id: String = UUID.randomUUID().toString(),
    val pointName: String,
    val knownN: Double,
    val knownE: Double,
    val knownZ: Double,
    val measuredN: Double,
    val measuredE: Double,
    val measuredZ: Double,
    val measuredLat: Double = 0.0,
    val measuredLon: Double = 0.0,
    val measuredEllipH: Double = 0.0,
    val residualN: Double = 0.0,
    val residualE: Double = 0.0,
    val residualZ: Double = 0.0,
    val enabled: Boolean = true
)

data class SiteCalibrationParams(
    val enabled: Boolean = false,
    val originN: Double = 0.0,
    val originE: Double = 0.0,
    val translationN: Double = 0.0,
    val translationE: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val scaleFactor: Double = 1.0,
    val verticalShiftZ: Double = 0.0,
    val slopeNorthPpm: Double = 0.0,
    val slopeEastPpm: Double = 0.0,
    val rmseHorizontal: Double = 0.0,
    val rmseVertical: Double = 0.0,
    val residualDistributeMode: ResidualDistributeMode = ResidualDistributeMode.UNIFORM
)

data class NtripConfig(
    val host: String = "RTS2.ngii.go.kr",
    val port: Int = 2101,
    val mountpoint: String = "VRS-RTCM32",
    val username: String = "qwsaweds",
    val password: String = "ngii",
    val autoSendGgaIntervalSec: Int = 5
)

data class BtDeviceItem(
    val name: String,
    val address: String,
    val isHiTargetV90: Boolean = false,
    val isBonded: Boolean = true,
    val rssi: Int = -60
)

data class SurveyProjectData(
    val projectName: String,
    val coordinateSystem: KoreanCoordinateSystem = KoreanCoordinateSystem.CENTRAL_GRS80,
    val createdAt: String = "",
    val points: List<SurveyPoint> = emptyList(),
    val figures: List<CadPolyline> = emptyList(),
    val cadEntities: List<CadLayerEntity> = emptyList()
)

enum class DesignGradeMode(val koreanLabel: String, val shortLabel: String) {
    OFF("꺼짐", "꺼짐"),
    FLAT_LEVEL("수평 계획고(EL) 기준", "수평계획고(EL)"),
    SLOPE_LINE("두 점 구배(경사선) 기준", "구배(경사선)")
}

data class DesignGradeConfig(
    val mode: DesignGradeMode = DesignGradeMode.OFF,
    val targetElevationZ: Double = 50.000,
    val verticalOffsetMeters: Double = 0.000,
    val toleranceMeters: Double = 0.020,
    val startPointName: String = "시점(A)",
    val startN: Double = 0.0,
    val startE: Double = 0.0,
    val startZ: Double = 50.000,
    val endPointName: String = "종점(B)",
    val endN: Double = 10.0,
    val endE: Double = 0.0,
    val endZ: Double = 49.800,
    val useManualSlopePercent: Boolean = false,
    val manualSlopePercent: Double = -2.00
)

data class DesignGradeGuidance(
    val mode: DesignGradeMode,
    val currentGroundZ: Double,
    val computedDesignZ: Double,
    val verticalOffsetMeters: Double,
    val cutFillDeltaZ: Double,
    val isOnGrade: Boolean,
    val stationMeters: Double = 0.0,
    val totalLineLengthMeters: Double = 0.0,
    val lineOffsetMeters: Double = 0.0,
    val effectiveSlopePercent: Double = 0.0
)

data class SurveyHistorySnapshot(
    val id: String = UUID.randomUUID().toString(),
    val actionLabel: String,
    val timestamp: String,
    val points: List<SurveyPoint>,
    val polylines: List<CadPolyline>,
    val cadEntities: List<CadLayerEntity>,
    val backgroundShiftN: Double = 0.0,
    val backgroundShiftE: Double = 0.0
)

data class CogoRulerSummary(
    val selectedPoints: List<SurveyPoint> = emptyList(),
    val lastSegmentHorizontalMeters: Double = 0.0,
    val lastSegmentSlopeMeters: Double = 0.0,
    val lastSegmentDeltaZ: Double = 0.0,
    val lastSegmentGradePercent: Double = 0.0,
    val lastSegmentAzimuthDeg: Double = 0.0,
    val totalHorizontalLengthMeters: Double = 0.0,
    val totalSlopeLengthMeters: Double = 0.0,
    val areaSqMeters: Double = 0.0,
    val areaPyeong: Double = 0.0
)
