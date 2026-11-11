package kr.koseco.gandansurvey.v90.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.koseco.gandansurvey.v90.coord.CalibrationSolver
import kr.koseco.gandansurvey.v90.coord.CoordinateConverter
import kr.koseco.gandansurvey.v90.gnss.GnssBluetoothManager
import kr.koseco.gandansurvey.v90.gnss.NmeaParser
import kr.koseco.gandansurvey.v90.gnss.NtripClient
import kr.koseco.gandansurvey.v90.model.*
import kr.koseco.gandansurvey.v90.util.FileIoManager
import kr.koseco.gandansurvey.v90.util.ShpFileParser
import kr.koseco.gandansurvey.v90.util.SoundAndSensorManager
import kr.koseco.gandansurvey.v90.util.TileMapManager
import kr.koseco.gandansurvey.v90.util.VWorldCadastralManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class GandanSurveyViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("gandan_survey_v90_prefs", Context.MODE_PRIVATE)
    val soundSensorManager = SoundAndSensorManager(application)
    private val nmeaParser = NmeaParser()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private val _projectName = MutableStateFlow("간단측량_현장01")
    val projectName: StateFlow<String> = _projectName.asStateFlow()

    private val _savedProjects = MutableStateFlow<List<File>>(emptyList())
    val savedProjects: StateFlow<List<File>> = _savedProjects.asStateFlow()

    private val _savedTrfFiles = MutableStateFlow<List<File>>(emptyList())
    val savedTrfFiles: StateFlow<List<File>> = _savedTrfFiles.asStateFlow()

    private val _sourceMode = MutableStateFlow(PositioningSourceMode.BLUETOOTH_GNSS)
    val sourceMode: StateFlow<PositioningSourceMode> = _sourceMode.asStateFlow()

    private val _requiredFixMode = MutableStateFlow(RequiredFixMode.ALL_VALID_FIX)
    val requiredFixMode: StateFlow<RequiredFixMode> = _requiredFixMode.asStateFlow()

    fun setRequiredFixMode(mode: RequiredFixMode) {
        _requiredFixMode.value = mode
        saveCurrentStateToPrefsOnly()
        postToast("측정 허용 모드: ${mode.koreanLabel}")
    }

    private val _gnssStatus = MutableStateFlow(GnssStatus())
    val gnssStatus: StateFlow<GnssStatus> = _gnssStatus.asStateFlow()

    private val _coordinateSystem = MutableStateFlow(KoreanCoordinateSystem.CENTRAL_GRS80)
    val coordinateSystem: StateFlow<KoreanCoordinateSystem> = _coordinateSystem.asStateFlow()

    private val _useKnGeoid18 = MutableStateFlow(true)
    val useKnGeoid18: StateFlow<Boolean> = _useKnGeoid18.asStateFlow()

    private val _antennaHeight = MutableStateFlow(1.800)
    val antennaHeight: StateFlow<Double> = _antennaHeight.asStateFlow()

    private val _favoriteAntennaHeight = MutableStateFlow(1.800)
    val favoriteAntennaHeight: StateFlow<Double> = _favoriteAntennaHeight.asStateFlow()

    private val _lastSurveyDateYmd = MutableStateFlow("")
    private var confirmedNonFavoriteAntennaToday = false

    private val _nextDayAntennaWarningActive = MutableStateFlow(false)
    val nextDayAntennaWarningActive: StateFlow<Boolean> = _nextDayAntennaWarningActive.asStateFlow()

    private val _currentPlaneCoord = MutableStateFlow(PlaneCoordinate())
    val currentPlaneCoord: StateFlow<PlaneCoordinate> = _currentPlaneCoord.asStateFlow()

    private val _points = MutableStateFlow<List<SurveyPoint>>(emptyList())
    val points: StateFlow<List<SurveyPoint>> = _points.asStateFlow()

    private val _polylines = MutableStateFlow<List<CadPolyline>>(emptyList())
    val polylines: StateFlow<List<CadPolyline>> = _polylines.asStateFlow()

    private val _cadEntities = MutableStateFlow<List<CadLayerEntity>>(emptyList())
    val cadEntities: StateFlow<List<CadLayerEntity>> = _cadEntities.asStateFlow()

    private val _cadSnapEnabled = MutableStateFlow(true)
    val cadSnapEnabled: StateFlow<Boolean> = _cadSnapEnabled.asStateFlow()

    private val _pendingSnappedTarget = MutableStateFlow<CadSnapTarget?>(null)
    val pendingSnappedTarget: StateFlow<CadSnapTarget?> = _pendingSnappedTarget.asStateFlow()

    private val _lastSnappedTarget = MutableStateFlow<CadSnapTarget?>(null)
    val lastSnappedTarget: StateFlow<CadSnapTarget?> = _lastSnappedTarget.asStateFlow()

    private val _canvasToolMode = MutableStateFlow(CanvasToolMode.NORMAL)
    val canvasToolMode: StateFlow<CanvasToolMode> = _canvasToolMode.asStateFlow()

    private val _manualConnectLastPointId = MutableStateFlow<String?>(null)
    val manualConnectLastPointId: StateFlow<String?> = _manualConnectLastPointId.asStateFlow()

    private val _drawingShiftSourceSnap = MutableStateFlow<CadSnapTarget?>(null)
    val drawingShiftSourceSnap: StateFlow<CadSnapTarget?> = _drawingShiftSourceSnap.asStateFlow()

    private val _backgroundShiftNE = MutableStateFlow(Pair(0.0, 0.0))
    val backgroundShiftNE: StateFlow<Pair<Double, Double>> = _backgroundShiftNE.asStateFlow()

    private val _shiftIncludeBackgroundTiles = MutableStateFlow(true)
    val shiftIncludeBackgroundTiles: StateFlow<Boolean> = _shiftIncludeBackgroundTiles.asStateFlow()

    private val _currentViewportCenterNE = MutableStateFlow<Pair<Double, Double>?>(null)
    val currentViewportCenterNE: StateFlow<Pair<Double, Double>?> = _currentViewportCenterNE.asStateFlow()

    private data class TimedCoordSample(
        val timeMs: Long,
        val plane: PlaneCoordinate,
        val gnss: GnssStatus
    )
    private val recentCleanSamples = ArrayDeque<TimedCoordSample>()

    private val _hiddenLayers = MutableStateFlow<Set<String>>(emptySet())
    val hiddenLayers: StateFlow<Set<String>> = _hiddenLayers.asStateFlow()

    private val _customLayers = MutableStateFlow<List<CadLayerInfo>>(emptyList())
    val customLayers: StateFlow<List<CadLayerInfo>> = _customLayers.asStateFlow()

    private val _mapDisplayConfig = MutableStateFlow(MapDisplayConfig())
    val mapDisplayConfig: StateFlow<MapDisplayConfig> = _mapDisplayConfig.asStateFlow()

    private val _autoTrackConfig = MutableStateFlow(AutoTrackConfig())
    val autoTrackConfig: StateFlow<AutoTrackConfig> = _autoTrackConfig.asStateFlow()
    private var autoTrackJob: Job? = null

    private val _backgroundMapType = MutableStateFlow(BackgroundMapType.NONE)
    val backgroundMapType: StateFlow<BackgroundMapType> = _backgroundMapType.asStateFlow()

    private val _backgroundMapAlpha = MutableStateFlow(0.82f)
    val backgroundMapAlpha: StateFlow<Float> = _backgroundMapAlpha.asStateFlow()

    private val _tileInvalidateTick = MutableStateFlow(0L)
    val tileInvalidateTick: StateFlow<Long> = _tileInvalidateTick.asStateFlow()

    val tileMapManager: TileMapManager = TileMapManager(viewModelScope) {
        _tileInvalidateTick.value = System.currentTimeMillis()
    }

    private val _addressSearchResults = MutableStateFlow<List<TileMapManager.AddressSearchResult>>(emptyList())
    val addressSearchResults: StateFlow<List<TileMapManager.AddressSearchResult>> = _addressSearchResults.asStateFlow()

    private val _mapCenterOverrideNE = MutableStateFlow<Pair<Double, Double>?>(null)
    val mapCenterOverrideNE: StateFlow<Pair<Double, Double>?> = _mapCenterOverrideNE.asStateFlow()

    private val _exportConfig = MutableStateFlow(ExportColumnConfig())
    val exportConfig: StateFlow<ExportColumnConfig> = _exportConfig.asStateFlow()

    private val _stakeoutTarget = MutableStateFlow<SurveyPoint?>(null)
    val stakeoutTarget: StateFlow<SurveyPoint?> = _stakeoutTarget.asStateFlow()

    private val _activeStakeoutSegment = MutableStateFlow<Pair<Pair<Double, Double>, Pair<Double, Double>>?>(null)
    val activeStakeoutSegment: StateFlow<Pair<Pair<Double, Double>, Pair<Double, Double>>?> = _activeStakeoutSegment.asStateFlow()

    private val _stakeoutDirectionMode = MutableStateFlow(StakeoutDirectionMode.NORTH_FIXED)
    val stakeoutDirectionMode: StateFlow<StakeoutDirectionMode> = _stakeoutDirectionMode.asStateFlow()

    private val _stakeoutIncludeZ = MutableStateFlow(true)
    val stakeoutIncludeZ: StateFlow<Boolean> = _stakeoutIncludeZ.asStateFlow()

    private val _stakeoutSoundEnabled = MutableStateFlow(true)
    val stakeoutSoundEnabled: StateFlow<Boolean> = _stakeoutSoundEnabled.asStateFlow()

    private val _stakeoutGuidance = MutableStateFlow<StakeoutGuidance?>(null)
    val stakeoutGuidance: StateFlow<StakeoutGuidance?> = _stakeoutGuidance.asStateFlow()

    private val _designGradeConfig = MutableStateFlow(DesignGradeConfig())
    val designGradeConfig: StateFlow<DesignGradeConfig> = _designGradeConfig.asStateFlow()

    private val _designGradeGuidance = MutableStateFlow<DesignGradeGuidance?>(null)
    val designGradeGuidance: StateFlow<DesignGradeGuidance?> = _designGradeGuidance.asStateFlow()

    private val _undoHistory = MutableStateFlow<List<SurveyHistorySnapshot>>(emptyList())
    val undoHistory: StateFlow<List<SurveyHistorySnapshot>> = _undoHistory.asStateFlow()

    private val _redoHistory = MutableStateFlow<List<SurveyHistorySnapshot>>(emptyList())
    val redoHistory: StateFlow<List<SurveyHistorySnapshot>> = _redoHistory.asStateFlow()

    private val _blackboxStatusText = MutableStateFlow("블랙박스 자동보호 대기중")
    val blackboxStatusText: StateFlow<String> = _blackboxStatusText.asStateFlow()

    private val _blackboxBackups = MutableStateFlow<List<File>>(emptyList())
    val blackboxBackups: StateFlow<List<File>> = _blackboxBackups.asStateFlow()

    private val _activePhotoBoardPoint = MutableStateFlow<SurveyPoint?>(null)
    val activePhotoBoardPoint: StateFlow<SurveyPoint?> = _activePhotoBoardPoint.asStateFlow()

    private val _autoOpenPhotoBoardAfterMeasure = MutableStateFlow(false)
    val autoOpenPhotoBoardAfterMeasure: StateFlow<Boolean> = _autoOpenPhotoBoardAfterMeasure.asStateFlow()

    private val _cogoRulerSummary = MutableStateFlow(CogoRulerSummary())
    val cogoRulerSummary: StateFlow<CogoRulerSummary> = _cogoRulerSummary.asStateFlow()

    private val _quickCodes = MutableStateFlow(defaultQuickCodes())
    val quickCodes: StateFlow<List<QuickCodePreset>> = _quickCodes.asStateFlow()

    private val _lastMeasurePreset = MutableStateFlow(LastMeasurePreset())
    val lastMeasurePreset: StateFlow<LastMeasurePreset> = _lastMeasurePreset.asStateFlow()

    private val _isAveraging = MutableStateFlow(false)
    val isAveraging: StateFlow<Boolean> = _isAveraging.asStateFlow()

    private val _averagingProgress = MutableStateFlow(0)
    val averagingProgress: StateFlow<Int> = _averagingProgress.asStateFlow()

    private val _calibrationPairs = MutableStateFlow<List<CalibrationPair>>(emptyList())
    val calibrationPairs: StateFlow<List<CalibrationPair>> = _calibrationPairs.asStateFlow()

    private val _calibrationParams = MutableStateFlow(SiteCalibrationParams())
    val calibrationParams: StateFlow<SiteCalibrationParams> = _calibrationParams.asStateFlow()

    private val _ntripConfig = MutableStateFlow(NtripConfig())
    val ntripConfig: StateFlow<NtripConfig> = _ntripConfig.asStateFlow()

    private val _availableMountpoints = MutableStateFlow(listOf("VRS-RTCM32", "VRS-RTCM31", "FKP-RTCM32"))
    val availableMountpoints: StateFlow<List<String>> = _availableMountpoints.asStateFlow()

    private val _keepScreenOnEnabled = MutableStateFlow(true)
    val keepScreenOnEnabled: StateFlow<Boolean> = _keepScreenOnEnabled.asStateFlow()

    private val _touchLockEnabled = MutableStateFlow(false)
    val touchLockEnabled: StateFlow<Boolean> = _touchLockEnabled.asStateFlow()

    private val _autoReconnectEnabled = MutableStateFlow(true)
    val autoReconnectEnabled: StateFlow<Boolean> = _autoReconnectEnabled.asStateFlow()

    private val _lastBtDeviceAddress = MutableStateFlow("")
    val lastBtDeviceAddress: StateFlow<String> = _lastBtDeviceAddress.asStateFlow()

    private val _lastBtDeviceName = MutableStateFlow("")
    val lastBtDeviceName: StateFlow<String> = _lastBtDeviceName.asStateFlow()

    // 수평기포관(전자기포관) 기본 OFF: 필요할 때만 도구함에서 켜서 사용!
    private val _imuTiltCompensationEnabled = MutableStateFlow(false)
    val imuTiltCompensationEnabled: StateFlow<Boolean> = _imuTiltCompensationEnabled.asStateFlow()

    private val _imuBubbleVisibleOnMap = MutableStateFlow(false)
    val imuBubbleVisibleOnMap: StateFlow<Boolean> = _imuBubbleVisibleOnMap.asStateFlow()

    private val _imuMaxTiltLimitDeg = MutableStateFlow(15.0)
    val imuMaxTiltLimitDeg: StateFlow<Double> = _imuMaxTiltLimitDeg.asStateFlow()

    private val _imuBlockRecordOnExcessTilt = MutableStateFlow(false)
    val imuBlockRecordOnExcessTilt: StateFlow<Boolean> = _imuBlockRecordOnExcessTilt.asStateFlow()

    private val _imuTiltWarningAlertActive = MutableStateFlow(false)
    val imuTiltWarningAlertActive: StateFlow<Boolean> = _imuTiltWarningAlertActive.asStateFlow()

    private val _vworldApiKey = MutableStateFlow("38299C15-0A2C-4367-8DD6-BF3936E0F86D")
    val vworldApiKey: StateFlow<String> = _vworldApiKey.asStateFlow()

    private val _vworldSearchRadiusMeters = MutableStateFlow(300)
    val vworldSearchRadiusMeters: StateFlow<Int> = _vworldSearchRadiusMeters.asStateFlow()

    private val _isVWorldLoading = MutableStateFlow(false)
    val isVWorldLoading: StateFlow<Boolean> = _isVWorldLoading.asStateFlow()

    private val _vworldStatusMessage = MutableStateFlow("반경 내 연속지적도(폴리선·지번)를 실시간으로 불러옵니다.")
    val vworldStatusMessage: StateFlow<String> = _vworldStatusMessage.asStateFlow()

    private var simJob: Job? = null
    private var autoReconnectWatchdogJob: Job? = null
    private var userExplicitlyDisconnectedBt = false
    private var userExplicitlyStoppedNtrip = true

    val btManager = GnssBluetoothManager(
        context = application,
        scope = viewModelScope,
        onNmeaLineReceived = { line ->
            val parsed = nmeaParser.parseLine(line)
            if (parsed != null) {
                viewModelScope.launch(Dispatchers.Main) {
                    val cur = _gnssStatus.value
                    updateGnssAndCoordinates(
                        parsed.copy(
                            sourceMode = _sourceMode.value,
                            btConnected = cur.btConnected,
                            connectedDeviceName = cur.connectedDeviceName,
                            ntripConnected = cur.ntripConnected,
                            ntripStatusMessage = cur.ntripStatusMessage,
                            rtcmBytesPerSec = cur.rtcmBytesPerSec,
                            imuTiltActive = _imuTiltCompensationEnabled.value
                        )
                    )
                }
            }
        },
        onConnectionStateChanged = { connected, name ->
            viewModelScope.launch(Dispatchers.Main) {
                _gnssStatus.value = _gnssStatus.value.copy(
                    btConnected = connected,
                    connectedDeviceName = name,
                    fixQuality = if (connected) _gnssStatus.value.fixQuality else FixQuality.NO_FIX
                )
                if (connected) {
                    postToast("블루투스 수신기 연결됨: $name")
                    soundSensorManager.speak("수신기가 연결되었습니다")
                }
            }
        }
    )

    private val ntripClient = NtripClient(
        scope = viewModelScope,
        onRtcmReceived = { bytes, bps ->
            btManager.writeRtcmBytes(bytes)
            _gnssStatus.value = _gnssStatus.value.copy(rtcmBytesPerSec = bps)
        },
        onStatusChanged = { connected, msg ->
            viewModelScope.launch(Dispatchers.Main) {
                _gnssStatus.value = _gnssStatus.value.copy(
                    ntripConnected = connected,
                    ntripStatusMessage = msg,
                    rtcmBytesPerSec = if (connected) _gnssStatus.value.rtcmBytesPerSec else 0
                )
            }
        }
    )

    private val phoneGpsManager = object : LocationListener {
        private val lm = application.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        @SuppressLint("MissingPermission")
        fun start() {
            try {
                lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (_: Exception) {}
            try {
                lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, this, Looper.getMainLooper())
            } catch (_: Exception) {}
            seedFromLastKnownLocation()
        }
        fun seedFromLastKnownLocation() {
            try {
                val gpsLoc = try { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
                val netLoc = try { lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
                val passLoc = try { lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (_: Exception) { null }
                val best = listOfNotNull(gpsLoc, netLoc, passLoc).maxByOrNull { it.time }
                if (best != null) {
                    onLocationChanged(best)
                }
            } catch (_: Exception) {}
        }
        fun stop() {
            try {
                lm?.removeUpdates(this)
            } catch (_: Exception) {}
        }
        override fun onLocationChanged(loc: Location) {
            // 블루투스 수신기가 미연결 상태이거나 내장 GPS 모드일 때 스마트폰 위치를 즉시 반영
            if (_sourceMode.value == PositioningSourceMode.SIMULATION) return
            if (_sourceMode.value == PositioningSourceMode.BLUETOOTH_GNSS && _gnssStatus.value.btConnected) return
            val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 3.5
            updateGnssAndCoordinates(
                _gnssStatus.value.copy(
                    sourceMode = PositioningSourceMode.PHONE_INTERNAL_GPS,
                    btConnected = true,
                    connectedDeviceName = "스마트폰 내장 GPS",
                    fixQuality = FixQuality.SINGLE,
                    satellitesUsed = 12,
                    satellitesTracked = 18,
                    hdop = 1.1,
                    hrms = acc,
                    vrms = acc * 1.5,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    ellipsoidalHeight = loc.altitude
                )
            )
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    init {
        loadSavedSettingsAndLastProject()
        refreshSavedProjectsList()
        refreshSavedTrfFilesList()
        phoneGpsManager.start()
    }

    fun postToast(msg: String) {
        _toastEvents.tryEmit(msg)
    }

    fun setTouchLockEnabled(locked: Boolean) {
        _touchLockEnabled.value = locked
        postToast(if (locked) "화면 터치잠금 켜짐 (볼륨버튼 측정은 작동합니다)" else "화면 터치잠금 해제됨")
    }

    fun setKeepScreenOnEnabled(enabled: Boolean) {
        _keepScreenOnEnabled.value = enabled
        saveCurrentStateToPrefsOnly()
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        _autoReconnectEnabled.value = enabled
        saveCurrentStateToPrefsOnly()
        if (enabled) startAutoReconnectWatchdogIfNeeded()
    }

    fun updateMapDisplayConfig(config: MapDisplayConfig) {
        _mapDisplayConfig.value = config
        saveCurrentStateToPrefsOnly()
    }

    fun updateExportConfig(config: ExportColumnConfig) {
        _exportConfig.value = config
    }

    fun setPositioningSourceMode(mode: PositioningSourceMode) {
        _sourceMode.value = mode
        simJob?.cancel()
        phoneGpsManager.stop()
        when (mode) {
            PositioningSourceMode.BLUETOOTH_GNSS -> {
                _gnssStatus.value = _gnssStatus.value.copy(
                    sourceMode = mode,
                    btConnected = false,
                    fixQuality = FixQuality.NO_FIX,
                    connectedDeviceName = "미연결"
                )
            }
            PositioningSourceMode.PHONE_INTERNAL_GPS -> {
                btManager.disconnect()
                phoneGpsManager.start()
                postToast("스마트폰 내장 GPS 모드로 전환되었습니다.")
            }
            PositioningSourceMode.SIMULATION -> {
                btManager.disconnect()
                startSimulationLoop()
                postToast("현장 시뮬레이션(데모) 모드가 시작되었습니다.")
            }
        }
    }

    private fun startSimulationLoop() {
        simJob?.cancel()
        simJob = viewModelScope.launch {
            var step = 0
            while (isActive && _sourceMode.value == PositioningSourceMode.SIMULATION) {
                val baseLat = 36.642435 + sin(step * 0.08) * 0.00012
                val baseLon = 127.489031 + cos(step * 0.08) * 0.00012
                updateGnssAndCoordinates(
                    _gnssStatus.value.copy(
                        sourceMode = PositioningSourceMode.SIMULATION,
                        btConnected = true,
                        connectedDeviceName = "V90 시뮬레이터",
                        fixQuality = FixQuality.RTK_FIXED,
                        satellitesUsed = 18,
                        satellitesTracked = 26,
                        hdop = 0.7,
                        pdop = 1.2,
                        hrms = 0.008,
                        vrms = 0.014,
                        latitude = baseLat,
                        longitude = baseLon,
                        ellipsoidalHeight = 78.500,
                        nmeaGeoidSeparation = 25.350,
                        ntripConnected = true,
                        ntripStatusMessage = "시뮬레이션 RTK FIXED"
                    )
                )
                step++
                delay(1000L)
            }
        }
    }

    fun updateGnssAndCoordinates(status: GnssStatus) {
        _gnssStatus.value = status
        ntripClient.updateRoverPosition(status.latitude, status.longitude, status.ellipsoidalHeight)

        // 수평기포관이 켜져 있거나 기울기 보정이 켜져 있을 때만 기울기 경고 판단
        val effectiveTilt = getEffectiveTiltAngleDeg()
        _imuTiltWarningAlertActive.value = (_imuBubbleVisibleOnMap.value || _imuTiltCompensationEnabled.value) &&
            (effectiveTilt > _imuMaxTiltLimitDeg.value)

        if (status.latitude == 0.0 && status.longitude == 0.0) return

        val rawPlane = CoordinateConverter.wgs84ToPlane(
            latDeg = status.latitude,
            lonDeg = status.longitude,
            ellipsoidalHeight = status.ellipsoidalHeight,
            system = _coordinateSystem.value,
            useKnGeoid18 = _useKnGeoid18.value,
            nmeaGeoidFallback = status.nmeaGeoidSeparation
        )

        val groundZ = rawPlane.orthometricHeight - _antennaHeight.value
        val planeWithGroundZ = rawPlane.copy(orthometricHeight = groundZ)

        val calibratedPlane = CalibrationSolver.applyCalibration(
            raw = planeWithGroundZ,
            params = _calibrationParams.value,
            pairs = _calibrationPairs.value
        )

        val finalPlane = if (_imuTiltCompensationEnabled.value) {
            val (dN, dE, dH) = computeCurrentTiltCorrectionNEH()
            calibratedPlane.copy(
                northing = calibratedPlane.northing + dN,
                easting = calibratedPlane.easting + dE,
                orthometricHeight = calibratedPlane.orthometricHeight + dH
            )
        } else {
            calibratedPlane
        }

        val nowMs = System.currentTimeMillis()
        var isSingleEpochSpike = false
        if (status.fixQuality != FixQuality.NO_FIX) {
            synchronized(recentCleanSamples) {
                val prevSample = recentCleanSamples.lastOrNull()
                val dtSec = if (prevSample != null) ((nowMs - prevSample.timeMs).coerceAtLeast(50L) / 1000.0) else 1.0
                val jumpDist = if (prevSample != null) hypot(finalPlane.northing - prevSample.plane.northing, finalPlane.easting - prevSample.plane.easting) else 0.0
                val hadRecentRtkFixed = recentCleanSamples.any { (nowMs - it.timeMs) <= 4500L && it.gnss.fixQuality == FixQuality.RTK_FIXED }
                val isSuddenDegradedDrop = hadRecentRtkFixed && status.fixQuality != FixQuality.RTK_FIXED && jumpDist > 0.85
                val isMultipathJump = prevSample != null && dtSec < 1.2 && jumpDist > 2.5 && recentCleanSamples.size >= 2
                if (isSuddenDegradedDrop || isMultipathJump) {
                    isSingleEpochSpike = true
                } else {
                    recentCleanSamples.addLast(TimedCoordSample(nowMs, finalPlane, status))
                    while (recentCleanSamples.size > 12) recentCleanSamples.removeFirst()
                }
            }
        }
        if (!isSingleEpochSpike || _currentPlaneCoord.value.northing == 0.0) {
            _currentPlaneCoord.value = finalPlane
        }

        refreshStakeoutGuidance(finalPlane, status)
        refreshDesignGradeGuidance(finalPlane)
    }

    private fun refreshStakeoutGuidance(curPlane: PlaneCoordinate, status: GnssStatus) {
        val target = _stakeoutTarget.value ?: run {
            _stakeoutGuidance.value = null
            return
        }
        val dN = target.northing - curPlane.northing
        val dE = target.easting - curPlane.easting
        val dZ = target.elevation - curPlane.orthometricHeight
        val hDist = hypot(dN, dE)
        val sDist = hypot(hDist, dZ)
        val azRad = atan2(dE, dN)
        val azDeg = (Math.toDegrees(azRad) + 360.0) % 360.0

        val refDeg = when (_stakeoutDirectionMode.value) {
            StakeoutDirectionMode.NORTH_FIXED -> 0.0
            StakeoutDirectionMode.MOVING_HEADING -> soundSensorManager.deviceAzimuthDeg.value.toDouble()
            StakeoutDirectionMode.SUN_DIRECTION -> 180.0
            StakeoutDirectionMode.SUN_OPPOSITE -> 0.0
        }
        val relRad = Math.toRadians(azDeg - refDeg)
        val forward = hDist * cos(relRad)
        val right = hDist * sin(relRad)

        var segOffset: Double? = null
        var segStation: Double? = null
        _activeStakeoutSegment.value?.let { (segA, segB) ->
            val vx = segB.second - segA.second
            val vy = segB.first - segA.first
            val len = hypot(vy, vx)
            if (len > 1e-4) {
                val px = curPlane.easting - segA.second
                val py = curPlane.northing - segA.first
                segStation = (px * vx + py * vy) / len
                segOffset = (px * vy - py * vx) / len
            }
        }

        val guidance = StakeoutGuidance(
            targetPoint = target,
            targetName = target.name,
            deltaN = dN,
            deltaE = dE,
            deltaZ = dZ,
            horizontalDistance = hDist,
            slopeDistance = sDist,
            azimuthDeg = azDeg,
            forwardMeters = forward,
            rightMeters = right,
            isWithinTolerance = hDist <= 0.03,
            linePerpendicularOffsetMeters = segOffset,
            lineStationMeters = segStation
        )
        _stakeoutGuidance.value = guidance
        // 15m 이내 간헐적 종소리("딩~") 및 1m 이내 빠른 2연타 종소리("딩동!") 실시간 갱신
        soundSensorManager.updateStakeoutDistance(hDist, _stakeoutSoundEnabled.value)
    }

    private fun refreshDesignGradeGuidance(curPlane: PlaneCoordinate) {
        val cfg = _designGradeConfig.value
        if (cfg.mode == DesignGradeMode.OFF) {
            _designGradeGuidance.value = null
            return
        }
        val curZ = curPlane.orthometricHeight
        when (cfg.mode) {
            DesignGradeMode.FLAT_LEVEL -> {
                val designZ = cfg.targetElevationZ + cfg.verticalOffsetMeters
                val delta = designZ - curZ
                _designGradeGuidance.value = DesignGradeGuidance(
                    mode = cfg.mode,
                    currentGroundZ = curZ,
                    computedDesignZ = designZ,
                    verticalOffsetMeters = cfg.verticalOffsetMeters,
                    cutFillDeltaZ = delta,
                    isOnGrade = abs(delta) <= cfg.toleranceMeters
                )
            }
            DesignGradeMode.SLOPE_LINE -> {
                val dN = cfg.endN - cfg.startN
                val dE = cfg.endE - cfg.startE
                val totalLen = hypot(dN, dE).coerceAtLeast(0.01)
                val pN = curPlane.northing - cfg.startN
                val pE = curPlane.easting - cfg.startE
                val sta = (pN * dN + pE * dE) / totalLen
                val lineOff = (pE * dN - pN * dE) / totalLen
                val slopePct = if (cfg.useManualSlopePercent) {
                    cfg.manualSlopePercent
                } else {
                    ((cfg.endZ - cfg.startZ) / totalLen) * 100.0
                }
                val designZ = cfg.startZ + (sta * (slopePct / 100.0)) + cfg.verticalOffsetMeters
                val delta = designZ - curZ
                _designGradeGuidance.value = DesignGradeGuidance(
                    mode = cfg.mode,
                    currentGroundZ = curZ,
                    computedDesignZ = designZ,
                    verticalOffsetMeters = cfg.verticalOffsetMeters,
                    cutFillDeltaZ = delta,
                    isOnGrade = abs(delta) <= cfg.toleranceMeters,
                    stationMeters = sta,
                    totalLineLengthMeters = totalLen,
                    lineOffsetMeters = lineOff,
                    effectiveSlopePercent = slopePct
                )
            }
            DesignGradeMode.OFF -> _designGradeGuidance.value = null
        }
    }

    fun validateReadyToMeasure(): String? {
        if (_gnssStatus.value.latitude == 0.0 && _gnssStatus.value.longitude == 0.0) {
            phoneGpsManager.seedFromLastKnownLocation()
        }
        val g = _gnssStatus.value
        if (g.fixQuality == FixQuality.NO_FIX || (g.latitude == 0.0 && g.longitude == 0.0)) {
            return if (_sourceMode.value == PositioningSourceMode.BLUETOOTH_GNSS && !g.btConnected) {
                "블루투스 GPS 미연결 및 휴대폰 위치 신호 대기 중입니다. (실내에서는 시뮬레이션 모드를 켜거나 위치 권한을 확인하세요)"
            } else {
                "위성 위치 신호(GPS)를 수신 대기 중입니다."
            }
        }
        when (_requiredFixMode.value) {
            RequiredFixMode.RTK_FIXED_ONLY -> {
                if (g.fixQuality != FixQuality.RTK_FIXED) {
                    return "현재 [FIX만 허용] 모드입니다 (현재: ${g.fixQuality.shortBadge}). 하단에서 [FLOAT] 또는 [DGPS]로 변경하면 측정이 가능합니다."
                }
            }
            RequiredFixMode.RTK_FLOAT_OR_BETTER -> {
                if (g.fixQuality != FixQuality.RTK_FIXED && g.fixQuality != FixQuality.RTK_FLOAT) {
                    return "현재 [FLOAT 이상] 모드입니다 (현재: ${g.fixQuality.shortBadge}). 휴대폰/단독 측량은 하단에서 [DGPS] 모드를 선택하세요."
                }
            }
            RequiredFixMode.ALL_VALID_FIX -> {
                // FIX, FLOAT, DGPS, SINGLE(스마트폰 내장 GPS) 모두 측정 허용
            }
        }
        // 평소 쓰는 안테나고와 다르게 설정되어 있고 아직 확인하지 않았다면 경고 팝업 1회 표시
        if (abs(_antennaHeight.value - _favoriteAntennaHeight.value) > 0.0005 && !confirmedNonFavoriteAntennaToday) {
            _nextDayAntennaWarningActive.value = true
            soundSensorManager.speak("주의하세요. 현재 안테나 폴대 높이가 평소 사용하는 안테나고와 다릅니다.")
            return "⚠️ 평소 쓰는 안테나고(${String.format(Locale.US, "%.3fm", _favoriteAntennaHeight.value)})와 현재 높이(${String.format(Locale.US, "%.3fm", _antennaHeight.value)})가 다릅니다. 확인 창에서 선택해 주세요."
        }
        if ((_imuBubbleVisibleOnMap.value || _imuTiltCompensationEnabled.value) &&
            _imuBlockRecordOnExcessTilt.value &&
            getEffectiveTiltAngleDeg() > _imuMaxTiltLimitDeg.value
        ) {
            return "폴대 기울기가 허용 한도를 초과하여 측량이 일시 차단되었습니다."
        }
        return null
    }

    fun generateNextSeqNumber(): Int = (_points.value.maxOfOrNull { it.seqNumber } ?: 0) + 1
    fun generateNextPointName(): String = "${generateNextSeqNumber()}"

    private fun promoteCodeToFront(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) return
        val current = _quickCodes.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.code == clean }
        if (existingIdx > 0) {
            val item = current.removeAt(existingIdx)
            current.add(0, item)
            _quickCodes.value = current
        } else if (existingIdx < 0) {
            current.add(0, QuickCodePreset(clean, "사용자 코드", 0xFF38BDF8, true))
            _quickCodes.value = current
        }
    }

    fun updateMeasurePreset(
        code: String = _lastMeasurePreset.value.code,
        epochs: Int = _lastMeasurePreset.value.epochs,
        memo: String = _lastMeasurePreset.value.memo,
        autoLineMode: AutoLineMode = _lastMeasurePreset.value.autoLineMode
    ) {
        val cleanCode = code.trim().ifBlank { "지반" }
        promoteCodeToFront(cleanCode)
        _lastMeasurePreset.value = LastMeasurePreset(
            code = cleanCode,
            epochs = epochs.coerceIn(1, 30),
            memo = memo,
            autoLineMode = autoLineMode
        )
        saveCurrentStateToPrefsOnly()
    }

    fun instantRepeatMeasure() {
        val preset = _lastMeasurePreset.value
        measureSurveyPoint(
            name = generateNextPointName(),
            code = preset.code,
            epochs = preset.epochs,
            memo = preset.memo,
            autoLineMode = preset.autoLineMode
        )
    }

    fun measureSurveyPoint(
        name: String,
        code: String,
        epochs: Int,
        memo: String,
        autoLineMode: AutoLineMode = _lastMeasurePreset.value.autoLineMode
    ) {
        if (_isAveraging.value) return
        val blockReason = validateReadyToMeasure()
        if (blockReason != null) {
            postToast("⚠️ $blockReason")
            soundSensorManager.speak(blockReason)
            return
        }
        if (triggerNextDayAntennaWarningIfNeeded()) return

        val cleanCode = code.trim().ifBlank { "지반" }
        promoteCodeToFront(cleanCode)
        _lastMeasurePreset.value = LastMeasurePreset(
            code = cleanCode,
            epochs = epochs.coerceIn(1, 30),
            memo = memo,
            autoLineMode = autoLineMode
        )
        saveCurrentStateToPrefsOnly()

        viewModelScope.launch {
            _isAveraging.value = true
            _averagingProgress.value = 0

            val totalEpochs = epochs.coerceIn(1, 30)
            val collectedSamples = mutableListOf<TimedCoordSample>()
            val nowStartMs = System.currentTimeMillis()
            synchronized(recentCleanSamples) {
                recentCleanSamples.filter { nowStartMs - it.timeMs <= 1500L }.forEach {
                    collectedSamples.add(it)
                }
            }

            val sampleLoops = if (totalEpochs == 1) 3 else totalEpochs
            for (i in 1..sampleLoops) {
                val p = _currentPlaneCoord.value
                val g = _gnssStatus.value
                val sample = TimedCoordSample(System.currentTimeMillis(), p, g)
                collectedSamples.add(sample)
                synchronized(recentCleanSamples) {
                    recentCleanSamples.addLast(sample)
                    while (recentCleanSamples.size > 8) recentCleanSamples.removeFirst()
                }
                _averagingProgress.value = i.coerceAtMost(totalEpochs)
                if (totalEpochs == 1) {
                    if (i < sampleLoops) delay(75L)
                } else {
                    if (i < sampleLoops) delay(220L)
                }
            }

            // 1) 샘플 중 RTK_FIXED가 존재하면 순간적으로 풀린 FLOAT/SINGLE(6~10m 점프) 샘플은 자동 제외!
            val fixedOnly = collectedSamples.filter { it.gnss.fixQuality == FixQuality.RTK_FIXED }
            val qualityFiltered = if (fixedOnly.isNotEmpty()) fixedOnly else collectedSamples

            // 2) 중앙값(Median) 계산 후 중앙값에서 0.35m 이상 튄 비정상 스파이크 샘플 자동 제거!
            val sortedN = qualityFiltered.map { it.plane.northing }.sorted()
            val sortedE = qualityFiltered.map { it.plane.easting }.sorted()
            val medN = sortedN[sortedN.size / 2]
            val medE = sortedE[sortedE.size / 2]
            val inliers = qualityFiltered.filter {
                hypot(it.plane.northing - medN, it.plane.easting - medE) <= 0.25
            }.ifEmpty { listOf(qualityFiltered[qualityFiltered.size / 2]) }

            val finalCount = inliers.size
            val avgN = inliers.sumOf { it.plane.northing } / finalCount
            val avgE = inliers.sumOf { it.plane.easting } / finalCount
            val avgZ = inliers.sumOf { it.plane.orthometricHeight } / finalCount
            val avgLat = inliers.sumOf { it.gnss.latitude } / finalCount
            val avgLon = inliers.sumOf { it.gnss.longitude } / finalCount
            val avgEllip = inliers.sumOf { it.gnss.ellipsoidalHeight } / finalCount

            val bestSample = inliers.last()
            val g = bestSample.gnss
            val p = bestSample.plane
            val seq = generateNextSeqNumber()
            val cleanName = name.ifBlank { "$seq" }

            val newPoint = SurveyPoint(
                seqNumber = seq,
                name = cleanName,
                code = cleanCode,
                northing = avgN,
                easting = avgE,
                elevation = avgZ,
                latitude = avgLat,
                longitude = avgLon,
                ellipsoidalHeight = avgEllip,
                geoidSeparation = p.geoidSeparation,
                antennaHeight = _antennaHeight.value,
                fixQuality = g.fixQuality,
                hrms = g.hrms,
                vrms = g.vrms,
                epochCount = totalEpochs,
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()),
                memo = memo
            )

            pushUndoSnapshot("#${seq} ${cleanName}(${cleanCode}) 측정")
            val previousPoints = _points.value
            val updated = previousPoints + newPoint
            _points.value = updated

            applyLineworkConnection(
                newPoint = newPoint,
                previousPoints = previousPoints,
                mode = autoLineMode
            )

            _isAveraging.value = false
            soundSensorManager.playPointSavedBeep()
            soundSensorManager.speak("${seq}번 ${cleanCode} 저장")
            postToast("#${seq} ${cleanName} [${cleanCode}] (${autoLineMode.shortLabel}) 저장 완료")
            saveCurrentStateToPrefsAndAutoFile()
            if (_autoOpenPhotoBoardAfterMeasure.value) {
                _activePhotoBoardPoint.value = newPoint
            }
        }
    }

    private fun applyLineworkConnection(
        newPoint: SurveyPoint,
        previousPoints: List<SurveyPoint>,
        mode: AutoLineMode
    ) {
        if (mode == AutoLineMode.NONE) return
        val presetColor = _quickCodes.value.find { it.code == newPoint.code }?.colorHex ?: 0xFF38BDF8
        val list = _polylines.value.toMutableList()

        when (mode) {
            AutoLineMode.SAME_CODE -> {
                val idx = list.indexOfLast { it.code == newPoint.code && !it.isClosed }
                if (idx >= 0) {
                    val existing = list[idx]
                    list[idx] = existing.copy(pointIds = existing.pointIds + newPoint.id)
                } else {
                    val prevSameCodePt = previousPoints.lastOrNull { it.code == newPoint.code }
                    val initialIds = if (prevSameCodePt != null) listOf(prevSameCodePt.id, newPoint.id) else listOf(newPoint.id)
                    list.add(CadPolyline(code = newPoint.code, pointIds = initialIds, isClosed = false, colorHex = presetColor))
                }
            }
            AutoLineMode.PREV_POINT -> {
                val prevPt = previousPoints.lastOrNull()
                if (prevPt != null) {
                    val idx = list.indexOfLast { !it.isClosed && it.pointIds.lastOrNull() == prevPt.id }
                    if (idx >= 0) {
                        val existing = list[idx]
                        list[idx] = existing.copy(pointIds = existing.pointIds + newPoint.id)
                    } else {
                        list.add(CadPolyline(code = newPoint.code, pointIds = listOf(prevPt.id, newPoint.id), isClosed = false, colorHex = presetColor))
                    }
                } else {
                    list.add(CadPolyline(code = newPoint.code, pointIds = listOf(newPoint.id), isClosed = false, colorHex = presetColor))
                }
            }
            AutoLineMode.PREV_PREV_POINT -> {
                val targetPt = if (previousPoints.size >= 2) previousPoints[previousPoints.size - 2] else previousPoints.lastOrNull()
                if (targetPt != null) {
                    val idx = list.indexOfLast { !it.isClosed && it.pointIds.lastOrNull() == targetPt.id }
                    if (idx >= 0) {
                        val existing = list[idx]
                        list[idx] = existing.copy(pointIds = existing.pointIds + newPoint.id)
                    } else {
                        list.add(CadPolyline(code = newPoint.code, pointIds = listOf(targetPt.id, newPoint.id), isClosed = false, colorHex = presetColor))
                    }
                } else {
                    list.add(CadPolyline(code = newPoint.code, pointIds = listOf(newPoint.id), isClosed = false, colorHex = presetColor))
                }
            }
            AutoLineMode.CLOSED_POLYGON -> {
                val idx = list.indexOfLast { it.code == newPoint.code && !it.isClosed }
                    .takeIf { it >= 0 }
                    ?: list.indexOfLast { !it.isClosed }
                if (idx >= 0) {
                    val existing = list[idx]
                    val updatedIds = existing.pointIds + newPoint.id
                    list[idx] = existing.copy(pointIds = updatedIds, isClosed = updatedIds.size >= 3)
                } else {
                    val prevSame = previousPoints.lastOrNull { it.code == newPoint.code } ?: previousPoints.lastOrNull()
                    val initialIds = if (prevSame != null) listOf(prevSame.id, newPoint.id) else listOf(newPoint.id)
                    list.add(CadPolyline(code = newPoint.code, pointIds = initialIds, isClosed = false, colorHex = presetColor))
                }
            }
            AutoLineMode.NONE -> {}
        }
        _polylines.value = list
    }

    fun setCanvasToolMode(mode: CanvasToolMode) {
        _canvasToolMode.value = mode
        _manualConnectLastPointId.value = null
        if (mode != CanvasToolMode.DRAWING_SNAP_SHIFT) {
            _drawingShiftSourceSnap.value = null
        }
        if (mode != CanvasToolMode.NORMAL) {
            _pendingSnappedTarget.value = null
            postToast(mode.koreanLabel)
        }
    }

    fun setShiftIncludeBackgroundTiles(enabled: Boolean) {
        _shiftIncludeBackgroundTiles.value = enabled
        saveCurrentStateToPrefsOnly()
    }

    fun resetBackgroundShiftNE() {
        _backgroundShiftNE.value = Pair(0.0, 0.0)
        saveCurrentStateToPrefsOnly()
        postToast("배경지도/지적도 타일 위치 오프셋을 원위치(0, 0)로 초기화했습니다.")
    }

    fun clearDrawingShiftSourceSnap() {
        _drawingShiftSourceSnap.value = null
        postToast("1단계: 이동할 도면(지적도/CAD)의 모서리를 다시 터치하거나 길게 눌러 스냅하세요.")
    }

    fun startInteractiveDrawingShiftFromSnap(snap: CadSnapTarget? = _pendingSnappedTarget.value) {
        if (_cadEntities.value.isEmpty() && _backgroundMapType.value == BackgroundMapType.NONE && !_mapDisplayConfig.value.cadastralOverlayEnabled) {
            postToast("먼저 불러온 도면(DXF/DWG/SHP 또는 V-World 지적도)을 열어주세요.")
            return
        }
        _pendingSnappedTarget.value = null
        _drawingShiftSourceSnap.value = snap
        _canvasToolMode.value = CanvasToolMode.DRAWING_SNAP_SHIFT
        soundSensorManager.playPointSavedBeep()
        if (snap != null) {
            soundSensorManager.speak("도면 모서리가 잡혔습니다. 맞출 측량 포인트를 터치하세요.")
            postToast("🎯 1단계 완료(${snap.label}): 이제 맞출 [측량한 포인트(현황점)]를 화면에서 터치하세요!")
        } else {
            soundSensorManager.speak("이동할 도면의 모서리를 터치하거나 길게 눌러 스냅하세요.")
            postToast("🎯 1단계: 불러온 도면(지적도/CAD)의 모서리를 터치하거나 꾹 눌러 잡으세요.")
        }
    }

    fun alignDrawingSourceSnapToSurveyPoint(targetPoint: SurveyPoint) {
        val src = _drawingShiftSourceSnap.value ?: _pendingSnappedTarget.value
        if (src == null) {
            postToast("먼저 이동 기준이 될 도면 모서리를 스냅하여 선택해주세요.")
            return
        }
        val dn = targetPoint.northing - src.northing
        val de = targetPoint.easting - src.easting
        shiftExternalCadEntities(
            deltaN = dn,
            deltaE = de,
            deltaZ = 0.0,
            alsoShiftBackground = _shiftIncludeBackgroundTiles.value,
            targetLabel = "#${targetPoint.seqNumber}(${targetPoint.name})"
        )
        _drawingShiftSourceSnap.value = null
        _canvasToolMode.value = CanvasToolMode.NORMAL
    }

    fun alignDrawingSourceSnapToCurrentGps() {
        val src = _drawingShiftSourceSnap.value ?: _pendingSnappedTarget.value
        if (src == null) {
            postToast("먼저 이동 기준이 될 도면 모서리를 스냅하여 선택해주세요.")
            return
        }
        val cur = _currentPlaneCoord.value
        val dn = cur.northing - src.northing
        val de = cur.easting - src.easting
        shiftExternalCadEntities(
            deltaN = dn,
            deltaE = de,
            deltaZ = 0.0,
            alsoShiftBackground = _shiftIncludeBackgroundTiles.value,
            targetLabel = "현위치(GPS)"
        )
        _drawingShiftSourceSnap.value = null
        _canvasToolMode.value = CanvasToolMode.NORMAL
    }

    fun updateCurrentViewportCenterNE(northing: Double, easting: Double) {
        if (northing != 0.0 || easting != 0.0) {
            _currentViewportCenterNE.value = Pair(northing, easting)
        }
    }

    fun handleCanvasTapInToolMode(tapN: Double, tapE: Double, toleranceMeters: Double): Boolean {
        val mode = _canvasToolMode.value
        if (mode == CanvasToolMode.NORMAL) return false

        if (mode == CanvasToolMode.MANUAL_LINE_CONNECT) {
            val nearestPt = _points.value.minByOrNull { hypot(it.northing - tapN, it.easting - tapE) }
            if (nearestPt == null || hypot(nearestPt.northing - tapN, nearestPt.easting - tapE) > toleranceMeters * 1.5) {
                postToast("연결할 측점을 정확히 터치하세요.")
                return true
            }
            val prevId = _manualConnectLastPointId.value
            if (prevId == null) {
                _manualConnectLastPointId.value = nearestPt.id
                postToast("시작점 선택됨: #${nearestPt.seqNumber} (${nearestPt.name}) -> 다음 연결할 측점을 터치하세요")
            } else if (prevId != nearestPt.id) {
                pushUndoSnapshot("#${nearestPt.seqNumber} 수동 선연결")
                val list = _polylines.value.toMutableList()
                val idx = list.indexOfLast { !it.isClosed && it.pointIds.lastOrNull() == prevId }
                if (idx >= 0) {
                    val existing = list[idx]
                    list[idx] = existing.copy(pointIds = existing.pointIds + nearestPt.id)
                } else {
                    val presetColor = _quickCodes.value.find { it.code == nearestPt.code }?.colorHex ?: 0xFF38BDF8
                    list.add(CadPolyline(code = nearestPt.code.ifBlank { "현황선" }, pointIds = listOf(prevId, nearestPt.id), isClosed = false, colorHex = presetColor))
                }
                _polylines.value = list
                _manualConnectLastPointId.value = nearestPt.id
                saveCurrentStateToPrefsAndAutoFile()
                postToast("#${nearestPt.seqNumber} (${nearestPt.name}) 선연결 완료 (계속 이으려면 다음 점 터치)")
            }
            return true
        }

        if (mode == CanvasToolMode.MANUAL_LINE_DELETE) {
            val pointMap = _points.value.associateBy { it.id }
            val list = _polylines.value.toMutableList()
            var bestPolyIdx = -1
            var bestDist = toleranceMeters * 1.5
            list.forEachIndexed { idx, poly ->
                val pts = poly.pointIds.mapNotNull { pointMap[it] }
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val d = distancePointToSegment(tapN, tapE, a.northing, a.easting, b.northing, b.easting)
                    if (d < bestDist) {
                        bestDist = d
                        bestPolyIdx = idx
                    }
                }
            }
            if (bestPolyIdx >= 0) {
                pushUndoSnapshot("선분(${list[bestPolyIdx].code}) 삭제")
                val removed = list.removeAt(bestPolyIdx)
                _polylines.value = list
                saveCurrentStateToPrefsAndAutoFile()
                postToast("현황선(${removed.code})이 삭제되었습니다.")
            } else {
                postToast("삭제할 현황선을 정확히 터치하세요.")
            }
            return true
        }

        if (mode == CanvasToolMode.COGO_RULER_AREA) {
            val nearestPt = _points.value.minByOrNull { hypot(it.northing - tapN, it.easting - tapE) }
            val chosenPt = if (nearestPt != null && hypot(nearestPt.northing - tapN, nearestPt.easting - tapE) <= toleranceMeters * 1.8) {
                nearestPt
            } else {
                val (lat, lon) = CoordinateConverter.planeToWgs84(tapN, tapE, _coordinateSystem.value)
                SurveyPoint(
                    seqNumber = _cogoRulerSummary.value.selectedPoints.size + 1,
                    name = "도면점${_cogoRulerSummary.value.selectedPoints.size + 1}",
                    code = "측정자",
                    northing = tapN,
                    easting = tapE,
                    elevation = _currentPlaneCoord.value.orthometricHeight,
                    latitude = lat,
                    longitude = lon
                )
            }
            addPointToCogoRuler(chosenPt)
            return true
        }

        if (mode == CanvasToolMode.DRAWING_SNAP_SHIFT) {
            val src = _drawingShiftSourceSnap.value
            if (src == null) {
                val cadSnap = CoordinateConverter.findNearestCadSnap(
                    tapN = tapN,
                    tapE = tapE,
                    entities = _cadEntities.value,
                    maxSnapRadiusMeters = (toleranceMeters * 2.4).coerceAtLeast(1.0),
                    surveyPoints = emptyList(),
                    hiddenLayers = _hiddenLayers.value
                )
                if (cadSnap != null) {
                    _drawingShiftSourceSnap.value = cadSnap
                    soundSensorManager.playPointSavedBeep()
                    soundSensorManager.speak("도면 모서리가 잡혔습니다. 이동할 측량 포인트를 터치하세요.")
                    postToast("🎯 1단계 도면 모서리 잡음 [${cadSnap.snapType.koreanLabel}: ${cadSnap.label}] → 맞출 [측량 포인트]를 터치하세요!")
                } else {
                    postToast("잡을 도면(지적도/CAD)의 모서리나 선 근처를 터치하세요.")
                }
                return true
            } else {
                val surveyedOnly = _points.value.filterNot {
                    it.memo.startsWith("V-World 지적기준점") || it.timestamp == "DXF 도면"
                }.ifEmpty { _points.value }
                val nearestSurveyPt = surveyedOnly.minByOrNull { hypot(it.northing - tapN, it.easting - tapE) }
                val distToSurveyPt = nearestSurveyPt?.let { hypot(it.northing - tapN, it.easting - tapE) } ?: Double.MAX_VALUE
                if (nearestSurveyPt != null && distToSurveyPt <= (toleranceMeters * 2.4).coerceAtLeast(1.0)) {
                    alignDrawingSourceSnapToSurveyPoint(nearestSurveyPt)
                } else {
                    val reSnap = CoordinateConverter.findNearestCadSnap(
                        tapN = tapN,
                        tapE = tapE,
                        entities = _cadEntities.value,
                        maxSnapRadiusMeters = (toleranceMeters * 1.5).coerceAtLeast(0.5),
                        surveyPoints = emptyList(),
                        hiddenLayers = _hiddenLayers.value
                    )
                    if (reSnap != null && surveyedOnly.isEmpty()) {
                        _drawingShiftSourceSnap.value = reSnap
                        soundSensorManager.playPointSavedBeep()
                        postToast("기준 도면 모서리 변경됨: [${reSnap.label}] -> 상단 [현위치(GPS)로 이동]을 누르거나 측점을 선택하세요.")
                    } else {
                        postToast("이동할 [측량한 포인트(현황점)]를 정확히 터치하거나 상단 배너의 측점 버튼을 누르세요.")
                    }
                }
                return true
            }
        }
        return false
    }

    fun addPointToCogoRuler(pt: SurveyPoint) {
        val current = _cogoRulerSummary.value.selectedPoints + pt
        if (current.size < 2) {
            _cogoRulerSummary.value = CogoRulerSummary(selectedPoints = current)
            return
        }
        val a = current[current.size - 2]
        val b = current.last()
        val dn = b.northing - a.northing
        val de = b.easting - a.easting
        val dz = b.elevation - a.elevation
        val segH = hypot(dn, de)
        val segS = hypot(segH, dz)
        val grade = if (segH > 1e-4) (dz / segH) * 100.0 else 0.0
        val az = (Math.toDegrees(atan2(de, dn)) + 360.0) % 360.0

        var totalH = 0.0
        var totalS = 0.0
        for (i in 0 until current.size - 1) {
            val p1 = current[i]
            val p2 = current[i + 1]
            val h = hypot(p2.northing - p1.northing, p2.easting - p1.easting)
            totalH += h
            totalS += hypot(h, p2.elevation - p1.elevation)
        }

        var areaSqM = 0.0
        if (current.size >= 3) {
            var sum = 0.0
            for (i in current.indices) {
                val p1 = current[i]
                val p2 = current[(i + 1) % current.size]
                sum += (p1.easting * p2.northing - p2.easting * p1.northing)
            }
            areaSqM = abs(sum) * 0.5
        }

        _cogoRulerSummary.value = CogoRulerSummary(
            selectedPoints = current,
            lastSegmentHorizontalMeters = segH,
            lastSegmentSlopeMeters = segS,
            lastSegmentDeltaZ = dz,
            lastSegmentGradePercent = grade,
            lastSegmentAzimuthDeg = az,
            totalHorizontalLengthMeters = totalH,
            totalSlopeLengthMeters = totalS,
            areaSqMeters = areaSqM,
            areaPyeong = areaSqM * 0.3025
        )
    }

    fun clearCogoRuler() {
        _cogoRulerSummary.value = CogoRulerSummary()
    }

    private fun distancePointToSegment(pN: Double, pE: Double, aN: Double, aE: Double, bN: Double, bE: Double): Double {
        val dn = bN - aN
        val de = bE - aE
        val lenSq = dn * dn + de * de
        if (lenSq < 1e-8) return hypot(pN - aN, pE - aE)
        val t = (((pN - aN) * dn + (pE - aE) * de) / lenSq).coerceIn(0.0, 1.0)
        return hypot(pN - (aN + t * dn), pE - (aE + t * de))
    }

    fun undoLastLineSegment() {
        val list = _polylines.value.toMutableList()
        if (list.isEmpty()) {
            postToast("취소할 연결선이 없습니다.")
            return
        }
        pushUndoSnapshot("직전 연결선 취소")
        val lastPoly = list.last()
        if (lastPoly.pointIds.size <= 2) {
            list.removeAt(list.lastIndex)
        } else {
            list[list.lastIndex] = lastPoly.copy(pointIds = lastPoly.pointIds.dropLast(1), isClosed = false)
        }
        _polylines.value = list
        saveCurrentStateToPrefsAndAutoFile()
        postToast("직전 연결선을 취소했습니다.")
    }

    fun toggleLayerVisibility(layerName: String) {
        val cur = _hiddenLayers.value.toMutableSet()
        if (cur.contains(layerName)) cur.remove(layerName) else cur.add(layerName)
        _hiddenLayers.value = cur
    }

    fun setAllLayersVisibility(visible: Boolean) {
        if (visible) {
            _hiddenLayers.value = emptySet()
        } else {
            val allNames = (_cadEntities.value.map { it.layerName } + _points.value.map { it.code } + _customLayers.value.map { it.name }).toSet()
            _hiddenLayers.value = allNames
        }
    }

    fun addCustomLayer(name: String, colorHex: Long) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        if (_customLayers.value.none { it.name == clean }) {
            _customLayers.value = _customLayers.value + CadLayerInfo(clean, true, colorHex, true)
        }
        if (_quickCodes.value.none { it.code == clean }) {
            _quickCodes.value = _quickCodes.value + QuickCodePreset(clean, "사용자 레이어", colorHex, true)
        }
        updateMeasurePreset(code = clean)
        postToast("레이어/코드 '$clean' 생성 및 선택 완료")
    }

    fun setCadSnapEnabled(enabled: Boolean) {
        _cadSnapEnabled.value = enabled
        if (!enabled) {
            _pendingSnappedTarget.value = null
            _lastSnappedTarget.value = null
        }
        postToast(if (enabled) "도면측설 스냅 켜짐" else "도면측설 스냅 꺼짐")
    }

    fun previewCadSnapFromCanvas(tapNorthing: Double, tapEasting: Double, toleranceMeters: Double): CadSnapTarget? {
        if (_canvasToolMode.value == CanvasToolMode.DRAWING_SNAP_SHIFT) {
            return if (_drawingShiftSourceSnap.value == null) {
                CoordinateConverter.findNearestCadSnap(
                    tapN = tapNorthing,
                    tapE = tapEasting,
                    entities = _cadEntities.value,
                    maxSnapRadiusMeters = toleranceMeters,
                    surveyPoints = emptyList(),
                    hiddenLayers = _hiddenLayers.value
                )
            } else {
                val surveyedOnly = _points.value.filterNot {
                    it.memo.startsWith("V-World 지적기준점") || it.timestamp == "DXF 도면"
                }.ifEmpty { _points.value }
                CoordinateConverter.findNearestCadSnap(
                    tapN = tapNorthing,
                    tapE = tapEasting,
                    entities = emptyList(),
                    maxSnapRadiusMeters = toleranceMeters,
                    surveyPoints = surveyedOnly,
                    hiddenLayers = _hiddenLayers.value
                )
            }
        }
        if (!_cadSnapEnabled.value) return null
        if (_cadEntities.value.isEmpty() && _points.value.isEmpty()) return null
        return CoordinateConverter.findNearestCadSnap(
            tapN = tapNorthing,
            tapE = tapEasting,
            entities = _cadEntities.value,
            maxSnapRadiusMeters = toleranceMeters,
            surveyPoints = _points.value,
            hiddenLayers = _hiddenLayers.value
        )
    }

    fun lockPendingCadSnap(snap: CadSnapTarget) {
        if (_canvasToolMode.value == CanvasToolMode.DRAWING_SNAP_SHIFT) {
            val currentSrc = _drawingShiftSourceSnap.value
            if (currentSrc == null) {
                _drawingShiftSourceSnap.value = snap
                soundSensorManager.playPointSavedBeep()
                soundSensorManager.speak("도면 모서리가 잡혔습니다. 이동할 측량 포인트를 터치하세요.")
                postToast("🎯 1단계 완료(${snap.label}): 이제 맞출 [측량한 포인트(현황점)]를 터치하세요!")
            } else {
                val surveyedOnly = _points.value.filterNot {
                    it.memo.startsWith("V-World 지적기준점") || it.timestamp == "DXF 도면"
                }.ifEmpty { _points.value }
                val matchedPt = surveyedOnly.minByOrNull { hypot(it.northing - snap.northing, it.easting - snap.easting) }
                if (matchedPt != null && hypot(matchedPt.northing - snap.northing, matchedPt.easting - snap.easting) < 0.05) {
                    alignDrawingSourceSnapToSurveyPoint(matchedPt)
                } else {
                    val dn = snap.northing - currentSrc.northing
                    val de = snap.easting - currentSrc.easting
                    shiftExternalCadEntities(dn, de, 0.0, alsoShiftBackground = _shiftIncludeBackgroundTiles.value, targetLabel = snap.label)
                    _drawingShiftSourceSnap.value = null
                    _canvasToolMode.value = CanvasToolMode.NORMAL
                }
            }
            return
        }
        _pendingSnappedTarget.value = snap
        soundSensorManager.playPointSavedBeep()
    }

    fun clearPendingCadSnap() {
        _pendingSnappedTarget.value = null
    }

    fun startStakeoutFromPendingSnap() {
        val snap = _pendingSnappedTarget.value ?: return
        _pendingSnappedTarget.value = null
        _lastSnappedTarget.value = snap
        _activeStakeoutSegment.value = if (snap.segmentStartNE != null && snap.segmentEndNE != null) {
            Pair(snap.segmentStartNE, snap.segmentEndNE)
        } else null
        val (lat, lon) = CoordinateConverter.planeToWgs84(snap.northing, snap.easting, _coordinateSystem.value)
        val stakePt = SurveyPoint(
            seqNumber = 0,
            name = "도면-${snap.snapType.koreanLabel}",
            code = snap.layerName,
            northing = snap.northing,
            easting = snap.easting,
            elevation = snap.elevationZ,
            latitude = lat,
            longitude = lon,
            ellipsoidalHeight = snap.elevationZ + 25.0,
            geoidSeparation = 25.0,
            antennaHeight = 0.0,
            timestamp = "도면측설 스냅",
            memo = snap.label
        )
        setStakeoutTarget(stakePt, keepSegment = true)
    }

    fun addPendingSnapToSurveyPoints() {
        val snap = _pendingSnappedTarget.value ?: return
        addManualStakeoutPoint(
            name = generateNextPointName(),
            code = snap.layerName.ifBlank { "도면점" },
            northing = snap.northing,
            easting = snap.easting,
            elevation = snap.elevationZ,
            memo = snap.label,
            setAsImmediateTarget = false
        )
        _pendingSnappedTarget.value = null
    }

    fun setStakeoutDirectionMode(mode: StakeoutDirectionMode) {
        _stakeoutDirectionMode.value = mode
        refreshStakeoutGuidance(_currentPlaneCoord.value, _gnssStatus.value)
        postToast("측설 방향 기준: ${mode.koreanLabel}")
    }

    fun selectNearestPointForStakeout() {
        val cur = _currentPlaneCoord.value
        val pts = _points.value
        if (pts.isNotEmpty()) {
            val nearest = pts.minByOrNull { hypot(it.northing - cur.northing, it.easting - cur.easting) }
            if (nearest != null) {
                val dist = hypot(nearest.northing - cur.northing, nearest.easting - cur.easting)
                setStakeoutTarget(nearest)
                postToast(String.format(Locale.US, "[근접점 자동선택] #%d %s (거리 %.2fm)", nearest.seqNumber, nearest.name, dist))
                return
            }
        }
        val cadSnap = CoordinateConverter.findNearestCadSnap(
            tapN = cur.northing,
            tapE = cur.easting,
            entities = _cadEntities.value,
            maxSnapRadiusMeters = 5000.0,
            surveyPoints = emptyList(),
            hiddenLayers = _hiddenLayers.value
        )
        if (cadSnap != null) {
            _pendingSnappedTarget.value = cadSnap
            startStakeoutFromPendingSnap()
        } else {
            postToast("선택할 측점이나 도면 객체가 없습니다.")
        }
    }

    fun stepStakeoutTarget(delta: Int) {
        val list = _points.value
        if (list.isEmpty()) {
            postToast("저장된 측점 목록이 없습니다.")
            return
        }
        val curId = _stakeoutTarget.value?.id
        val curIdx = list.indexOfFirst { it.id == curId }
        val nextIdx = if (curIdx < 0) 0 else (curIdx + delta + list.size) % list.size
        setStakeoutTarget(list[nextIdx])
    }

    fun selectStakeoutByPointQuery(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val list = _points.value
        val match = list.find { it.name.equals(q, ignoreCase = true) }
            ?: list.find { it.seqNumber.toString() == q }
            ?: list.find { it.name.contains(q, ignoreCase = true) }
        return if (match != null) {
            setStakeoutTarget(match)
            true
        } else {
            postToast("측번 '$q'에 해당하는 측점을 찾을 수 없습니다.")
            false
        }
    }

    fun addManualStakeoutPoint(
        name: String,
        code: String,
        northing: Double,
        easting: Double,
        elevation: Double,
        memo: String,
        setAsImmediateTarget: Boolean
    ) {
        val seq = generateNextSeqNumber()
        val (lat, lon) = CoordinateConverter.planeToWgs84(northing, easting, _coordinateSystem.value)
        val pt = SurveyPoint(
            seqNumber = seq,
            name = name.ifBlank { "ST-$seq" },
            code = code.ifBlank { "측설점" },
            northing = northing,
            easting = easting,
            elevation = elevation,
            latitude = lat,
            longitude = lon,
            ellipsoidalHeight = elevation + 25.0,
            geoidSeparation = 25.0,
            antennaHeight = 0.0,
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()),
            memo = memo
        )
        pushUndoSnapshot("측점 #${pt.seqNumber}(${pt.name}) 추가")
        _points.value = _points.value + pt
        saveCurrentStateToPrefsAndAutoFile()
        if (setAsImmediateTarget) setStakeoutTarget(pt) else postToast("측점 #${pt.seqNumber} (${pt.name}) 추가됨")
    }

    fun deletePoint(pointId: String) {
        val targetPt = _points.value.find { it.id == pointId }
        pushUndoSnapshot("측점 ${targetPt?.name ?: ""} 삭제")
        _points.value = _points.value.filterNot { it.id == pointId }
        if (_stakeoutTarget.value?.id == pointId) setStakeoutTarget(null)
        saveCurrentStateToPrefsAndAutoFile()
        postToast("측점이 삭제되었습니다.")
    }

    fun batchUpdateSurveyPoints(
        targetPointIds: Set<String>,
        applyAntennaHeightChange: Boolean = false,
        useEachPointRecordedAntennaAsOld: Boolean = false,
        manualOldAntennaHeight: Double = 2.400,
        newAntennaHeight: Double = 1.800,
        applyDirectDeltaZ: Boolean = false,
        directDeltaZ: Double = 0.0,
        applyNewCode: Boolean = false,
        newCode: String = "",
        applyNewMemo: Boolean = false,
        newMemo: String = ""
    ) {
        if (targetPointIds.isEmpty()) {
            postToast("수정할 측점을 먼저 1개 이상 선택해주세요.")
            return
        }
        val cleanNewCode = newCode.trim()
        if (applyNewCode && cleanNewCode.isEmpty()) {
            postToast("변경할 새 코드명을 입력해주세요.")
            return
        }

        pushUndoSnapshot("측점 ${targetPointIds.size}개 일괄 수정")
        var updatedCount = 0
        var sampleDeltaZ = 0.0
        val updatedPoints = _points.value.map { pt ->
            if (!targetPointIds.contains(pt.id)) pt
            else {
                updatedCount++
                var nextZ = pt.elevation
                var nextEllipH = pt.ellipsoidalHeight
                var nextAntH = pt.antennaHeight

                if (applyAntennaHeightChange) {
                    val oldH = if (useEachPointRecordedAntennaAsOld) pt.antennaHeight else manualOldAntennaHeight
                    val cleanNewH = newAntennaHeight.coerceIn(0.0, 15.0)
                    val dzFromAntenna = oldH - cleanNewH
                    sampleDeltaZ = dzFromAntenna
                    nextZ += dzFromAntenna
                    nextEllipH += dzFromAntenna
                    nextAntH = cleanNewH
                }
                if (applyDirectDeltaZ) {
                    sampleDeltaZ += directDeltaZ
                    nextZ += directDeltaZ
                    nextEllipH += directDeltaZ
                }
                val nextCode = if (applyNewCode && cleanNewCode.isNotEmpty()) cleanNewCode else pt.code
                val nextMemo = if (applyNewMemo) newMemo.trim() else pt.memo
                pt.copy(code = nextCode, elevation = nextZ, ellipsoidalHeight = nextEllipH, antennaHeight = nextAntH, memo = nextMemo)
            }
        }
        _points.value = updatedPoints

        if (applyNewCode && cleanNewCode.isNotEmpty()) {
            val presetColor = _quickCodes.value.find { it.code == cleanNewCode }?.colorHex ?: 0xFF38BDF8
            _polylines.value = _polylines.value.map { poly ->
                if (poly.pointIds.isNotEmpty() && poly.pointIds.all { targetPointIds.contains(it) }) {
                    poly.copy(code = cleanNewCode, colorHex = presetColor)
                } else poly
            }
            promoteCodeToFront(cleanNewCode)
        }

        _stakeoutTarget.value?.let { st ->
            if (targetPointIds.contains(st.id)) {
                val refreshedTarget = updatedPoints.find { it.id == st.id }
                if (refreshedTarget != null) {
                    _stakeoutTarget.value = refreshedTarget
                    refreshStakeoutGuidance(_currentPlaneCoord.value, _gnssStatus.value)
                }
            }
        }

        saveCurrentStateToPrefsAndAutoFile(actionLabel = "측점일괄수정(${updatedCount}점)")
        soundSensorManager.playPointSavedBeep()
        val summaryParts = mutableListOf<String>()
        if (applyAntennaHeightChange) {
            summaryParts.add(String.format(Locale.US, "안테나고→%.3fm (표고 %+.3fm 보정)", newAntennaHeight, sampleDeltaZ))
        } else if (applyDirectDeltaZ) {
            summaryParts.add(String.format(Locale.US, "표고 %+.3fm 보정", directDeltaZ))
        }
        if (applyNewCode && cleanNewCode.isNotEmpty()) summaryParts.add("코드→'$cleanNewCode'")
        if (applyNewMemo) summaryParts.add("메모변경")
        val detailStr = if (summaryParts.isNotEmpty()) summaryParts.joinToString(", ") else "수정 완료"
        soundSensorManager.speak("측점 ${updatedCount}개를 일괄 수정했습니다.")
        postToast("✅ 측점 ${updatedCount}개 일괄 수정 완료: $detailStr")
    }

    fun batchDeleteSurveyPoints(targetPointIds: Set<String>) {
        if (targetPointIds.isEmpty()) {
            postToast("삭제할 측점을 먼저 선택해주세요.")
            return
        }
        pushUndoSnapshot("측점 ${targetPointIds.size}개 일괄 삭제")
        _points.value = _points.value.filterNot { targetPointIds.contains(it.id) }
        _polylines.value = _polylines.value.mapNotNull { poly ->
            val rem = poly.pointIds.filterNot { targetPointIds.contains(it) }
            if (rem.size >= 2) poly.copy(pointIds = rem) else null
        }
        if (_stakeoutTarget.value?.id in targetPointIds) setStakeoutTarget(null)
        saveCurrentStateToPrefsAndAutoFile(actionLabel = "측점일괄삭제(${targetPointIds.size}점)")
        postToast("선택한 측점 ${targetPointIds.size}개를 일괄 삭제했습니다.")
    }

    fun setStakeoutTarget(point: SurveyPoint?, keepSegment: Boolean = false) {
        _stakeoutTarget.value = point
        if (!keepSegment) _activeStakeoutSegment.value = null
        if (point == null) {
            _stakeoutGuidance.value = null
            soundSensorManager.stopStakeoutSound()
            postToast("좌표/도면 측설이 종료되었습니다.")
        } else {
            refreshStakeoutGuidance(_currentPlaneCoord.value, _gnssStatus.value)
            soundSensorManager.speak("${point.name} 측설을 시작합니다")
            postToast("[측설 시작] ${point.name} (${point.code})")
        }
    }

    fun setStakeoutIncludeZ(includeZ: Boolean) { _stakeoutIncludeZ.value = includeZ }

    fun setStakeoutSoundEnabled(enabled: Boolean) {
        _stakeoutSoundEnabled.value = enabled
        val g = _stakeoutGuidance.value
        if (g != null) {
            soundSensorManager.updateStakeoutDistance(g.horizontalDistance, enabled)
        } else {
            soundSensorManager.stopStakeoutSound()
        }
    }

    fun shiftExternalCadEntities(
        deltaN: Double,
        deltaE: Double,
        deltaZ: Double = 0.0,
        alsoShiftBackground: Boolean = _shiftIncludeBackgroundTiles.value,
        targetLabel: String = ""
    ) {
        if (_cadEntities.value.isEmpty() && !alsoShiftBackground) {
            postToast("이동할 도면(지적도/DXF/DWG/SHP)이 없습니다.")
            return
        }
        val labelSuffix = if (targetLabel.isNotBlank()) " → $targetLabel" else ""
        pushUndoSnapshot(String.format(Locale.US, "도면·배경 맞춤이동%s(ΔN:%+.2f, ΔE:%+.2f)", labelSuffix, deltaN, deltaE))

        if (_cadEntities.value.isNotEmpty()) {
            _cadEntities.value = _cadEntities.value.map { ent ->
                ent.copy(
                    verticesNE = ent.verticesNE.map { (n, e) -> Pair(n + deltaN, e + deltaE) },
                    elevationZ = ent.elevationZ + deltaZ
                )
            }
        }

        val hasVWorldControlPts = _points.value.any { it.memo.startsWith("V-World 지적기준점") }
        if (hasVWorldControlPts) {
            _points.value = _points.value.map { pt ->
                if (pt.memo.startsWith("V-World 지적기준점")) {
                    val nextN = pt.northing + deltaN
                    val nextE = pt.easting + deltaE
                    val (lat, lon) = CoordinateConverter.planeToWgs84(nextN, nextE, _coordinateSystem.value)
                    pt.copy(northing = nextN, easting = nextE, latitude = lat, longitude = lon)
                } else pt
            }
        }

        if (alsoShiftBackground && hypot(deltaN, deltaE) < 500.0) {
            val curBg = _backgroundShiftNE.value
            _backgroundShiftNE.value = Pair(curBg.first + deltaN, curBg.second + deltaE)
        }

        _pendingSnappedTarget.value = null
        _drawingShiftSourceSnap.value = null
        saveCurrentStateToPrefsAndAutoFile(actionLabel = "도면배경맞춤이동")
        soundSensorManager.playPointSavedBeep()
        soundSensorManager.speak("도면과 배경을 현황점에 맞춰 일괄 이동했습니다.")
        val targetInfo = if (targetLabel.isNotBlank()) "[$targetLabel 기준] " else ""
        postToast(String.format(Locale.US, "✅ %s도면·배경 일괄 이동 완료 (ΔN:%+.3fm, ΔE:%+.3fm)", targetInfo, deltaN, deltaE))
    }

    fun searchAddress(query: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { tileMapManager.searchKoreanAddressBlocking(query) }
            _addressSearchResults.value = res
            if (res.isEmpty()) postToast("검색 결과가 없습니다. 시/군/구 또는 동/리 이름을 입력해 보세요.")
        }
    }

    fun moveMapToAddressResult(item: TileMapManager.AddressSearchResult) {
        val plane = CoordinateConverter.wgs84ToPlane(item.latitude, item.longitude, 50.0, _coordinateSystem.value, _useKnGeoid18.value)
        _mapCenterOverrideNE.value = Pair(plane.northing, plane.easting)
        if (_backgroundMapType.value == BackgroundMapType.NONE) {
            _backgroundMapType.value = BackgroundMapType.GOOGLE_SATELLITE
        }
        postToast("지도 이동: ${item.displayName}")
    }

    fun clearMapCenterOverride() { _mapCenterOverrideNE.value = null }

    fun loadExternalCadOrShpUri(uri: Uri, fileNameHint: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val resolvedName = fileNameHint.ifBlank {
                    var name = "외부도면.dxf"
                    app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
                    }
                    name
                }
                val rawBytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("파일을 읽을 수 없습니다.")
                val lowerName = resolvedName.lowercase(Locale.US)

                if (lowerName.endsWith(".shp")) {
                    val shpEnts = ShpFileParser.parseShpBytes(rawBytes, resolvedName)
                    if (shpEnts.isNotEmpty()) {
                        pushUndoSnapshot("SHP 도면 불러오기")
                        _cadEntities.value = _cadEntities.value + shpEnts
                        saveCurrentStateToPrefsAndAutoFile()
                        postToast("SHP 도면 객체 ${shpEnts.size}개를 불러왔습니다.")
                    } else {
                        postToast("SHP 파일에서 도형 객체를 찾지 못했습니다.")
                    }
                } else if (lowerName.endsWith(".csv") || lowerName.endsWith(".txt") || lowerName.endsWith(".nez")) {
                    val text = String(rawBytes, Charsets.UTF_8)
                    val imported = FileIoManager.parseCsvOrTxtPoints(text, generateNextSeqNumber())
                    if (imported.isNotEmpty()) {
                        pushUndoSnapshot("좌표파일(${imported.size}점) 불러오기")
                        _points.value = _points.value + imported
                        saveCurrentStateToPrefsAndAutoFile()
                        postToast("좌표점 ${imported.size}개를 불러왔습니다.")
                    } else {
                        postToast("불러올 수 있는 좌표 데이터가 없습니다.")
                    }
                } else {
                    val cadRes = FileIoManager.parseExternalCadBytes(rawBytes, resolvedName, generateNextSeqNumber())
                    if (cadRes.entities.isNotEmpty()) {
                        pushUndoSnapshot("CAD 도면 불러오기")
                        val keptVworld = _cadEntities.value.filter { it.sourceType == "VWORLD" }
                        _cadEntities.value = keptVworld + cadRes.entities
                        saveCurrentStateToPrefsAndAutoFile(actionLabel = "CAD도면로드(${cadRes.entities.size}개)")
                    }
                    postToast(cadRes.message)
                }
            } catch (e: Exception) {
                postToast("파일 불러오기 실패: ${e.message}")
            }
        }
    }

    fun clearExternalCadEntities() {
        if (_cadEntities.value.isNotEmpty()) {
            pushUndoSnapshot("외부 도면(${_cadEntities.value.size}개) 지우기")
        }
        _cadEntities.value = emptyList()
        _pendingSnappedTarget.value = null
        _lastSnappedTarget.value = null
        saveCurrentStateToPrefsAndAutoFile()
        postToast("불러온 외부 도면(DXF/DWG/SHP) 레이어를 초기화했습니다.")
    }

    fun setBackgroundMapType(type: BackgroundMapType) {
        _backgroundMapType.value = type
        postToast("배경맵: ${type.koreanLabel}")
    }

    fun setBackgroundMapAlpha(alpha: Float) {
        _backgroundMapAlpha.value = alpha.coerceIn(0.2f, 1.0f)
    }

    fun setCoordinateSystem(sys: KoreanCoordinateSystem, transformExistingPoints: Boolean = true) {
        _coordinateSystem.value = sys
        if (transformExistingPoints && _points.value.isNotEmpty()) {
            var transformedCount = 0
            _points.value = _points.value.map { pt ->
                if (pt.latitude != 0.0 && pt.longitude != 0.0) {
                    transformedCount++
                    val newPlane = CoordinateConverter.wgs84ToPlane(
                        latDeg = pt.latitude,
                        lonDeg = pt.longitude,
                        ellipsoidalHeight = pt.ellipsoidalHeight,
                        system = sys,
                        useKnGeoid18 = _useKnGeoid18.value,
                        nmeaGeoidFallback = pt.geoidSeparation
                    )
                    val newElev = newPlane.orthometricHeight - pt.antennaHeight
                    pt.copy(
                        northing = newPlane.northing,
                        easting = newPlane.easting,
                        elevation = newElev,
                        geoidSeparation = newPlane.geoidSeparation
                    )
                } else pt
            }
            postToast("좌표계 변경 및 기측정 ${transformedCount}점 일괄 좌표변환 완료 (${sys.displayName})")
        } else {
            postToast("좌표계 변경: ${sys.displayName}")
        }
        updateGnssAndCoordinates(_gnssStatus.value)
        saveCurrentStateToPrefsAndAutoFile()
    }

    fun setUseKnGeoid18(enabled: Boolean) {
        _useKnGeoid18.value = enabled
        updateGnssAndCoordinates(_gnssStatus.value)
        saveCurrentStateToPrefsAndAutoFile()
    }

    fun setAntennaHeight(heightMeters: Double) {
        val cleanH = heightMeters.coerceIn(0.0, 10.0)
        _antennaHeight.value = cleanH
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        if (_lastSurveyDateYmd.value.isBlank()) _lastSurveyDateYmd.value = todayYmd
        if (abs(cleanH - _favoriteAntennaHeight.value) <= 0.0005) {
            _nextDayAntennaWarningActive.value = false
            confirmedNonFavoriteAntennaToday = true
        } else {
            confirmedNonFavoriteAntennaToday = false
        }
        updateGnssAndCoordinates(_gnssStatus.value)
        saveCurrentStateToPrefsAndAutoFile()
    }

    fun setFavoriteAntennaHeight(heightMeters: Double, alsoApplyToCurrent: Boolean = false) {
        val cleanFav = heightMeters.coerceIn(0.0, 10.0)
        _favoriteAntennaHeight.value = cleanFav
        if (alsoApplyToCurrent) {
            _antennaHeight.value = cleanFav
            updateGnssAndCoordinates(_gnssStatus.value)
        }
        saveCurrentStateToPrefsOnly()
        postToast(String.format(Locale.US, "자주 사용하는 기본 안테나고: %.3fm 저장됨", cleanFav))
    }

    private fun markTodayAsSurveyedDate() {
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        if (_lastSurveyDateYmd.value != todayYmd) {
            _lastSurveyDateYmd.value = todayYmd
            saveCurrentStateToPrefsOnly()
        }
    }

    fun triggerNextDayAntennaWarningIfNeeded(): Boolean {
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val isMismatch = abs(_antennaHeight.value - _favoriteAntennaHeight.value) > 0.0005
        if (!isMismatch) {
            markTodayAsSurveyedDate()
            return false
        }
        val isNewDay = _lastSurveyDateYmd.value.isNotBlank() && _lastSurveyDateYmd.value != todayYmd
        if ((isNewDay || !confirmedNonFavoriteAntennaToday) && isMismatch) {
            _nextDayAntennaWarningActive.value = true
            soundSensorManager.speak("주의하세요. 현재 안테나 폴대 높이가 자주 사용하는 안테나고와 다릅니다.")
            postToast(String.format(Locale.US, "⚠️ 안테나고 확인: 현재 %.3fm (자주 사용하는 안테나고: %.3fm)", _antennaHeight.value, _favoriteAntennaHeight.value))
            return true
        }
        return false
    }

    fun applyFavoriteAntennaHeightAndDismissWarning() {
        _antennaHeight.value = _favoriteAntennaHeight.value
        confirmedNonFavoriteAntennaToday = true
        _nextDayAntennaWarningActive.value = false
        markTodayAsSurveyedDate()
        updateGnssAndCoordinates(_gnssStatus.value)
        saveCurrentStateToPrefsAndAutoFile()
        postToast(String.format(Locale.US, "자주 사용하는 안테나고(%.3fm)로 변경되었습니다.", _favoriteAntennaHeight.value))
    }

    fun keepCurrentAntennaHeightForTodayAndDismissWarning() {
        confirmedNonFavoriteAntennaToday = true
        _nextDayAntennaWarningActive.value = false
        markTodayAsSurveyedDate()
        postToast(String.format(Locale.US, "오늘 작업은 현재 안테나고(%.3fm)를 유지합니다.", _antennaHeight.value))
    }

    fun setImuTiltCompensation(enabled: Boolean) {
        _imuTiltCompensationEnabled.value = enabled
        if (enabled) _imuBubbleVisibleOnMap.value = true
        if (!enabled && !_imuBubbleVisibleOnMap.value) _imuTiltWarningAlertActive.value = false
        updateGnssAndCoordinates(_gnssStatus.value.copy(imuTiltActive = enabled))
        saveCurrentStateToPrefsOnly()
        postToast(if (enabled) "IMU 기울기 보정 및 수평 감지가 켜졌습니다." else "IMU 기울기 보정이 꺼졌습니다.")
    }

    fun setImuBubbleVisibleOnMap(visible: Boolean) {
        _imuBubbleVisibleOnMap.value = visible
        if (!visible && !_imuTiltCompensationEnabled.value) {
            _imuTiltWarningAlertActive.value = false
        }
        saveCurrentStateToPrefsOnly()
        postToast(if (visible) "수평기포관 표시: 켜짐 (ON)" else "수평기포관 표시: 꺼짐 (OFF)")
    }

    fun setImuMaxTiltLimitDeg(limitDeg: Double) {
        val clean = limitDeg.coerceIn(2.0, 60.0)
        _imuMaxTiltLimitDeg.value = clean
        saveCurrentStateToPrefsOnly()
        postToast(String.format(Locale.US, "수평 경고 허용 각도: %.0f° 설정됨", clean))
    }

    fun setImuBlockRecordOnExcessTilt(block: Boolean) {
        _imuBlockRecordOnExcessTilt.value = block
        saveCurrentStateToPrefsOnly()
        postToast(if (block) "허용 기울기 초과 시 측점 저장 자동 차단: ON" else "허용 기울기 초과 시 측점 저장 자동 차단: OFF")
    }

    fun calibrateImuBubbleZero() {
        soundSensorManager.calibrateBubbleZero()
        soundSensorManager.playPointSavedBeep()
        postToast("현재 자세를 전자기포관 수평 영점(0.0°)으로 보정했습니다.")
    }

    fun resetImuBubbleZero() {
        soundSensorManager.resetBubbleZeroCalibration()
        postToast("전자기포관 영점 보정을 초기화했습니다.")
    }

    fun getEffectiveTiltAngleDeg(): Double {
        val g = _gnssStatus.value
        return if (g.sourceMode == PositioningSourceMode.BLUETOOTH_GNSS && g.imuTiltAngleDeg > 0.2) {
            g.imuTiltAngleDeg
        } else {
            soundSensorManager.deviceTiltAngleDeg.value.toDouble()
        }
    }

    fun getEffectiveTiltAzimuthDeg(): Double {
        val g = _gnssStatus.value
        return if (g.sourceMode == PositioningSourceMode.BLUETOOTH_GNSS && g.imuTiltAngleDeg > 0.2) {
            g.imuTiltAzimuthDeg
        } else {
            soundSensorManager.deviceTiltAzimuthDeg.value.toDouble()
        }
    }

    fun computeCurrentTiltCorrectionNEH(): Triple<Double, Double, Double> {
        return nmeaParser.computeTiltCorrection(getEffectiveTiltAngleDeg(), getEffectiveTiltAzimuthDeg(), _antennaHeight.value)
    }

    fun setVWorldApiKey(key: String) {
        _vworldApiKey.value = key.trim()
        saveCurrentStateToPrefsOnly()
        postToast(if (key.isBlank()) "브이월드 기본 내장 인증키를 사용합니다." else "브이월드 사용자 인증키가 저장되었습니다.")
    }

    fun setVWorldSearchRadius(radiusMeters: Int) {
        _vworldSearchRadiusMeters.value = radiusMeters.coerceIn(100, 2000)
        saveCurrentStateToPrefsOnly()
    }

    fun fetchVWorldCadastralMap(
        radiusMeters: Int = _vworldSearchRadiusMeters.value,
        useMapCenter: Boolean = false,
        replaceExistingCadastral: Boolean = true,
        includeControlPoints: Boolean = true,
        registerControlPointsToSurveyPoints: Boolean = true,
        forceSampleMode: Boolean = false,
        fallbackToSampleOnError: Boolean = true
    ) {
        if (_isVWorldLoading.value) return
        _vworldSearchRadiusMeters.value = radiusMeters.coerceIn(100, 2000)
        saveCurrentStateToPrefsOnly()

        val g = _gnssStatus.value
        val viewCenterNE = _currentViewportCenterNE.value
        val overrideNE = _mapCenterOverrideNE.value
        val effectiveCenterNE = if (useMapCenter) {
            when {
                viewCenterNE != null && (abs(viewCenterNE.first) > 50000.0 || abs(viewCenterNE.second) > 50000.0) -> viewCenterNE
                overrideNE != null && (abs(overrideNE.first) > 50000.0 || abs(overrideNE.second) > 50000.0) -> overrideNE
                _points.value.isNotEmpty() -> Pair(_points.value.last().northing, _points.value.last().easting)
                else -> viewCenterNE ?: overrideNE
            }
        } else null

        val (targetLat, targetLon) = if (useMapCenter && effectiveCenterNE != null && (effectiveCenterNE.first != 0.0 || effectiveCenterNE.second != 0.0)) {
            val bg = _backgroundShiftNE.value
            val cleanN = if (hypot(bg.first, bg.second) < 500.0) effectiveCenterNE.first - bg.first else effectiveCenterNE.first
            val cleanE = if (hypot(bg.first, bg.second) < 500.0) effectiveCenterNE.second - bg.second else effectiveCenterNE.second
            CoordinateConverter.planeToWgs84(cleanN, cleanE, _coordinateSystem.value)
        } else if (g.latitude != 0.0 && g.longitude != 0.0) {
            Pair(g.latitude, g.longitude)
        } else if (_points.value.isNotEmpty()) {
            val lastPt = _points.value.last()
            if (lastPt.latitude != 0.0 && lastPt.longitude != 0.0) Pair(lastPt.latitude, lastPt.longitude)
            else CoordinateConverter.planeToWgs84(lastPt.northing, lastPt.easting, _coordinateSystem.value)
        } else if (viewCenterNE != null && (viewCenterNE.first != 0.0 || viewCenterNE.second != 0.0)) {
            CoordinateConverter.planeToWgs84(viewCenterNE.first, viewCenterNE.second, _coordinateSystem.value)
        } else {
            Pair(36.642435, 127.489031)
        }

        _isVWorldLoading.value = true
        _vworldStatusMessage.value = "브이월드 서버에서 반경 ${radiusMeters}m 연속지적도 수신 중..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = if (forceSampleMode) {
                    VWorldCadastralManager.generateSampleCadastralEntities(targetLat, targetLon, radiusMeters, _coordinateSystem.value, includeControlPoints)
                } else {
                    VWorldCadastralManager.fetchCadastralEntities(targetLat, targetLon, radiusMeters, _vworldApiKey.value, _coordinateSystem.value, includeControlPoints, fallbackToSampleOnError)
                }
                withContext(Dispatchers.Main) {
                    _isVWorldLoading.value = false
                    _vworldStatusMessage.value = res.message
                    if (res.success && res.entities.isNotEmpty()) {
                        pushUndoSnapshot("V-World 지적도(${res.parcelCount}필지) 불러오기")
                        val baseList = if (replaceExistingCadastral) _cadEntities.value.filterNot { it.sourceType == "VWORLD" } else _cadEntities.value
                        _cadEntities.value = baseList + res.entities
                        saveCurrentStateToPrefsAndAutoFile(actionLabel = "VWorld지적도(${res.parcelCount}필지)")
                        soundSensorManager.playPointSavedBeep()
                        soundSensorManager.speak("지적도 ${res.parcelCount}필지를 도면에 불러왔습니다.")
                        postToast(res.message)
                    } else {
                        postToast(res.message)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isVWorldLoading.value = false
                    postToast("지적도 불러오기 오류: ${e.message}")
                }
            }
        }
    }

    fun clearVWorldCadastralEntities() {
        val current = _cadEntities.value
        if (current.none { it.sourceType == "VWORLD" }) {
            postToast("현재 불러온 V-World 지적도 레이어가 없습니다.")
            return
        }
        pushUndoSnapshot("V-World 지적도 지우기")
        _cadEntities.value = current.filterNot { it.sourceType == "VWORLD" }
        saveCurrentStateToPrefsAndAutoFile()
        postToast("V-World 지적도 레이어를 지웠습니다.")
    }

    // =========================================================================
    // PROJECT MANAGEMENT & EXPORT
    // =========================================================================

    fun refreshSavedProjectsList() {
        _savedProjects.value = FileIoManager.listSavedProjects(getApplication())
    }

    fun createNewProject(newName: String, clearExistingPoints: Boolean = true) {
        val clean = newName.trim().ifBlank { "간단측량_${SimpleDateFormat("MMdd_HHmm", Locale.KOREA).format(Date())}" }
        _projectName.value = clean
        if (clearExistingPoints) {
            pushUndoSnapshot("새 프로젝트($clean) 생성")
            _points.value = emptyList()
            _polylines.value = emptyList()
            _cadEntities.value = emptyList()
            _stakeoutTarget.value = null
            _backgroundShiftNE.value = Pair(0.0, 0.0)
        }
        saveCurrentStateToPrefsAndAutoFile(actionLabel = "새프로젝트($clean)")
        postToast("프로젝트 '$clean' 시작됨")
    }

    fun saveCurrentProjectExplicitly(customName: String? = null) {
        if (!customName.isNullOrBlank()) {
            _projectName.value = customName.trim()
        }
        saveCurrentStateToPrefsAndAutoFile(actionLabel = "수동저장")
        soundSensorManager.playPointSavedBeep()
        postToast("💾 프로젝트 '${_projectName.value}' 저장 완료 (측점 ${_points.value.size}개)")
    }

    fun loadProjectFile(file: File) {
        val loaded = FileIoManager.loadProjectFromFile(file)
        if (loaded != null) {
            pushUndoSnapshot("프로젝트 '${loaded.projectName}' 열기")
            applyLoadedProject(loaded)
            saveCurrentStateToPrefsOnly()
            postToast("프로젝트 '${loaded.projectName}' 로드 완료 (측점 ${loaded.points.size}개)")
        } else {
            postToast("프로젝트 파일을 읽을 수 없습니다.")
        }
    }

    private fun applyLoadedProject(proj: SurveyProjectData) {
        _projectName.value = proj.projectName
        _coordinateSystem.value = proj.coordinateSystem
        _points.value = proj.points
        _polylines.value = proj.figures
        if (proj.cadEntities.isNotEmpty()) {
            _cadEntities.value = proj.cadEntities
        }
    }

    private fun buildCurrentProjectData(): SurveyProjectData {
        return SurveyProjectData(
            projectName = _projectName.value,
            coordinateSystem = _coordinateSystem.value,
            createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()),
            points = _points.value,
            figures = _polylines.value,
            cadEntities = emptyList()
        )
    }

    fun exportDxfNow(encodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN) {
        if (_points.value.isEmpty()) {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val f = FileIoManager.exportDxf(getApplication(), _projectName.value, _points.value, _polylines.value, encodingMode, _exportConfig.value)
            postToast("DXF 저장 완료: 다운로드/간단측량/${f.name}")
        }
    }

    fun exportCsvNow() {
        if (_points.value.isEmpty()) {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val f = FileIoManager.exportCustomTextOrCsv(getApplication(), _projectName.value, _points.value, _exportConfig.value)
            postToast("CSV 저장 완료: 다운로드/간단측량/${f.name}")
        }
    }

    fun exportKmlNow() {
        if (_points.value.isEmpty()) {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = FileIoManager.buildKmlBytes(_projectName.value, _points.value)
                val f = FileIoManager.writeFileSafelyToDownloadAndInternal(getApplication(), "", "${_projectName.value}.kml", bytes, "application/vnd.google-earth.kml+xml")
                postToast("KML 저장 완료: 다운로드/간단측량/${f.name}")
            } catch (e: Exception) {
                postToast("KML 저장 오류: ${e.message}")
            }
        }
    }

    fun exportAllFormatsBundleNow() {
        val list = _points.value
        if (list.isEmpty()) {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val dxfFile = FileIoManager.exportAllFormatsBundle(getApplication(), _projectName.value, _coordinateSystem.value, list, _polylines.value, DxfEncodingMode.CP949_KOREAN, _exportConfig.value)
            postToast("전체 포맷 일괄 저장 완료: 다운로드/간단측량/${dxfFile.parentFile?.name}")
        }
    }

    fun shareExportedFileNow(formatType: String) {
        val list = _points.value
        if (list.isEmpty()) {
            postToast("공유할 측점 데이터가 없습니다.")
            return
        }
        val app = getApplication<Application>()
        val file = when (formatType) {
            "DXF" -> FileIoManager.exportDxf(app, _projectName.value, list, _polylines.value, DxfEncodingMode.CP949_KOREAN, _exportConfig.value)
            "ELT" -> FileIoManager.writeFileSafelyToDownloadAndInternal(app, "", "${_projectName.value}.elt", FileIoManager.buildEltBytes(_projectName.value, _coordinateSystem.value, list, _polylines.value), "text/plain")
            else -> FileIoManager.exportCustomTextOrCsv(app, _projectName.value, list, _exportConfig.value)
        }
        val ok = FileIoManager.shareFileExternally(app, file)
        if (!ok) postToast("파일 저장 완료 (${file.absolutePath})")
    }

    fun exportToUserSelectedUri(uri: Uri, exportType: String, dxfEncodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN) {
        val list = _points.value
        if (list.isEmpty() && exportType != "GSPROJ" && exportType != "TRF") {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = when (exportType) {
                "DXF" -> FileIoManager.buildDxfBytes(list, _polylines.value, dxfEncodingMode, _exportConfig.value)
                "CSV" -> FileIoManager.buildCustomTextOrCsvBytes(list, _exportConfig.value)
                "ELT" -> FileIoManager.buildEltBytes(_projectName.value, _coordinateSystem.value, list, _polylines.value)
                "GGA" -> FileIoManager.buildGgaFileBytes(list)
                "WGS" -> FileIoManager.buildWgsFileBytes(list)
                "KML" -> FileIoManager.buildKmlBytes(_projectName.value, list)
                "TRF" -> CalibrationSolver.buildTrfFileContent(_coordinateSystem.value, _calibrationParams.value, _calibrationPairs.value).toByteArray(Charsets.UTF_8)
                "GSPROJ" -> FileIoManager.buildProjectJsonBytes(buildCurrentProjectData())
                else -> ByteArray(0)
            }
            val ok = FileIoManager.writeBytesToUri(getApplication(), uri, bytes)
            postToast(if (ok) "지정한 경로에 $exportType 파일 저장을 완료했습니다." else "지정한 경로에 파일을 저장하지 못했습니다.")
        }
    }

    // =========================================================================
    // SITE CALIBRATION
    // =========================================================================

    fun refreshSavedTrfFilesList() {
        _savedTrfFiles.value = FileIoManager.listSavedTrfFiles(getApplication())
    }

    fun addCalibrationPair(pointName: String, knownN: Double, knownE: Double, knownZ: Double) {
        val g = _gnssStatus.value
        val rawPlane = CoordinateConverter.wgs84ToPlane(g.latitude, g.longitude, g.ellipsoidalHeight, _coordinateSystem.value, _useKnGeoid18.value, g.nmeaGeoidSeparation)
        val measN = if (g.latitude != 0.0) rawPlane.northing else _currentPlaneCoord.value.northing
        val measE = if (g.longitude != 0.0) rawPlane.easting else _currentPlaneCoord.value.easting
        val measZ = if (g.latitude != 0.0) (rawPlane.orthometricHeight - _antennaHeight.value) else _currentPlaneCoord.value.orthometricHeight

        val pair = CalibrationPair(
            pointName = pointName.ifBlank { "CP-${_calibrationPairs.value.size + 1}" },
            knownN = knownN,
            knownE = knownE,
            knownZ = knownZ,
            measuredN = measN,
            measuredE = measE,
            measuredZ = measZ,
            measuredLat = g.latitude,
            measuredLon = g.longitude,
            measuredEllipH = g.ellipsoidalHeight,
            enabled = true
        )
        recalculateCalibration(_calibrationPairs.value + pair, _calibrationParams.value.residualDistributeMode)
        postToast("캘리브레이션 제어점 추가됨 (RMSE: ${String.format(Locale.US, "%.4f", _calibrationParams.value.rmseHorizontal)}m)")
    }

    fun toggleCalibrationPairEnabled(pairId: String) {
        val updated = _calibrationPairs.value.map { if (it.id == pairId) it.copy(enabled = !it.enabled) else it }
        recalculateCalibration(updated, _calibrationParams.value.residualDistributeMode)
    }

    fun removeCalibrationPair(pairId: String) {
        recalculateCalibration(_calibrationPairs.value.filterNot { it.id == pairId }, _calibrationParams.value.residualDistributeMode)
    }

    fun setResidualDistributeMode(mode: ResidualDistributeMode) {
        recalculateCalibration(_calibrationPairs.value, mode)
        postToast("잔차분배 방식: ${mode.koreanLabel}")
    }

    private fun recalculateCalibration(pairs: List<CalibrationPair>, distributeMode: ResidualDistributeMode) {
        val (params, pairsWithRes) = CalibrationSolver.solveHelmert2DAndVertical(pairs, distributeMode)
        _calibrationPairs.value = pairsWithRes
        _calibrationParams.value = params
        updateGnssAndCoordinates(_gnssStatus.value)
    }

    fun saveCalibrationToTrfFile(trfName: String) {
        if (_calibrationPairs.value.isEmpty()) {
            postToast("저장할 캘리브레이션 기준점이 없습니다.")
            return
        }
        val file = FileIoManager.saveTrfFile(getApplication(), trfName.ifBlank { "${_projectName.value}_좌표계" }, _coordinateSystem.value, _calibrationParams.value, _calibrationPairs.value)
        refreshSavedTrfFilesList()
        postToast("TRF 좌표계 파일 저장 완료: ${file.name}")
    }

    fun loadCalibrationFromTrfFile(file: File) {
        try {
            val content = file.readText(Charsets.UTF_8)
            val pairs = FileIoManager.parseTrfFile(content, _coordinateSystem.value, _useKnGeoid18.value)
            if (pairs.isNotEmpty()) {
                recalculateCalibration(pairs, _calibrationParams.value.residualDistributeMode)
                postToast("TRF 현장좌표계 '${file.name}' 적용 완료 (${pairs.size}점)")
            } else {
                postToast("TRF 파일을 읽을 수 없습니다.")
            }
        } catch (e: Exception) {
            postToast("TRF 불러오기 실패: ${e.message}")
        }
    }

    fun clearCalibration() {
        _calibrationPairs.value = emptyList()
        _calibrationParams.value = SiteCalibrationParams(enabled = false)
        updateGnssAndCoordinates(_gnssStatus.value)
        postToast("현장 로컬 캘리브레이션이 초기화되었습니다.")
    }

    // =========================================================================
    // NTRIP & BLUETOOTH
    // =========================================================================

    fun updateNtripConfig(config: NtripConfig) {
        _ntripConfig.value = config
        saveCurrentStateToPrefsOnly()
    }

    fun fetchMountpoints() {
        val cfg = _ntripConfig.value
        viewModelScope.launch {
            postToast("마운트포인트 목록 조회 중...")
            val res = ntripClient.fetchMountpoints(cfg.host, cfg.port)
            res.onSuccess { list ->
                _availableMountpoints.value = list
                postToast("마운트포인트 ${list.size}개 수신 완료")
            }.onFailure { err ->
                postToast("마운트포인트 조회 실패: ${err.message}")
            }
        }
    }

    fun toggleNtripConnection() {
        if (_gnssStatus.value.ntripConnected) {
            userExplicitlyStoppedNtrip = true
            ntripClient.stopStreaming()
            _gnssStatus.value = _gnssStatus.value.copy(ntripConnected = false, ntripStatusMessage = "연결 해제됨", rtcmBytesPerSec = 0)
        } else {
            userExplicitlyStoppedNtrip = false
            val g = _gnssStatus.value
            val lat = if (g.latitude != 0.0) g.latitude else 36.642435
            val lon = if (g.longitude != 0.0) g.longitude else 127.489031
            val h = if (g.ellipsoidalHeight != 0.0) g.ellipsoidalHeight else 80.0
            ntripClient.startStreaming(_ntripConfig.value, lat, lon, h)
        }
    }

    fun getPairedBluetoothDevices(): List<BtDeviceItem> = btManager.getPairedDevices()
    fun startBluetoothScan() = btManager.startDiscoveryScan()

    fun connectBluetoothReceiver(item: BtDeviceItem) {
        userExplicitlyDisconnectedBt = false
        _lastBtDeviceAddress.value = item.address
        _lastBtDeviceName.value = item.name
        saveCurrentStateToPrefsOnly()
        if (_sourceMode.value != PositioningSourceMode.BLUETOOTH_GNSS) {
            _sourceMode.value = PositioningSourceMode.BLUETOOTH_GNSS
            simJob?.cancel()
            phoneGpsManager.stop()
        }
        btManager.connect(item.address, item.name)
        startAutoReconnectWatchdogIfNeeded()
    }

    fun reconnectLastBluetoothDeviceNow() {
        val addr = _lastBtDeviceAddress.value
        val name = _lastBtDeviceName.value.ifBlank { "최근 GPS 수신기" }
        if (addr.isBlank()) {
            postToast("저장된 최근 연결 GPS 기기가 없습니다. 통신 탭에서 먼저 기기를 선택하세요.")
            return
        }
        connectBluetoothReceiver(BtDeviceItem(name = name, address = addr, isHiTargetV90 = true, isBonded = true))
    }

    fun disconnectBluetoothReceiver() {
        userExplicitlyDisconnectedBt = true
        btManager.disconnect()
        nmeaParser.reset()
        _gnssStatus.value = _gnssStatus.value.copy(btConnected = false, fixQuality = FixQuality.NO_FIX, satellitesTracked = 0, satellitesUsed = 0, connectedDeviceName = "미연결")
    }

    private fun startAutoReconnectWatchdogIfNeeded() {
        if (autoReconnectWatchdogJob?.isActive == true) return
        autoReconnectWatchdogJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(4500L)
                if (!_autoReconnectEnabled.value) continue
                if (_sourceMode.value == PositioningSourceMode.BLUETOOTH_GNSS &&
                    !userExplicitlyDisconnectedBt &&
                    !_gnssStatus.value.btConnected &&
                    _lastBtDeviceAddress.value.isNotBlank()
                ) {
                    btManager.connect(_lastBtDeviceAddress.value, _lastBtDeviceName.value.ifBlank { "GPS 수신기" })
                }
                if (!userExplicitlyStoppedNtrip && !_gnssStatus.value.ntripConnected) {
                    val g = _gnssStatus.value
                    val lat = if (g.latitude != 0.0) g.latitude else 36.642435
                    val lon = if (g.longitude != 0.0) g.longitude else 127.489031
                    val h = if (g.ellipsoidalHeight != 0.0) g.ellipsoidalHeight else 80.0
                    ntripClient.startStreaming(_ntripConfig.value, lat, lon, h)
                }
            }
        }
    }

    // =========================================================================
    // EXCAVATOR DESIGN GRADE & UNDO/REDO & PHOTO BOARD
    // =========================================================================

    fun updateDesignGradeConfig(config: DesignGradeConfig) {
        _designGradeConfig.value = config
        refreshDesignGradeGuidance(_currentPlaneCoord.value)
        saveCurrentStateToPrefsOnly()
        postToast(if (config.mode == DesignGradeMode.OFF) "절·성토 안내가 꺼졌습니다." else "절·성토 안내 시작: ${config.mode.koreanLabel}")
    }

    fun zeroDesignElevationAtCurrentPosition() {
        val curZ = _currentPlaneCoord.value.orthometricHeight
        _designGradeConfig.value = _designGradeConfig.value.copy(mode = DesignGradeMode.FLAT_LEVEL, targetElevationZ = curZ)
        refreshDesignGradeGuidance(_currentPlaneCoord.value)
        saveCurrentStateToPrefsOnly()
        soundSensorManager.playPointSavedBeep()
        postToast(String.format(Locale.US, "[현위치 영점 완료] 계획고 EL = %.3fm 설정됨", curZ))
    }

    fun setDesignSlopeStartFromCurrentPosition() {
        val cur = _currentPlaneCoord.value
        _designGradeConfig.value = _designGradeConfig.value.copy(mode = DesignGradeMode.SLOPE_LINE, startPointName = "현위치시점(A)", startN = cur.northing, startE = cur.easting, startZ = cur.orthometricHeight)
        refreshDesignGradeGuidance(cur)
        saveCurrentStateToPrefsOnly()
    }

    fun setDesignSlopeEndFromCurrentPosition() {
        val cur = _currentPlaneCoord.value
        _designGradeConfig.value = _designGradeConfig.value.copy(mode = DesignGradeMode.SLOPE_LINE, endPointName = "현위치종점(B)", endN = cur.northing, endE = cur.easting, endZ = cur.orthometricHeight)
        refreshDesignGradeGuidance(cur)
        saveCurrentStateToPrefsOnly()
    }

    fun setDesignSlopeStartFromPoint(pt: SurveyPoint) {
        _designGradeConfig.value = _designGradeConfig.value.copy(mode = DesignGradeMode.SLOPE_LINE, startPointName = "#${pt.seqNumber}(${pt.name})", startN = pt.northing, startE = pt.easting, startZ = pt.elevation)
        refreshDesignGradeGuidance(_currentPlaneCoord.value)
        saveCurrentStateToPrefsOnly()
    }

    fun setDesignSlopeEndFromPoint(pt: SurveyPoint) {
        _designGradeConfig.value = _designGradeConfig.value.copy(mode = DesignGradeMode.SLOPE_LINE, endPointName = "#${pt.seqNumber}(${pt.name})", endN = pt.northing, endE = pt.easting, endZ = pt.elevation)
        refreshDesignGradeGuidance(_currentPlaneCoord.value)
        saveCurrentStateToPrefsOnly()
    }

    fun speakCurrentCutFillGuidance() {
        val g = _designGradeGuidance.value ?: return
        val cm = (abs(g.cutFillDeltaZ) * 100.0).toInt()
        val mm = ((abs(g.cutFillDeltaZ) * 1000.0).toInt()) % 10
        val speech = when {
            g.isOnGrade -> "정위치 계획고 일치"
            g.cutFillDeltaZ < 0.0 -> "깎기 절토 ${cm}센티 ${mm}밀리"
            else -> "채우기 성토 ${cm}센티 ${mm}밀리"
        }
        soundSensorManager.speak(speech)
        postToast("음성안내: $speech")
    }

    private fun pushUndoSnapshot(actionLabel: String) {
        val nowTime = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val bg = _backgroundShiftNE.value
        val snap = SurveyHistorySnapshot(
            actionLabel = actionLabel,
            timestamp = nowTime,
            points = _points.value.toList(),
            polylines = _polylines.value.toList(),
            cadEntities = _cadEntities.value.toList(),
            backgroundShiftN = bg.first,
            backgroundShiftE = bg.second
        )
        _undoHistory.value = (_undoHistory.value + snap).takeLast(30)
        _redoHistory.value = emptyList()
    }

    fun undoLastAction() {
        val stack = _undoHistory.value
        if (stack.isEmpty()) {
            postToast("되돌릴(Undo) 이전 작업 이력이 없습니다.")
            return
        }
        val targetSnap = stack.last()
        val nowTime = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val bg = _backgroundShiftNE.value
        val currentForRedo = SurveyHistorySnapshot(
            actionLabel = targetSnap.actionLabel,
            timestamp = nowTime,
            points = _points.value.toList(),
            polylines = _polylines.value.toList(),
            cadEntities = _cadEntities.value.toList(),
            backgroundShiftN = bg.first,
            backgroundShiftE = bg.second
        )
        _undoHistory.value = stack.dropLast(1)
        _redoHistory.value = (_redoHistory.value + currentForRedo).takeLast(30)
        _points.value = targetSnap.points
        _polylines.value = targetSnap.polylines
        _cadEntities.value = targetSnap.cadEntities
        _backgroundShiftNE.value = Pair(targetSnap.backgroundShiftN, targetSnap.backgroundShiftE)
        saveCurrentStateToPrefsAndAutoFile(actionLabel = "실행취소(${targetSnap.actionLabel})")
        soundSensorManager.playPointSavedBeep()
        postToast("↩ 실행취소 완료: ${targetSnap.actionLabel}")
    }

    fun redoLastAction() {
        val rStack = _redoHistory.value
        if (rStack.isEmpty()) {
            postToast("다시 실행(Redo)할 작업이 없습니다.")
            return
        }
        val targetSnap = rStack.last()
        val nowTime = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val bg = _backgroundShiftNE.value
        val currentForUndo = SurveyHistorySnapshot(
            actionLabel = targetSnap.actionLabel,
            timestamp = nowTime,
            points = _points.value.toList(),
            polylines = _polylines.value.toList(),
            cadEntities = _cadEntities.value.toList(),
            backgroundShiftN = bg.first,
            backgroundShiftE = bg.second
        )
        _redoHistory.value = rStack.dropLast(1)
        _undoHistory.value = (_undoHistory.value + currentForUndo).takeLast(30)
        _points.value = targetSnap.points
        _polylines.value = targetSnap.polylines
        _cadEntities.value = targetSnap.cadEntities
        _backgroundShiftNE.value = Pair(targetSnap.backgroundShiftN, targetSnap.backgroundShiftE)
        saveCurrentStateToPrefsAndAutoFile(actionLabel = "다시실행(${targetSnap.actionLabel})")
        soundSensorManager.playPointSavedBeep()
        postToast("↪ 다시실행 완료: ${targetSnap.actionLabel}")
    }

    fun restoreToHistorySnapshot(snapshot: SurveyHistorySnapshot) {
        pushUndoSnapshot("이력 복원(${snapshot.timestamp})")
        _points.value = snapshot.points
        _polylines.value = snapshot.polylines
        _cadEntities.value = snapshot.cadEntities
        _backgroundShiftNE.value = Pair(snapshot.backgroundShiftN, snapshot.backgroundShiftE)
        saveCurrentStateToPrefsAndAutoFile(actionLabel = "이력복원")
        postToast("${snapshot.timestamp} 시점으로 복원 완료")
    }

    fun refreshBlackboxBackups() {
        _blackboxBackups.value = FileIoManager.listBlackboxBackups(getApplication())
    }

    fun restoreFromBlackboxFile(file: File) {
        val loaded = FileIoManager.loadProjectFromFile(file)
        if (loaded != null) {
            pushUndoSnapshot("블랙박스 복원(${file.name})")
            applyLoadedProject(loaded)
            saveCurrentStateToPrefsAndAutoFile(actionLabel = "블랙박스복원")
            postToast("블랙박스 스냅샷 복구 완료: 측점 ${loaded.points.size}개")
        } else {
            postToast("블랙박스 파일을 읽을 수 없습니다.")
        }
    }

    fun setAutoOpenPhotoBoardAfterMeasure(enabled: Boolean) { _autoOpenPhotoBoardAfterMeasure.value = enabled }
    fun openPhotoBoardForPoint(point: SurveyPoint) { _activePhotoBoardPoint.value = point }
    fun openPhotoBoardForLatestPoint() {
        val last = _points.value.lastOrNull()
        if (last == null) postToast("먼저 측점을 1개 이상 측정하거나 추가하세요.") else _activePhotoBoardPoint.value = last
    }
    fun closePhotoBoardDialog() { _activePhotoBoardPoint.value = null }

    fun stampAndAttachBoardPhoto(pointId: String, rawBitmap: Bitmap?, customNote: String = "") {
        val targetPt = _points.value.find { it.id == pointId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val az = soundSensorManager.deviceAzimuthDeg.value
                val savedFile = FileIoManager.composeAndSaveBoardPhoto(getApplication(), rawBitmap, _projectName.value, targetPt, _coordinateSystem.value, az, customNote)
                pushUndoSnapshot("#${targetPt.seqNumber}(${targetPt.name}) 보드판 사진 첨부")
                val updatedPt = targetPt.copy(photoPath = savedFile.absolutePath, memo = if (customNote.isNotBlank()) customNote else targetPt.memo)
                _points.value = _points.value.map { if (it.id == pointId) updatedPt else it }
                _activePhotoBoardPoint.value = updatedPt
                saveCurrentStateToPrefsAndAutoFile(actionLabel = "보드판사진저장(#${targetPt.seqNumber})")
                soundSensorManager.playPointSavedBeep()
                postToast("현장 보드판 사진 저장 완료: ${savedFile.name}")
            } catch (e: Exception) {
                postToast("보드판 사진 합성 실패: ${e.message}")
            }
        }
    }

    fun createHighResCameraUri(): Uri? = FileIoManager.createHighResCameraCaptureUri(getApplication())

    fun stampBoardPhotoFromHighResCameraUri(pointId: String, uri: Uri, customNote: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = FileIoManager.decodeHighResBitmapFromUri(getApplication(), uri, true)
            if (bmp != null) stampAndAttachBoardPhoto(pointId, bmp, customNote) else postToast("카메라 원본 사진을 읽을 수 없습니다.")
        }
    }

    fun stampBoardPhotoFromGalleryUri(pointId: String, uri: Uri, customNote: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = FileIoManager.decodeHighResBitmapFromUri(getApplication(), uri, false)
            if (bmp != null) stampAndAttachBoardPhoto(pointId, bmp, customNote) else postToast("선택한 사진 파일을 읽을 수 없습니다.")
        }
    }

    fun sharePointBoardPhoto(point: SurveyPoint) {
        if (point.photoPath.isBlank()) {
            postToast("저장된 보드판 사진이 없습니다.")
            return
        }
        val file = File(point.photoPath)
        if (file.exists()) FileIoManager.shareFileExternally(getApplication(), file, "image/jpeg")
    }

    fun exportAndShareFullZipBundle(shareImmediately: Boolean) {
        val list = _points.value
        if (list.isEmpty()) {
            postToast("압축할 측점 데이터가 없습니다.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val zipFile = FileIoManager.exportFullProjectZipBundle(app, _projectName.value, _coordinateSystem.value, list, _polylines.value, DxfEncodingMode.CP949_KOREAN, _exportConfig.value)
                postToast("통합 ZIP 패키지 생성 완료 (${zipFile.name})")
                if (shareImmediately) FileIoManager.shareFileExternally(app, zipFile, "application/zip")
            } catch (e: Exception) {
                postToast("통합 ZIP 생성 실패: ${e.message}")
            }
        }
    }

    private fun saveCurrentStateToPrefsAndAutoFile(actionLabel: String = "자동저장") {
        saveCurrentStateToPrefsOnly()
        val nowStamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        _blackboxStatusText.value = "블랙박스 보호중 ($nowStamp · ${_points.value.size}점/${_polylines.value.size}선)"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val proj = buildCurrentProjectData()
                FileIoManager.saveProjectFile(getApplication(), proj)
                FileIoManager.saveBlackboxSnapshot(getApplication(), proj, actionLabel)
                refreshSavedProjectsList()
                refreshBlackboxBackups()
            } catch (_: Exception) {}
        }
    }

    private fun saveCurrentStateToPrefsOnly() {
        val preset = _lastMeasurePreset.value
        val disp = _mapDisplayConfig.value
        prefs.edit().apply {
            putString("project_name", _projectName.value)
            putString("coord_sys", _coordinateSystem.value.name)
            putBoolean("use_geoid18", _useKnGeoid18.value)
            putString("antenna_h", _antennaHeight.value.toString())
            putString("favorite_antenna_h", _favoriteAntennaHeight.value.toString())
            putString("last_survey_date_ymd", _lastSurveyDateYmd.value)
            putString("ntrip_host", _ntripConfig.value.host)
            putInt("ntrip_port", _ntripConfig.value.port)
            putString("ntrip_user", _ntripConfig.value.username)
            putString("ntrip_pass", _ntripConfig.value.password)
            putString("ntrip_mount", _ntripConfig.value.mountpoint)
            putString("last_measure_code", preset.code)
            putString("quick_codes_mru_order", _quickCodes.value.joinToString("|") { it.code })
            putInt("last_measure_epochs", preset.epochs)
            putString("last_measure_memo", preset.memo)
            putString("last_measure_line_mode", preset.autoLineMode.name)
            putBoolean("disp_white_bg", disp.whiteBackground)
            putBoolean("disp_show_name", disp.showPointName)
            putBoolean("disp_show_elev", disp.showElevation)
            putBoolean("disp_show_code", disp.showCode)
            putInt("disp_decimals", disp.decimalPlaces)
            putBoolean("disp_top_dms", disp.showTopLatLonDms)
            putBoolean("disp_cadastral", disp.cadastralOverlayEnabled)
            putBoolean("disp_cad_text", disp.showCadText)
            putBoolean("keep_screen_on", _keepScreenOnEnabled.value)
            putBoolean("auto_reconnect", _autoReconnectEnabled.value)
            putString("last_bt_addr", _lastBtDeviceAddress.value)
            putString("last_bt_name", _lastBtDeviceName.value)
            putBoolean("imu_tilt_enabled", _imuTiltCompensationEnabled.value)
            putBoolean("imu_bubble_visible_v2", _imuBubbleVisibleOnMap.value)
            putString("imu_max_tilt_deg", _imuMaxTiltLimitDeg.value.toString())
            putBoolean("imu_block_excess", _imuBlockRecordOnExcessTilt.value)
            putString("vworld_api_key", _vworldApiKey.value)
            putInt("vworld_radius_m", _vworldSearchRadiusMeters.value)
            putString("bg_shift_n", _backgroundShiftNE.value.first.toString())
            putString("bg_shift_e", _backgroundShiftNE.value.second.toString())
            putBoolean("shift_include_bg", _shiftIncludeBackgroundTiles.value)
            apply()
        }
    }

    private fun loadSavedSettingsAndLastProject() {
        _projectName.value = prefs.getString("project_name", "간단측량_현장01") ?: "간단측량_현장01"
        val sysName = prefs.getString("coord_sys", KoreanCoordinateSystem.CENTRAL_GRS80.name)
        _coordinateSystem.value = try {
            KoreanCoordinateSystem.valueOf(sysName ?: KoreanCoordinateSystem.CENTRAL_GRS80.name)
        } catch (_: Exception) {
            KoreanCoordinateSystem.CENTRAL_GRS80
        }
        _useKnGeoid18.value = prefs.getBoolean("use_geoid18", true)
        _antennaHeight.value = prefs.getString("antenna_h", "1.800")?.toDoubleOrNull() ?: 1.800
        _favoriteAntennaHeight.value = prefs.getString("favorite_antenna_h", "1.800")?.toDoubleOrNull() ?: 1.800
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val savedDateYmd = prefs.getString("last_survey_date_ymd", "") ?: ""
        _lastSurveyDateYmd.value = savedDateYmd.ifBlank { todayYmd }

        _ntripConfig.value = NtripConfig(
            host = prefs.getString("ntrip_host", "RTS2.ngii.go.kr") ?: "RTS2.ngii.go.kr",
            port = prefs.getInt("ntrip_port", 2101),
            mountpoint = prefs.getString("ntrip_mount", "VRS-RTCM32") ?: "VRS-RTCM32",
            username = prefs.getString("ntrip_user", "qwsaweds") ?: "qwsaweds",
            password = prefs.getString("ntrip_pass", "ngii") ?: "ngii"
        )

        val savedLineModeName = prefs.getString("last_measure_line_mode", AutoLineMode.NONE.name)
        val savedLineMode = try { AutoLineMode.valueOf(savedLineModeName ?: AutoLineMode.NONE.name) } catch (_: Exception) { AutoLineMode.NONE }
        val savedLastCode = prefs.getString("last_measure_code", "지반") ?: "지반"
        promoteCodeToFront(savedLastCode)

        _lastMeasurePreset.value = LastMeasurePreset(
            code = savedLastCode,
            epochs = prefs.getInt("last_measure_epochs", 1).coerceIn(1, 30),
            memo = prefs.getString("last_measure_memo", "") ?: "",
            autoLineMode = savedLineMode
        )

        _keepScreenOnEnabled.value = prefs.getBoolean("keep_screen_on", true)
        _autoReconnectEnabled.value = prefs.getBoolean("auto_reconnect", true)
        _lastBtDeviceAddress.value = prefs.getString("last_bt_addr", "") ?: ""
        _lastBtDeviceName.value = prefs.getString("last_bt_name", "") ?: ""
        _imuTiltCompensationEnabled.value = prefs.getBoolean("imu_tilt_enabled", false)
        _imuBubbleVisibleOnMap.value = prefs.getBoolean("imu_bubble_visible_v2", false)
        _imuMaxTiltLimitDeg.value = prefs.getString("imu_max_tilt_deg", "15.0")?.toDoubleOrNull() ?: 15.0
        _imuBlockRecordOnExcessTilt.value = prefs.getBoolean("imu_block_excess", false)
        _vworldApiKey.value = (prefs.getString("vworld_api_key", "38299C15-0A2C-4367-8DD6-BF3936E0F86D") ?: "").ifBlank { "38299C15-0A2C-4367-8DD6-BF3936E0F86D" }
        _vworldSearchRadiusMeters.value = prefs.getInt("vworld_radius_m", 300).coerceIn(100, 2000)
        val savedBgN = prefs.getString("bg_shift_n", "0.0")?.toDoubleOrNull() ?: 0.0
        val savedBgE = prefs.getString("bg_shift_e", "0.0")?.toDoubleOrNull() ?: 0.0
        _backgroundShiftNE.value = if (hypot(savedBgN, savedBgE) < 500.0) Pair(savedBgN, savedBgE) else Pair(0.0, 0.0)
        _shiftIncludeBackgroundTiles.value = prefs.getBoolean("shift_include_bg", true)

        if (_autoReconnectEnabled.value) startAutoReconnectWatchdogIfNeeded()

        _mapDisplayConfig.value = MapDisplayConfig(
            whiteBackground = prefs.getBoolean("disp_white_bg", false),
            showPointName = prefs.getBoolean("disp_show_name", true),
            showElevation = prefs.getBoolean("disp_show_elev", true),
            showCode = prefs.getBoolean("disp_show_code", true),
            decimalPlaces = prefs.getInt("disp_decimals", 3).coerceIn(3, 4),
            showTopLatLonDms = prefs.getBoolean("disp_top_dms", false),
            cadastralOverlayEnabled = prefs.getBoolean("disp_cadastral", false),
            showCadText = prefs.getBoolean("disp_cad_text", false)
        )

        val existingFiles = FileIoManager.listSavedProjects(getApplication())
        val matchingFile = existingFiles.find { it.nameWithoutExtension == _projectName.value } ?: existingFiles.firstOrNull()
        val loadedFromDisk = matchingFile?.let { FileIoManager.loadProjectFromFile(it) }
        val loadedFromBlackbox = FileIoManager.loadLatestBlackboxSnapshot(getApplication())
        val bestProject = when {
            loadedFromBlackbox != null && loadedFromDisk != null -> {
                if (loadedFromBlackbox.points.size >= loadedFromDisk.points.size) loadedFromBlackbox else loadedFromDisk
            }
            loadedFromBlackbox != null -> loadedFromBlackbox
            else -> loadedFromDisk
        }
        if (bestProject != null) {
            _points.value = bestProject.points
            _polylines.value = bestProject.figures
            val nowStamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            _blackboxStatusText.value = "블랙박스 자동복구됨 ($nowStamp · ${bestProject.points.size}점/${bestProject.figures.size}선)"
        }
        refreshBlackboxBackups()
    }

    private fun defaultQuickCodes(): List<QuickCodePreset> = listOf(
        QuickCodePreset("지반", "지반고(현황)", 0xFF38BDF8, false),
        QuickCodePreset("경계", "필지/부지경계", 0xFFF43F5E, true),
        QuickCodePreset("도로", "도로가장자리", 0xFFFACC15, true),
        QuickCodePreset("연석", "보도연석", 0xFFA855F7, true),
        QuickCodePreset("건물", "건물모서리", 0xFF22C55E, true),
        QuickCodePreset("맨홀", "상하수도맨홀", 0xFFFB923C, false),
        QuickCodePreset("수목", "조경수목", 0xFF10B981, false),
        QuickCodePreset("옹벽", "옹벽/법면상단", 0xFFEC4899, true),
        QuickCodePreset("기준점", "도근/수준기준점", 0xFFEF4444, false)
    )

    override fun onCleared() {
        super.onCleared()
        autoTrackJob?.cancel()
        btManager.disconnect()
        phoneGpsManager.stop()
        ntripClient.stopStreaming()
        soundSensorManager.release()
    }
}
