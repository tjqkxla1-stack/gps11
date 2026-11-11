package kr.koseco.gandansurvey.v90.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kr.koseco.gandansurvey.v90.coord.CoordinateConverter
import kr.koseco.gandansurvey.v90.model.*
import kr.koseco.gandansurvey.v90.util.TileMapManager
import java.io.File
import java.util.Locale
import kotlin.math.*

@Composable
fun GandanSurveyApp(viewModel: GandanSurveyViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val projectName by viewModel.projectName.collectAsState()
    val savedProjects by viewModel.savedProjects.collectAsState()
    val gnssStatus by viewModel.gnssStatus.collectAsState()
    val planeCoord by viewModel.currentPlaneCoord.collectAsState()
    val coordSystem by viewModel.coordinateSystem.collectAsState()
    val antennaHeight by viewModel.antennaHeight.collectAsState()
    val nextDayWarning by viewModel.nextDayAntennaWarningActive.collectAsState()
    val undoHistory by viewModel.undoHistory.collectAsState()
    val redoHistory by viewModel.redoHistory.collectAsState()
    val points by viewModel.points.collectAsState()
    val pendingSnappedTarget by viewModel.pendingSnappedTarget.collectAsState()
    val lastSnappedTarget by viewModel.lastSnappedTarget.collectAsState()
    val activePhotoBoardPoint by viewModel.activePhotoBoardPoint.collectAsState()

    var showProjectDialog by remember { mutableStateOf(false) }
    var showImuTiltDialog by remember { mutableStateOf(false) }
    var showDrawingShiftDialog by remember { mutableStateOf(false) }
    var showThreeDViewerDialog by remember { mutableStateOf(false) }
    var showAddressSearchDialog by remember { mutableStateOf(false) }
    var showLayerManagerDialog by remember { mutableStateOf(false) }
    var showDisplayConfigDialog by remember { mutableStateOf(false) }
    var showVWorldDialog by remember { mutableStateOf(false) }
    var showDesignGradeDialog by remember { mutableStateOf(false) }
    var showBlackboxDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.triggerNextDayAntennaWarningIfNeeded()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 프로젝트 선택 버튼
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .clickable {
                                viewModel.refreshSavedProjectsList()
                                showProjectDialog = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "📁 $projectName",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(" ▼", color = Color.LightGray, fontSize = 10.sp)
                    }

                    // GNSS Fix 상태 배지 + 폴대 높이 + Undo/Redo
                    val fixColor = Color(gnssStatus.fixQuality.colorHex)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(fixColor.copy(alpha = 0.2f))
                                .border(1.dp, fixColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${gnssStatus.fixQuality.shortBadge} (${gnssStatus.satellitesUsed}/${gnssStatus.satellitesTracked})",
                                color = fixColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = String.format(Locale.US, "H:%.3fm", gnssStatus.hrms),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        val favAnt by viewModel.favoriteAntennaHeight.collectAsState()
                        val isAntMismatch = abs(antennaHeight - favAnt) > 0.0005
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isAntMismatch) Color(0xFFB91C1C) else Color(0xFF334155))
                                .clickable { showImuTiltDialog = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isAntMismatch) String.format(Locale.US, "⚠️폴:%.2fm", antennaHeight)
                                else String.format(Locale.US, "폴:%.2fm", antennaHeight),
                                color = Color(0xFFFDE047),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { viewModel.undoLastAction() },
                            enabled = undoHistory.isNotEmpty(),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Undo,
                                contentDescription = "실행취소",
                                tint = if (undoHistory.isNotEmpty()) Color.White else Color.DarkGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.redoLastAction() },
                            enabled = redoHistory.isNotEmpty(),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Redo,
                                contentDescription = "다시실행",
                                tint = if (redoHistory.isNotEmpty()) Color.White else Color.DarkGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // 두 번째 줄: 실시간 N / E / Z 요약 + VRS 상태
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "N:%.3f  E:%.3f  EL:%.3fm",
                            planeCoord.northing,
                            planeCoord.easting,
                            planeCoord.orthometricHeight
                        ),
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "VRS:${if (gnssStatus.ntripConnected) "수신중" else "대기"} | 🔋${gnssStatus.receiverBatteryPercent}%",
                        color = if (gnssStatus.ntripConnected) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                tonalElevation = 4.dp,
                modifier = Modifier.height(60.dp)
            ) {
                val tabs = listOf(
                    "현황측량" to Icons.Default.Map,
                    "좌표측설" to Icons.Default.MyLocation,
                    "측점/출력" to Icons.Default.List,
                    "통신/VRS" to Icons.Default.Bluetooth,
                    "좌표계" to Icons.Default.Public,
                    "도면/도구함" to Icons.Default.Build
                )
                tabs.forEachIndexed { idx, (label, icon) ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) },
                        label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF38BDF8),
                            selectedTextColor = Color(0xFF38BDF8),
                            unselectedIconColor = Color(0xFF94A3B8),
                            unselectedTextColor = Color(0xFF94A3B8),
                            indicatorColor = Color(0xFF1E293B)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0B0F19))
        ) {
            when (selectedTab) {
                0 -> MapSurveyScreen(
                    viewModel = viewModel,
                    onOpenImuTiltDialog = { showImuTiltDialog = true },
                    onOpenDrawingShiftDialog = { showDrawingShiftDialog = true },
                    onOpenVWorldDialog = { showVWorldDialog = true },
                    onOpenLayerDialog = { showLayerManagerDialog = true },
                    onOpenDisplayConfigDialog = { showDisplayConfigDialog = true },
                    onOpenAddressSearchDialog = { showAddressSearchDialog = true }
                )
                1 -> StakeoutScreen(
                    viewModel = viewModel,
                    onJumpToMap = { selectedTab = 0 }
                )
                2 -> PointsAndExportScreen(
                    viewModel = viewModel,
                    onJumpToMap = { selectedTab = 0 }
                )
                3 -> GnssAndNtripScreen(viewModel = viewModel)
                4 -> CoordAndCalibrationScreen(viewModel = viewModel)
                5 -> ToolboxAndDrawingScreen(
                    viewModel = viewModel,
                    onReturnToMap = { selectedTab = 0 },
                    onOpenVWorldDialog = { showVWorldDialog = true },
                    onOpenDrawingShiftDialog = { showDrawingShiftDialog = true },
                    onOpenLayerDialog = { showLayerManagerDialog = true },
                    onOpenThreeDViewerDialog = { showThreeDViewerDialog = true },
                    onOpenDesignGradeDialog = { showDesignGradeDialog = true },
                    onOpenBlackboxDialog = {
                        viewModel.refreshBlackboxBackups()
                        showBlackboxDialog = true
                    },
                    onOpenImuTiltDialog = { showImuTiltDialog = true }
                )
            }
        }
    }

    if (nextDayWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.keepCurrentAntennaHeightForTodayAndDismissWarning() },
            title = { Text("⚠️ 폴대(안테나) 높이 확인", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    String.format(
                        Locale.US,
                        "현재 설정된 폴대 높이(%.3fm)가 즐겨찾기 고정 폴대 높이(%.3fm)와 다릅니다. 고정 폴대 높이로 변경하시겠습니까?",
                        antennaHeight,
                        viewModel.favoriteAntennaHeight.collectAsState().value
                    )
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.applyFavoriteAntennaHeightAndDismissWarning() }) {
                    Text("고정 높이로 복원")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.keepCurrentAntennaHeightForTodayAndDismissWarning() }) {
                    Text("현재 높이 유지")
                }
            }
        )
    }

    if (showProjectDialog) {
        ProjectSelectDialog(
            currentProjectName = projectName,
            savedProjects = savedProjects,
            onCreateNew = { name, clearPts ->
                viewModel.createNewProject(name, clearPts)
                showProjectDialog = false
            },
            onLoadProject = { file ->
                viewModel.loadProjectFile(file)
                showProjectDialog = false
            },
            onSaveCurrent = {
                viewModel.saveCurrentProjectExplicitly()
                showProjectDialog = false
            },
            onDismiss = { showProjectDialog = false }
        )
    }

    if (showImuTiltDialog) {
        ImuTiltSettingDialog(
            viewModel = viewModel,
            onDismiss = { showImuTiltDialog = false }
        )
    }

    if (showDrawingShiftDialog) {
        DrawingShiftDialog(
            snappedTarget = pendingSnappedTarget ?: lastSnappedTarget,
            currentPlane = planeCoord,
            surveyPoints = points,
            onStartInteractiveMapShift = {
                showDrawingShiftDialog = false
                selectedTab = 0
                viewModel.startInteractiveDrawingShiftFromSnap(pendingSnappedTarget ?: lastSnappedTarget)
            },
            onApplyShift = { dn, de, dz ->
                showDrawingShiftDialog = false
                viewModel.shiftExternalCadEntities(dn, de, dz)
            },
            onDismiss = { showDrawingShiftDialog = false }
        )
    }

    if (showVWorldDialog) {
        VWorldCadastralDialog(
            viewModel = viewModel,
            onDismiss = { showVWorldDialog = false }
        )
    }

    if (showLayerManagerDialog) {
        LayerManagerDialog(
            viewModel = viewModel,
            onDismiss = { showLayerManagerDialog = false }
        )
    }

    if (showDisplayConfigDialog) {
        DisplayConfigDialog(
            viewModel = viewModel,
            onDismiss = { showDisplayConfigDialog = false }
        )
    }

    if (showAddressSearchDialog) {
        AddressSearchDialog(
            viewModel = viewModel,
            onDismiss = { showAddressSearchDialog = false }
        )
    }

    if (showDesignGradeDialog) {
        DesignGradeDialog(
            viewModel = viewModel,
            onDismiss = { showDesignGradeDialog = false }
        )
    }

    if (showThreeDViewerDialog) {
        ThreeDCadViewerDialog(
            viewModel = viewModel,
            onDismiss = { showThreeDViewerDialog = false }
        )
    }

    if (showBlackboxDialog) {
        BlackboxRecoveryDialog(
            viewModel = viewModel,
            onDismiss = { showBlackboxDialog = false }
        )
    }

    activePhotoBoardPoint?.let { pt ->
        PhotoBoardDialog(
            point = pt,
            viewModel = viewModel,
            onDismiss = { viewModel.closePhotoBoardDialog() }
        )
    }
}

// ============================================================================
// 1. 현황측량 & 인터랙티브 도면 캔버스 화면 (MapSurveyScreen)
// ============================================================================
@Composable
fun MapSurveyScreen(
    viewModel: GandanSurveyViewModel,
    onOpenImuTiltDialog: () -> Unit,
    onOpenDrawingShiftDialog: () -> Unit,
    onOpenVWorldDialog: () -> Unit,
    onOpenLayerDialog: () -> Unit,
    onOpenDisplayConfigDialog: () -> Unit,
    onOpenAddressSearchDialog: () -> Unit
) {
    val points by viewModel.points.collectAsState()
    val polylines by viewModel.polylines.collectAsState()
    val cadEntities by viewModel.cadEntities.collectAsState()
    val hiddenLayers by viewModel.hiddenLayers.collectAsState()
    val planeCoord by viewModel.currentPlaneCoord.collectAsState()
    val gnssStatus by viewModel.gnssStatus.collectAsState()
    val coordSystem by viewModel.coordinateSystem.collectAsState()
    val cadSnapEnabled by viewModel.cadSnapEnabled.collectAsState()
    val pendingSnappedTarget by viewModel.pendingSnappedTarget.collectAsState()
    val canvasToolMode by viewModel.canvasToolMode.collectAsState()
    val mapDisplayConfig by viewModel.mapDisplayConfig.collectAsState()
    val backgroundMapType by viewModel.backgroundMapType.collectAsState()
    val backgroundMapAlpha by viewModel.backgroundMapAlpha.collectAsState()
    val tileInvalidateTick by viewModel.tileInvalidateTick.collectAsState()
    val mapCenterOverrideNE by viewModel.mapCenterOverrideNE.collectAsState()
    val stakeoutTarget by viewModel.stakeoutTarget.collectAsState()
    val stakeoutGuidance by viewModel.stakeoutGuidance.collectAsState()
    val stakeoutSoundEnabled by viewModel.stakeoutSoundEnabled.collectAsState()
    val designGradeGuidance by viewModel.designGradeGuidance.collectAsState()
    val undoHistory by viewModel.undoHistory.collectAsState()
    val quickCodes by viewModel.quickCodes.collectAsState()
    val lastMeasurePreset by viewModel.lastMeasurePreset.collectAsState()
    val isAveraging by viewModel.isAveraging.collectAsState()
    val averagingProgress by viewModel.averagingProgress.collectAsState()
    val touchLockEnabled by viewModel.touchLockEnabled.collectAsState()
    val antennaHeight by viewModel.antennaHeight.collectAsState()
    val requiredFixMode by viewModel.requiredFixMode.collectAsState()
    var isRightBarExpanded by remember { mutableStateOf(false) }

    val autoOpenPhotoBoard by viewModel.autoOpenPhotoBoardAfterMeasure.collectAsState()
    val cogoRulerSummary by viewModel.cogoRulerSummary.collectAsState()
    val drawingShiftSourceSnap by viewModel.drawingShiftSourceSnap.collectAsState()
    val backgroundShiftNE by viewModel.backgroundShiftNE.collectAsState()
    val shiftIncludeBackgroundTiles by viewModel.shiftIncludeBackgroundTiles.collectAsState()

    // 수평기포관 표시 상태 (기본값 false: 도구함에서 켤 때만 표시!)
    val imuBubbleVisible by viewModel.imuBubbleVisibleOnMap.collectAsState()
    val imuCompensationEnabled by viewModel.imuTiltCompensationEnabled.collectAsState()
    val imuMaxTiltLimit by viewModel.imuMaxTiltLimitDeg.collectAsState()
    val imuBlockOnExcess by viewModel.imuBlockRecordOnExcessTilt.collectAsState()
    val imuWarningActive by viewModel.imuTiltWarningAlertActive.collectAsState()

    // 수평기포관 또는 IMU 보정이 켜져 있을 때만 센서 StateFlow 구독 (배터리/발열 절약)
    val isImuActiveOnScreen = imuBubbleVisible || imuCompensationEnabled
    val sensorTiltDeg = if (isImuActiveOnScreen) viewModel.soundSensorManager.deviceTiltAngleDeg.collectAsState().value else 0f
    val sensorPitchDeg = if (isImuActiveOnScreen) viewModel.soundSensorManager.deviceTiltPitchDeg.collectAsState().value else 0f
    val sensorRollDeg = if (isImuActiveOnScreen) viewModel.soundSensorManager.deviceTiltRollDeg.collectAsState().value else 0f
    val liveTiltAngle = if (gnssStatus.imuTiltActive) gnssStatus.imuTiltAngleDeg else sensorTiltDeg.toDouble()

    var panOffsetPx by remember { mutableStateOf(Offset.Zero) }
    var scaleMetersPerPixel by remember { mutableFloatStateOf(0.08f) }
    var labelScaleMultiplier by remember { mutableFloatStateOf(1.0f) }
    var canvasSizePx by remember { mutableStateOf(IntSize(1080, 1600)) }
    var showMeasureInputDialog by remember { mutableStateOf(false) }

    val refN = remember(planeCoord.northing, points.size, cadEntities.size) {
        when {
            planeCoord.northing != 0.0 -> planeCoord.northing
            points.isNotEmpty() -> points.last().northing
            cadEntities.isNotEmpty() -> cadEntities.first().verticesNE.firstOrNull()?.first ?: 500000.0
            else -> 500000.0
        }
    }
    val refE = remember(planeCoord.easting, points.size, cadEntities.size) {
        when {
            planeCoord.easting != 0.0 -> planeCoord.easting
            points.isNotEmpty() -> points.last().easting
            cadEntities.isNotEmpty() -> cadEntities.first().verticesNE.firstOrNull()?.second ?: 200000.0
            else -> 200000.0
        }
    }

    fun triggerZoomExtend(silent: Boolean = false) {
        val allN = mutableListOf<Double>()
        val allE = mutableListOf<Double>()
        points.forEach {
            allN.add(it.northing)
            allE.add(it.easting)
        }
        cadEntities.forEach { ent ->
            if (ent.layerName !in hiddenLayers) {
                ent.verticesNE.forEach { (n, e) ->
                    allN.add(n)
                    allE.add(e)
                }
            }
        }
        if (allN.isEmpty()) {
            panOffsetPx = Offset.Zero
            scaleMetersPerPixel = 0.08f
            return
        }
        val minN = allN.minOrNull() ?: refN
        val maxN = allN.maxOrNull() ?: refN
        val minE = allE.minOrNull() ?: refE
        val maxE = allE.maxOrNull() ?: refE
        val midN = (minN + maxN) * 0.5
        val midE = (minE + maxE) * 0.5
        val spanN = (maxN - minN).coerceAtLeast(8.0)
        val spanE = (maxE - minE).coerceAtLeast(8.0)
        val w = canvasSizePx.width.coerceAtLeast(400).toFloat()
        val h = canvasSizePx.height.coerceAtLeast(400).toFloat()
        val mpp = max(spanE / (w * 0.72), spanN / (h * 0.72)).toFloat().coerceIn(0.002f, 20.0f)
        scaleMetersPerPixel = mpp
        panOffsetPx = Offset(
            x = ((refE - midE) / mpp).toFloat(),
            y = ((midN - refN) / mpp).toFloat()
        )
        if (!silent) {
            viewModel.postToast("전체 도면 영역에 화면을 맞췄습니다.")
        }
    }

    // 현재 보고 있는 화면 중심 좌표를 ViewModel과 항상 동기화 (V-World 지적도 로드 시 화면 중심 기준 지원)
    val currentViewCenterN = refN + panOffsetPx.y * scaleMetersPerPixel
    val currentViewCenterE = refE - panOffsetPx.x * scaleMetersPerPixel
    LaunchedEffect(currentViewCenterN, currentViewCenterE) {
        if (currentViewCenterN != 0.0 || currentViewCenterE != 0.0) {
            viewModel.updateCurrentViewportCenterNE(currentViewCenterN, currentViewCenterE)
        }
    }

    var prevExternalCadCount by remember { mutableIntStateOf(0) }
    val currentExternalCadCount = remember(cadEntities) { cadEntities.count { it.sourceType != "VWORLD" } }
    LaunchedEffect(currentExternalCadCount) {
        if (currentExternalCadCount > 0 && prevExternalCadCount == 0 && points.isEmpty()) {
            triggerZoomExtend(silent = true)
        }
        prevExternalCadCount = currentExternalCadCount
    }

    LaunchedEffect(mapCenterOverrideNE) {
        mapCenterOverrideNE?.let { (targetN, targetE) ->
            panOffsetPx = Offset(
                x = ((refE - targetE) / scaleMetersPerPixel).toFloat(),
                y = ((targetN - refN) / scaleMetersPerPixel).toFloat()
            )
            viewModel.clearMapCenterOverride()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 메인 CAD + 배경지도 캔버스
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(if (mapDisplayConfig.whiteBackground) Color(0xFFF8FAFC) else Color(0xFF0B0F19))
                .pointerInput(touchLockEnabled) {
                    if (!touchLockEnabled) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val oldScale = scaleMetersPerPixel
                            val newScale = (oldScale / zoom).coerceIn(0.001f, 50.0f)
                            val actualZoom = oldScale / newScale
                            val halfW = size.width / 2f
                            val halfH = size.height / 2f
                            val oldCenterX = halfW + panOffsetPx.x
                            val oldCenterY = halfH + panOffsetPx.y
                            val newCenterX = centroid.x + pan.x - (centroid.x - oldCenterX) * actualZoom
                            val newCenterY = centroid.y + pan.y - (centroid.y - oldCenterY) * actualZoom
                            scaleMetersPerPixel = newScale
                            panOffsetPx = Offset(newCenterX - halfW, newCenterY - halfH)
                        }
                    }
                }
                .pointerInput(touchLockEnabled, canvasToolMode, cadSnapEnabled, scaleMetersPerPixel, panOffsetPx, refN, refE) {
                    if (!touchLockEnabled) {
                        detectTapGestures(
                            onTap = { tapPos ->
                                val cx = size.width / 2f + panOffsetPx.x
                                val cy = size.height / 2f + panOffsetPx.y
                                val tapE = refE + (tapPos.x - cx) * scaleMetersPerPixel
                                val tapN = refN - (tapPos.y - cy) * scaleMetersPerPixel
                                val tolMeters = (32f * scaleMetersPerPixel).toDouble().coerceAtLeast(0.30)

                                if (canvasToolMode != CanvasToolMode.NORMAL) {
                                    viewModel.handleCanvasTapInToolMode(tapN, tapE, tolMeters)
                                } else {
                                    val hitPt = points.minByOrNull { hypot(it.northing - tapN, it.easting - tapE) }
                                    if (hitPt != null && hypot(hitPt.northing - tapN, hitPt.easting - tapE) <= tolMeters) {
                                        viewModel.setStakeoutTarget(hitPt)
                                    }
                                }
                            },
                            onLongPress = { tapPos ->
                                val canAttemptSnap = (canvasToolMode == CanvasToolMode.NORMAL || canvasToolMode == CanvasToolMode.DRAWING_SNAP_SHIFT) &&
                                    cadSnapEnabled && (cadEntities.isNotEmpty() || points.isNotEmpty())
                                if (canAttemptSnap) {
                                    val cx = size.width / 2f + panOffsetPx.x
                                    val cy = size.height / 2f + panOffsetPx.y
                                    val tapE = refE + (tapPos.x - cx) * scaleMetersPerPixel
                                    val tapN = refN - (tapPos.y - cy) * scaleMetersPerPixel
                                    val tolMeters = (42f * scaleMetersPerPixel).toDouble().coerceAtLeast(0.50)
                                    val snap = viewModel.previewCadSnapFromCanvas(tapN, tapE, tolMeters)
                                    if (snap != null) {
                                        viewModel.lockPendingCadSnap(snap)
                                    }
                                }
                            }
                        )
                    }
                }
        ) {
            canvasSizePx = IntSize(size.width.toInt(), size.height.toInt())
            val _tick = tileInvalidateTick
            val centerX = size.width / 2f + panOffsetPx.x
            val centerY = size.height / 2f + panOffsetPx.y

            fun worldToScreen(n: Double, e: Double): Offset {
                val sx = centerX + ((e - refE) / scaleMetersPerPixel).toFloat()
                val sy = centerY - ((n - refN) / scaleMetersPerPixel).toFloat()
                return Offset(sx, sy)
            }

            val dynamicLineStrokePx = (1.8f / scaleMetersPerPixel.coerceIn(0.03f, 1.5f) * 0.08f).coerceIn(1.5f, 4.5f)
            val dynamicNodeRadiusPx = (6.5f * labelScaleMultiplier).coerceIn(4.5f, 14f)
            val labelPaint = android.graphics.Paint().apply {
                color = if (mapDisplayConfig.whiteBackground) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                textSize = (24f * labelScaleMultiplier).coerceIn(14f, 44f)
                isAntiAlias = true
                if (!mapDisplayConfig.whiteBackground) {
                    setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                }
            }

            // 0. Draw Background Aerial / Road Map Tiles + Optional 4-3 Cadastral Hybrid Overlay (with backgroundShiftNE applied!)
            if (refN != 0.0 && refE != 0.0) {
                val bgShiftN = backgroundShiftNE.first
                val bgShiftE = backgroundShiftNE.second
                val (centerLat, centerLon) = CoordinateConverter.planeToWgs84(refN - bgShiftN, refE - bgShiftE, coordSystem)
                if (backgroundMapType != BackgroundMapType.NONE) {
                    val tiles = viewModel.tileMapManager.getVisibleTiles(
                        mapType = backgroundMapType,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        metersPerPixel = scaleMetersPerPixel.toDouble()
                    )
                    tiles.forEach { t ->
                        val bmp = t.bitmap
                        val nwPlane = CoordinateConverter.wgs84ToPlane(t.northLat, t.westLon, 0.0, coordSystem)
                        val sePlane = CoordinateConverter.wgs84ToPlane(t.southLat, t.eastLon, 0.0, coordSystem)
                        val topLeft = worldToScreen(nwPlane.northing + bgShiftN, nwPlane.easting + bgShiftE)
                        val botRight = worldToScreen(sePlane.northing + bgShiftN, sePlane.easting + bgShiftE)
                        val dstW = (botRight.x - topLeft.x).roundToInt().coerceAtLeast(1)
                        val dstH = (botRight.y - topLeft.y).roundToInt().coerceAtLeast(1)
                        if (bmp != null) {
                            drawImage(
                                image = bmp,
                                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                                dstSize = IntSize(dstW, dstH),
                                alpha = backgroundMapAlpha
                            )
                        }
                    }
                }
                if (mapDisplayConfig.cadastralOverlayEnabled && backgroundMapType != BackgroundMapType.CADASTRAL_HYBRID) {
                    val cadTiles = viewModel.tileMapManager.getVisibleTiles(
                        mapType = BackgroundMapType.CADASTRAL_HYBRID,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        metersPerPixel = scaleMetersPerPixel.toDouble()
                    )
                    cadTiles.forEach { t ->
                        val bmp = t.bitmap ?: return@forEach
                        val nwPlane = CoordinateConverter.wgs84ToPlane(t.northLat, t.westLon, 0.0, coordSystem)
                        val sePlane = CoordinateConverter.wgs84ToPlane(t.southLat, t.eastLon, 0.0, coordSystem)
                        val topLeft = worldToScreen(nwPlane.northing + bgShiftN, nwPlane.easting + bgShiftE)
                        val botRight = worldToScreen(sePlane.northing + bgShiftN, sePlane.easting + bgShiftE)
                        val dstW = (botRight.x - topLeft.x).roundToInt().coerceAtLeast(1)
                        val dstH = (botRight.y - topLeft.y).roundToInt().coerceAtLeast(1)
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                            dstSize = IntSize(dstW, dstH),
                            alpha = 0.78f
                        )
                    }
                }
            }

            // 1. Draw External CAD / DWG / DXF / SHP / V-World Entities
            // ★ 발열/배터리 소모 원인이던 꼭짓점 원(drawCircle) 반복 렌더링을 제거하고 도면 선/호/원/점/문자는 100% 그대로 유지
            cadEntities.forEach { ent ->
                if (ent.layerName !in hiddenLayers) {
                    val entColor = Color(ent.colorHex)
                    val verts = ent.verticesNE
                    when (ent.type) {
                        CadEntityType.POINT -> {
                            verts.firstOrNull()?.let { (n, e) ->
                                val p = worldToScreen(n, e)
                                if (p.x in -20f..(size.width + 20f) && p.y in -20f..(size.height + 20f)) {
                                    drawCircle(color = entColor, radius = 4.5f, center = p)
                                }
                            }
                        }
                        CadEntityType.LINE, CadEntityType.POLYLINE, CadEntityType.CIRCLE, CadEntityType.ARC -> {
                            if (verts.size >= 2) {
                                for (i in 0 until verts.size - 1) {
                                    val p1 = worldToScreen(verts[i].first, verts[i].second)
                                    val p2 = worldToScreen(verts[i + 1].first, verts[i + 1].second)
                                    if (max(p1.x, p2.x) >= -50f && min(p1.x, p2.x) <= size.width + 50f &&
                                        max(p1.y, p2.y) >= -50f && min(p1.y, p2.y) <= size.height + 50f
                                    ) {
                                        drawLine(color = entColor, start = p1, end = p2, strokeWidth = dynamicLineStrokePx)
                                    }
                                }
                                if (ent.isClosed && verts.size >= 3) {
                                    val pLast = worldToScreen(verts.last().first, verts.last().second)
                                    val pFirst = worldToScreen(verts.first().first, verts.first().second)
                                    drawLine(color = entColor, start = pLast, end = pFirst, strokeWidth = dynamicLineStrokePx)
                                }
                            }
                            if (ent.textLabel.isNotBlank() && (ent.sourceType == "VWORLD" || mapDisplayConfig.showCadText)) {
                                verts.firstOrNull()?.let { (n, e) ->
                                    val p = worldToScreen(n, e)
                                    if (p.x in -150f..(size.width + 150f) && p.y in -50f..(size.height + 50f)) {
                                        drawContext.canvas.nativeCanvas.drawText(ent.textLabel, p.x + 6f, p.y - 4f, labelPaint)
                                    }
                                }
                            }
                        }
                        CadEntityType.TEXT -> {
                            if (mapDisplayConfig.showCadText || ent.sourceType == "VWORLD") {
                                verts.firstOrNull()?.let { (n, e) ->
                                    val p = worldToScreen(n, e)
                                    if (p.x in -150f..(size.width + 150f) && p.y in -50f..(size.height + 50f)) {
                                        drawContext.canvas.nativeCanvas.drawText(ent.textLabel, p.x, p.y, labelPaint)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Draw Surveyed Auto-Linework Polylines
            val pointMap = points.associateBy { it.id }
            polylines.forEach { poly ->
                if (poly.code !in hiddenLayers && poly.pointIds.size >= 2) {
                    val pts = poly.pointIds.mapNotNull { pointMap[it] }
                    val c = Color(poly.colorHex)
                    for (i in 0 until pts.size - 1) {
                        val p1 = worldToScreen(pts[i].northing, pts[i].easting)
                        val p2 = worldToScreen(pts[i + 1].northing, pts[i + 1].easting)
                        drawLine(color = c, start = p1, end = p2, strokeWidth = 3.2f)
                    }
                    if (poly.isClosed && pts.size >= 3) {
                        val pLast = worldToScreen(pts.last().northing, pts.last().easting)
                        val pFirst = worldToScreen(pts.first().northing, pts.first().easting)
                        drawLine(color = c, start = pLast, end = pFirst, strokeWidth = 3.2f)
                    }
                }
            }

            // 3. Draw COGO Ruler / Area Overlay
            val cogoPts = cogoRulerSummary.selectedPoints
            if (cogoPts.size >= 2) {
                for (i in 0 until cogoPts.size - 1) {
                    val p1 = worldToScreen(cogoPts[i].northing, cogoPts[i].easting)
                    val p2 = worldToScreen(cogoPts[i + 1].northing, cogoPts[i + 1].easting)
                    drawLine(
                        color = Color(0xFFF43F5E),
                        start = p1,
                        end = p2,
                        strokeWidth = 3.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                }
                if (cogoPts.size >= 3) {
                    val pLast = worldToScreen(cogoPts.last().northing, cogoPts.last().easting)
                    val pFirst = worldToScreen(cogoPts.first().northing, cogoPts.first().easting)
                    drawLine(
                        color = Color(0xFFF43F5E),
                        start = pLast,
                        end = pFirst,
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    )
                }
            }

            // 4. Draw Survey Points
            points.forEach { pt ->
                if (pt.code !in hiddenLayers) {
                    val pos = worldToScreen(pt.northing, pt.easting)
                    if (pos.x in -100f..(size.width + 100f) && pos.y in -60f..(size.height + 60f)) {
                        val isStakeTarget = stakeoutTarget?.id == pt.id
                        val isControlPt = pt.memo.startsWith("V-World 지적기준점") || pt.code.contains("도근점") || pt.code.contains("기준점")
                        val ptColor = when {
                            isStakeTarget -> Color(0xFFEF4444)
                            isControlPt -> Color(0xFF38BDF8)
                            else -> Color(0xFF22C55E)
                        }
                        drawCircle(color = Color.Black, radius = dynamicNodeRadiusPx + 2f, center = pos)
                        drawCircle(color = ptColor, radius = dynamicNodeRadiusPx, center = pos)
                        if (isControlPt) {
                            drawCircle(color = Color(0xFFFACC15), radius = dynamicNodeRadiusPx + 5f, center = pos, style = Stroke(width = 2f))
                        }

                        val parts = mutableListOf<String>()
                        if (mapDisplayConfig.showPointName) parts.add(pt.name)
                        if (mapDisplayConfig.showCode && pt.code.isNotBlank()) parts.add("[${pt.code}]")
                        if (mapDisplayConfig.showElevation) {
                            parts.add(String.format(Locale.US, "%.${mapDisplayConfig.decimalPlaces}f", pt.elevation))
                        }
                        if (parts.isNotEmpty()) {
                            drawContext.canvas.nativeCanvas.drawText(
                                parts.joinToString(" "),
                                pos.x + dynamicNodeRadiusPx + 4f,
                                pos.y - 6f,
                                labelPaint
                            )
                        }
                    }
                }
            }

            // 5. Draw Locked PENDING OSNAP Marker & Interactive Drawing Shift Source Marker
            pendingSnappedTarget?.let { lockedSnap ->
                val sPos = worldToScreen(lockedSnap.northing, lockedSnap.easting)
                drawOsnapMarkerSymbol(sPos, lockedSnap.snapType)
            }

            drawingShiftSourceSnap?.let { srcSnap ->
                val srcPos = worldToScreen(srcSnap.northing, srcSnap.easting)
                drawOsnapMarkerSymbol(srcPos, srcSnap.snapType)
                drawCircle(
                    color = Color(0xFFEC4899),
                    radius = 28f,
                    center = srcPos,
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    )
                )
                val surveyedOnly = points.filterNot {
                    it.memo.startsWith("V-World 지적기준점") || it.timestamp == "DXF 도면"
                }.ifEmpty { points }
                surveyedOnly.takeLast(6).forEach { sPt ->
                    val dstPos = worldToScreen(sPt.northing, sPt.easting)
                    drawLine(
                        color = Color(0xFFFACC15).copy(alpha = 0.75f),
                        start = srcPos,
                        end = dstPos,
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                    drawCircle(
                        color = Color(0xFF22C55E),
                        radius = (dynamicNodeRadiusPx * 1.55f).coerceIn(12f, 34f),
                        center = dstPos,
                        style = Stroke(width = 3.5f)
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "🎯잡은도면점(${srcSnap.label})",
                    srcPos.x + 22f,
                    srcPos.y - 14f,
                    labelPaint
                )
            }

            // 6. Draw Stakeout Guidance Line & Bullseye Concentric Circles
            val myPos = worldToScreen(planeCoord.northing, planeCoord.easting)
            stakeoutTarget?.let { target ->
                val tPos = worldToScreen(target.northing, target.easting)
                drawLine(
                    color = Color(0xFFEF4444),
                    start = myPos,
                    end = tPos,
                    strokeWidth = 3.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
                )
                listOf(0.05f, 0.25f, 0.5f, 1.0f, 2.0f).forEach { rMeters ->
                    val rPx = rMeters / scaleMetersPerPixel
                    if (rPx in 6f..420f) {
                        drawCircle(
                            color = Color(0x88EF4444),
                            radius = rPx,
                            center = tPos,
                            style = Stroke(width = 1.5f)
                        )
                    }
                }
            }

            // 7. Draw Current GPS Position Marker
            drawCircle(color = Color(0x4438BDF8), radius = 20f, center = myPos)
            drawCircle(color = Color.White, radius = 9f, center = myPos)
            drawCircle(color = Color(0xFF0284C7), radius = 6.5f, center = myPos)
        }

        // 좌측 상단 플로팅 배너 영역
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 8.dp, end = 98.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 미니 측설 유도 배너
            stakeoutGuidance?.let { g ->
                Surface(
                    color = Color(0xE60F172A),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.dp, Color(0xFFFACC15), RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🎯 측설: ${g.targetName} (거리: ${String.format(Locale.US, "%.3fm", g.horizontalDistance)})",
                                color = Color(0xFFFACC15),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (stakeoutSoundEnabled) "🔔종소리ON" else "🔕종소리OFF",
                                    color = if (stakeoutSoundEnabled) Color(0xFF4ADE80) else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { viewModel.setStakeoutSoundEnabled(!stakeoutSoundEnabled) }
                                )
                                Text(
                                    text = "종료✕",
                                    color = Color(0xFFF87171),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { viewModel.setStakeoutTarget(null) }
                                )
                            }
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "전/후:%+.3fm | 좌/우:%+.3fm | 표고ΔZ:%+.3fm",
                                g.forwardMeters,
                                g.rightMeters,
                                g.deltaZ
                            ),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 2단계 OSNAP 도면점 포착 확인 카드
            pendingSnappedTarget?.let { locked ->
                Surface(
                    color = Color(0xF20F172A),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.5.dp, Color(0xFFFACC15), RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "📐 도면 오스냅 포착: [${locked.snapType.koreanLabel}] ${locked.label}",
                            color = Color(0xFFFACC15),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = String.format(Locale.US, "N:%.3f  E:%.3f  Z:%.3f", locked.northing, locked.easting, locked.elevationZ),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.startStakeoutFromPendingSnap() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("측설 시작", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Button(
                                onClick = { viewModel.startInteractiveDrawingShiftFromSnap(locked) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDB2777)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("🎯 현황점으로 끌어가기", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            }
                            Button(
                                onClick = { viewModel.addPendingSnapToSurveyPoints() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                Text("측점추가", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onOpenDrawingShiftDialog,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("수치이동", fontSize = 11.sp, color = Color(0xFFFACC15))
                            }
                            TextButton(
                                onClick = { viewModel.clearPendingCadSnap() }
                            ) {
                                Text("취소", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }

            // Floating Interactive 2-Step Drawing & Background Snap-Shift Banner (`🎯 도면·배경 잡고 측량 현황점으로 일괄 이동`)
            if (canvasToolMode == CanvasToolMode.DRAWING_SNAP_SHIFT) {
                val src = drawingShiftSourceSnap
                val surveyedOnly = remember(points) {
                    points.filterNot { it.memo.startsWith("V-World 지적기준점") || it.timestamp == "DXF 도면" }.ifEmpty { points }
                }
                Surface(
                    color = Color(0xF50F172A),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 12.dp,
                    modifier = Modifier.border(
                        2.dp,
                        if (src == null) Color(0xFFFACC15) else Color(0xFFEC4899),
                        RoundedCornerShape(12.dp)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (src == null) "🎯 [1단계] 옮길 도면(지적도·CAD) 모서리를 잡으세요"
                                else "🎯 [2단계] 맞출 측량 포인트(현황점)를 터치하세요!",
                                color = if (src == null) Color(0xFFFACC15) else Color(0xFFF472B6),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (src != null) {
                                Surface(
                                    color = Color(0xFF1E293B),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { viewModel.clearDrawingShiftSourceSnap() }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text("모서리 다시잡기", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                color = Color(0xFF3F1D1D),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .clickable { viewModel.setCanvasToolMode(CanvasToolMode.NORMAL) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text("닫기", color = Color(0xFFFCA5A5), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        if (src == null) {
                            Text(
                                text = "• 화면의 지적도나 불러온 도면 모서리를 터치(또는 꾹 눌러 오스냅)하면 기준점으로 잡힙니다. (내가 측량한 점/선은 고정된 채 배경과 외부 도면만 이동)",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "잡은 도면점: [%s] %s (N:%.3f, E:%.3f)",
                                    src.snapType.koreanLabel,
                                    src.label,
                                    src.northing,
                                    src.easting
                                ),
                                color = Color(0xFF22C55E),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "👇 도면 화면에서 [측량한 포인트]를 직접 터치하거나 아래 버튼을 누르세요:",
                                color = Color(0xFFFACC15),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Color(0xFF0284C7),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { viewModel.alignDrawingSourceSnapToCurrentGps() }
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text("📍 현위치(GPS)로 맞춤", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                surveyedOnly.reversed().forEach { pt ->
                                    Surface(
                                        color = Color(0xFF16A34A),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .clickable { viewModel.alignDrawingSourceSnapToSurveyPoint(pt) }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "🎯 #${pt.seqNumber} ${pt.name}(${pt.code})",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(5.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = if (shiftIncludeBackgroundTiles) Color(0xFF1E3A8A) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .border(1.dp, if (shiftIncludeBackgroundTiles) Color(0xFF38BDF8) else Color(0xFF475569), RoundedCornerShape(6.dp))
                                    .clickable { viewModel.setShiftIncludeBackgroundTiles(!shiftIncludeBackgroundTiles) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (shiftIncludeBackgroundTiles) "☑ 위성·배경타일도 함께 이동 ON" else "☐ 도면(선)만 이동 (배경타일 고정)",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (backgroundShiftNE.first != 0.0 || backgroundShiftNE.second != 0.0) {
                                Surface(
                                    color = Color(0xFF334155),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { viewModel.resetBackgroundShiftNE() }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text("배경타일 원위치", color = Color(0xFFFACC15), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (undoHistory.isNotEmpty()) {
                                Surface(
                                    color = Color(0xFF334155),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clickable { viewModel.undoLastAction() }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text("↩ 직전이동 취소", color = Color(0xFFF87171), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Floating COGO Distance / Grade / Area (㎡ & 평) Banner when COGO_RULER_AREA is active
            if (canvasToolMode == CanvasToolMode.COGO_RULER_AREA || cogoRulerSummary.selectedPoints.isNotEmpty()) {
                Surface(
                    color = Color(0xE61E1B4B),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.dp, Color(0xFF818CF8), RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📏 거리·면적 자 (${cogoRulerSummary.selectedPoints.size}점 선택)",
                                color = Color(0xFFC7D2FE),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "초기화✕",
                                color = Color(0xFFFCA5A5),
                                fontSize = 10.sp,
                                modifier = Modifier.clickable { viewModel.clearCogoRuler() }
                            )
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "총 수평거리: %.3fm | 면적: %.2f㎡ (%.2f평)",
                                cogoRulerSummary.totalHorizontalLengthMeters,
                                cogoRulerSummary.areaSqMeters,
                                cogoRulerSummary.areaPyeong
                            ),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 굴삭기 절성토 실시간 배너
            designGradeGuidance?.let { dg ->
                Surface(
                    color = Color(0xE60F172A),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.dp, Color(0xFF2DD4BF), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "🚜 계획고:%.3fm | 현재:%.3fm | %s %.3fm",
                                dg.computedDesignZ,
                                dg.currentGroundZ,
                                if (dg.cutFillDeltaZ >= 0) "성토(올림)" else "절토(깎기)",
                                abs(dg.cutFillDeltaZ)
                            ),
                            color = Color(0xFFFDE047),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "🔊음성",
                            color = Color(0xFF38BDF8),
                            fontSize = 10.sp,
                            modifier = Modifier.clickable { viewModel.speakCurrentCutFillGuidance() }
                        )
                    }
                }
            }
        }

        // 수평기포관 위젯 (도구함에서 켰을 때만 표시! 기본 OFF 시 완전히 숨김)
        if (imuBubbleVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 118.dp)
            ) {
                ElectronicBubbleLevelWidget(
                    expanded = true,
                    tiltAngleDeg = liveTiltAngle,
                    pitchDeg = sensorPitchDeg.toDouble(),
                    rollDeg = sensorRollDeg.toDouble(),
                    maxTiltLimitDeg = imuMaxTiltLimit,
                    compensationEnabled = imuCompensationEnabled,
                    blockOnExcess = imuBlockOnExcess,
                    onClick = onOpenImuTiltDialog
                )
            }
        }

        // 수평기포관이 켜져 있고 기울기 한계를 초과했을 때만 경고 배너 표시
        if (imuBubbleVisible && imuWarningActive) {
            Surface(
                color = Color(0xEEDC2626),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "⚠️ 폴대 기울기 초과 (%.1f° > %.1f°)", liveTiltAngle, imuMaxTiltLimit),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // 우측 접이식 슬림 도구 바 (기본: 아이콘만 작게 표시 ↔ 화살표 ◀/▶ 터치 시 글자까지 확장)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End
        ) {
            val btnMod = if (isRightBarExpanded) {
                Modifier.size(width = 96.dp, height = 28.dp)
            } else {
                Modifier.size(width = 36.dp, height = 28.dp)
            }

            // 화살표 펼치기/접기 토글 버튼
            SlimRightButton(
                label = if (isRightBarExpanded) "▶ 메뉴접기" else "◀",
                modifier = btnMod,
                bgColor = Color(0xEE0284C7)
            ) {
                isRightBarExpanded = !isRightBarExpanded
            }

            SlimRightButton(if (isRightBarExpanded) "➕ 확대 +" else "➕", btnMod) {
                val oldS = scaleMetersPerPixel
                val newS = (oldS / 1.35f).coerceAtLeast(0.001f)
                val ratio = oldS / newS
                scaleMetersPerPixel = newS
                panOffsetPx = panOffsetPx * ratio
            }
            SlimRightButton(if (isRightBarExpanded) "➖ 축소 -" else "➖", btnMod) {
                val oldS = scaleMetersPerPixel
                val newS = (oldS * 1.35f).coerceAtMost(50.0f)
                val ratio = oldS / newS
                scaleMetersPerPixel = newS
                panOffsetPx = panOffsetPx * ratio
            }
            SlimRightButton(if (isRightBarExpanded) "⛶ 전체보기" else "⛶", btnMod) { triggerZoomExtend(silent = false) }
            SlimRightButton(if (isRightBarExpanded) "◎ 현위치" else "◎", btnMod) { panOffsetPx = Offset.Zero }
            SlimRightButton(if (isRightBarExpanded) "가+ 글자확대" else "가+", btnMod) { labelScaleMultiplier = (labelScaleMultiplier + 0.15f).coerceAtMost(2.5f) }
            SlimRightButton(if (isRightBarExpanded) "가- 글자축소" else "가-", btnMod) { labelScaleMultiplier = (labelScaleMultiplier - 0.15f).coerceAtLeast(0.5f) }
            SlimRightButton(
                label = if (isRightBarExpanded) (if (touchLockEnabled) "🔒 잠금중" else "🔓 터치잠금") else (if (touchLockEnabled) "🔒" else "🔓"),
                modifier = btnMod,
                bgColor = if (touchLockEnabled) Color(0xFFB91C1C) else Color(0xCC1E293B)
            ) {
                viewModel.setTouchLockEnabled(!touchLockEnabled)
            }
            SlimRightButton(if (isRightBarExpanded) "🏛️ 지적/도근점" else "🏛️", btnMod) { onOpenVWorldDialog() }
            SlimRightButton(if (isRightBarExpanded) "🗂️ 레이어" else "🗂️", btnMod) { onOpenLayerDialog() }
            SlimRightButton(if (isRightBarExpanded) "👁️ 표시설정" else "👁️", btnMod) { onOpenDisplayConfigDialog() }
            SlimRightButton(if (isRightBarExpanded) "🔍 주소검색" else "🔍", btnMod) { onOpenAddressSearchDialog() }
            SlimRightButton(
                label = if (isRightBarExpanded) "💾 프로젝트저장" else "💾",
                modifier = btnMod,
                bgColor = Color(0xCC0369A1)
            ) {
                viewModel.saveCurrentProjectExplicitly()
            }
        }

        // 하단 원터치 측량 & 도구 칩 바
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xEE0F172A))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // 상단 칩 줄: 측정모드(FIX/FLOAT/DGPS) / 에포크 횟수 / 사진촬영 / 도면잡고이동 / 거리·면적자 / 결선모드 / 퀵코드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 측정 허용 모드 (FIX / FLOAT / DGPS) 원터치 전환 칩
                FilterChip(
                    selected = true,
                    onClick = {
                        val modes = RequiredFixMode.values()
                        val nextIdx = (modes.indexOf(requiredFixMode) + 1) % modes.size
                        viewModel.setRequiredFixMode(modes[nextIdx])
                    },
                    label = { Text("🛰️측정:${requiredFixMode.shortLabel}", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (requiredFixMode) {
                            RequiredFixMode.RTK_FIXED_ONLY -> Color(0xFF16A34A)
                            RequiredFixMode.RTK_FLOAT_OR_BETTER -> Color(0xFFD97706)
                            RequiredFixMode.ALL_VALID_FIX -> Color(0xFF0284C7)
                        },
                        selectedLabelColor = Color.White
                    )
                )

                // 에포크(평균 측정 횟수) 원터치 전환 칩 (1 -> 3 -> 5 -> 10 -> 20 -> 30회)
                FilterChip(
                    selected = lastMeasurePreset.epochs > 1,
                    onClick = {
                        val epochOptions = listOf(1, 3, 5, 10, 20, 30)
                        val curIdx = epochOptions.indexOf(lastMeasurePreset.epochs)
                        val nextEpochs = epochOptions[(curIdx + 1) % epochOptions.size]
                        viewModel.updateMeasurePreset(
                            code = lastMeasurePreset.code,
                            epochs = nextEpochs,
                            memo = lastMeasurePreset.memo,
                            autoLineMode = lastMeasurePreset.autoLineMode
                        )
                        viewModel.postToast("평균 에포크 횟수: ${nextEpochs}회")
                    },
                    label = { Text("⏱️에포크:${lastMeasurePreset.epochs}회", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )

                // 현장 기점 좌표 + 사진설명 보드 카메라 촬영 버튼
                FilterChip(
                    selected = false,
                    onClick = { viewModel.openPhotoBoardForLatestPoint() },
                    label = { Text("📸사진보드촬영", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFDE047)) }
                )

                FilterChip(
                    selected = (canvasToolMode == CanvasToolMode.DRAWING_SNAP_SHIFT),
                    onClick = {
                        if (canvasToolMode == CanvasToolMode.DRAWING_SNAP_SHIFT) {
                            viewModel.setCanvasToolMode(CanvasToolMode.NORMAL)
                        } else {
                            viewModel.startInteractiveDrawingShiftFromSnap(pendingSnappedTarget)
                        }
                    },
                    label = { Text("🎯도면잡고이동", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFDB2777),
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = (canvasToolMode == CanvasToolMode.COGO_RULER_AREA),
                    onClick = {
                        val next = if (canvasToolMode == CanvasToolMode.COGO_RULER_AREA) CanvasToolMode.NORMAL else CanvasToolMode.COGO_RULER_AREA
                        viewModel.setCanvasToolMode(next)
                    },
                    label = { Text("📏거리·면적자", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = (canvasToolMode == CanvasToolMode.MANUAL_LINE_CONNECT),
                    onClick = {
                        val next = if (canvasToolMode == CanvasToolMode.MANUAL_LINE_CONNECT) CanvasToolMode.NORMAL else CanvasToolMode.MANUAL_LINE_CONNECT
                        viewModel.setCanvasToolMode(next)
                    },
                    label = { Text("✏️수동선연결", fontSize = 11.sp) }
                )

                // 자동 결선 모드 순환 버튼
                FilterChip(
                    selected = lastMeasurePreset.autoLineMode != AutoLineMode.NONE,
                    onClick = {
                        val modes = AutoLineMode.values()
                        val nextIdx = (modes.indexOf(lastMeasurePreset.autoLineMode) + 1) % modes.size
                        viewModel.updateMeasurePreset(
                            code = lastMeasurePreset.code,
                            epochs = lastMeasurePreset.epochs,
                            memo = lastMeasurePreset.memo,
                            autoLineMode = modes[nextIdx]
                        )
                    },
                    label = { Text("🔗${lastMeasurePreset.autoLineMode.shortLabel}", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )

                quickCodes.forEach { preset ->
                    val isSelected = lastMeasurePreset.code == preset.code
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            viewModel.updateMeasurePreset(
                                code = preset.code,
                                epochs = lastMeasurePreset.epochs,
                                memo = lastMeasurePreset.memo,
                                autoLineMode = lastMeasurePreset.autoLineMode
                            )
                        },
                        label = { Text(preset.code, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 하단 측정 버튼 바
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { showMeasureInputDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "다음 점번호: ${viewModel.generateNextPointName()} [${lastMeasurePreset.code}]",
                        color = Color(0xFFFDE047),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "결선:${lastMeasurePreset.autoLineMode.shortLabel} | 터치하여 상세입력",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                }

                val activeEpochs = if (lastMeasurePreset.epochs > 1) lastMeasurePreset.epochs else 5
                Button(
                    onClick = {
                        viewModel.measureSurveyPoint(
                            name = viewModel.generateNextPointName(),
                            code = lastMeasurePreset.code,
                            epochs = activeEpochs,
                            memo = lastMeasurePreset.memo,
                            autoLineMode = lastMeasurePreset.autoLineMode
                        )
                    },
                    enabled = !isAveraging,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isAveraging) "평균중($averagingProgress%)" else "정밀평균(${activeEpochs}회)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { viewModel.instantRepeatMeasure() },
                    enabled = !isAveraging,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "📍 즉시측정",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }

    if (showMeasureInputDialog) {
        MeasureDetailInputDialog(
            initialName = viewModel.generateNextPointName(),
            initialPreset = lastMeasurePreset,
            initialAntenna = antennaHeight,
            quickCodes = quickCodes,
            autoOpenPhotoBoard = autoOpenPhotoBoard,
            onToggleAutoPhoto = { viewModel.setAutoOpenPhotoBoardAfterMeasure(it) },
            onConfirmMeasure = { name, code, epochs, memo, lineMode, ant ->
                viewModel.setAntennaHeight(ant)
                viewModel.updateMeasurePreset(code, epochs, memo, lineMode)
                viewModel.measureSurveyPoint(name, code, epochs, memo, lineMode)
                showMeasureInputDialog = false
            },
            onDismiss = { showMeasureInputDialog = false }
        )
    }
}

private fun DrawScope.drawOsnapMarkerSymbol(pos: Offset, snapType: CadSnapType) {
    val c = Color(0xFFFACC15)
    when (snapType) {
        CadSnapType.ENDPOINT -> {
            drawRect(
                color = c,
                topLeft = Offset(pos.x - 12f, pos.y - 12f),
                size = Size(24f, 24f),
                style = Stroke(width = 3f)
            )
        }
        CadSnapType.MIDPOINT -> {
            val path = Path().apply {
                moveTo(pos.x, pos.y - 14f)
                lineTo(pos.x - 13f, pos.y + 11f)
                lineTo(pos.x + 13f, pos.y + 11f)
                close()
            }
            drawPath(path = path, color = c, style = Stroke(width = 3f))
        }
        else -> {
            drawCircle(color = c, radius = 13f, center = pos, style = Stroke(width = 3f))
            drawLine(c, Offset(pos.x - 18f, pos.y), Offset(pos.x + 18f, pos.y), 2.5f)
            drawLine(c, Offset(pos.x, pos.y - 18f), Offset(pos.x, pos.y + 18f), 2.5f)
        }
    }
}

@Composable
private fun SlimRightButton(
    label: String,
    modifier: Modifier = Modifier,
    bgColor: Color = Color(0xCC1E293B),
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, Color(0xFF475569), RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun ElectronicBubbleLevelWidget(
    expanded: Boolean,
    tiltAngleDeg: Double,
    pitchDeg: Double,
    rollDeg: Double,
    maxTiltLimitDeg: Double,
    compensationEnabled: Boolean,
    blockOnExcess: Boolean,
    onClick: () -> Unit
) {
    if (!expanded) return
    val isOk = tiltAngleDeg <= maxTiltLimitDeg
    Box(
        modifier = Modifier
            .size(86.dp)
            .clip(CircleShape)
            .background(Color(0xD90F172A))
            .border(2.dp, if (isOk) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.width / 2f - 8f
            drawCircle(color = Color(0x5594A3B8), radius = maxR * 0.4f, center = c, style = Stroke(1.2f))
            drawLine(Color(0x5594A3B8), Offset(c.x - maxR, c.y), Offset(c.x + maxR, c.y), 1f)
            drawLine(Color(0x5594A3B8), Offset(c.x, c.y - maxR), Offset(c.x, c.y + maxR), 1f)

            val clampRoll = (rollDeg / 10.0).coerceIn(-1.0, 1.0)
            val clampPitch = (pitchDeg / 10.0).coerceIn(-1.0, 1.0)
            val bx = c.x + (clampRoll * maxR).toFloat()
            val by = c.y + (clampPitch * maxR).toFloat()
            drawCircle(
                color = if (isOk) Color(0xFF4ADE80) else Color(0xFFF87171),
                radius = 7f,
                center = Offset(bx, by)
            )
        }
        Text(
            text = String.format(Locale.US, "%.1f°%s", tiltAngleDeg, if (compensationEnabled) "보정" else ""),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
        )
    }
}

// ============================================================================
// 2. 좌표측설 / 도면측설 전용 화면 (StakeoutScreen)
// ============================================================================
@Composable
fun StakeoutScreen(
    viewModel: GandanSurveyViewModel,
    onJumpToMap: () -> Unit
) {
    val points by viewModel.points.collectAsState()
    val target by viewModel.stakeoutTarget.collectAsState()
    val guidance by viewModel.stakeoutGuidance.collectAsState()
    val soundEnabled by viewModel.stakeoutSoundEnabled.collectAsState()
    val dirMode by viewModel.stakeoutDirectionMode.collectAsState()
    var showManualDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎯 정밀 좌표측설 (15m / 1m 2단계 종소리 안내)",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onJumpToMap,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("도면에서 보기", fontSize = 12.sp)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔔 거리별 2단계 측설 종소리 안내", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "• 15m ~ 1m 이내: 간헐적 맑은 단일 종소리 (딩~)\n• 1m 이내 근접: 빠른 고음 2연타 종소리 (딩-동!)",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = { viewModel.setStakeoutSoundEnabled(it) }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StakeoutDirectionMode.values().forEach { mode ->
                        val selected = dirMode == mode
                        OutlinedButton(
                            onClick = { viewModel.setStakeoutDirectionMode(mode) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) Color(0xFF0284C7) else Color.Transparent,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(mode.shortLabel, fontSize = 11.sp)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { viewModel.selectNearestPointForStakeout() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        modifier = Modifier.weight(1f)
                    ) { Text("가장 가까운 점 자동선택", fontSize = 11.sp) }

                    OutlinedButton(
                        onClick = { showManualDialog = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("➕ 측설좌표 직접입력", fontSize = 11.sp) }
                }
            }
        }

        guidance?.let { g ->
            val distColor = when {
                g.horizontalDistance <= 0.05 -> Color(0xFF22C55E)
                g.horizontalDistance <= 1.0 -> Color(0xFFFACC15)
                else -> Color(0xFF38BDF8)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "목표점: ${g.targetName}",
                            color = Color(0xFFFACC15),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { viewModel.stepStakeoutTarget(-1) }, contentPadding = PaddingValues(6.dp)) {
                                Text("◀ 이전점", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { viewModel.stepStakeoutTarget(1) }, contentPadding = PaddingValues(6.dp)) {
                                Text("다음점 ▶", fontSize = 11.sp)
                            }
                        }
                    }
                    Text(
                        text = String.format(Locale.US, "수평거리: %.3f m", g.horizontalDistance),
                        color = distColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(Locale.US, "북/남(ΔN): %+.3f m", g.deltaN), color = Color.White, fontSize = 13.sp)
                        Text(String.format(Locale.US, "동/서(ΔE): %+.3f m", g.deltaE), color = Color.White, fontSize = 13.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(Locale.US, "전/후방: %+.3f m", g.forwardMeters), color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(String.format(Locale.US, "좌/우측: %+.3f m", g.rightMeters), color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = String.format(
                            Locale.US,
                            "표고차(ΔZ): %+.3f m (${if (g.deltaZ >= 0) "성토/올림" else "절토/내림"} %.3f m)",
                            g.deltaZ,
                            abs(g.deltaZ)
                        ),
                        color = Color(0xFF4ADE80),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text("📋 측점 목록에서 목표점 선택 (${points.size}개)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        points.forEach { pt ->
            val isSelected = target?.id == pt.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color(0xFF1E3A8A) else Color(0xFF1E293B))
                    .clickable { viewModel.setStakeoutTarget(pt) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("#${pt.seqNumber} ${pt.name} [${pt.code}]", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        String.format(Locale.US, "N:%.3f  E:%.3f  EL:%.3f", pt.northing, pt.easting, pt.elevation),
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = if (isSelected) "🎯 측설중" else "선택",
                    color = if (isSelected) Color(0xFFFACC15) else Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showManualDialog) {
        AddManualStakeoutPointDialog(
            onConfirm = { name, n, e, z, code ->
                viewModel.addManualStakeoutPoint(name = name, code = code, northing = n, easting = e, elevation = z, memo = "", setAsImmediateTarget = true)
                showManualDialog = false
            },
            onDismiss = { showManualDialog = false }
        )
    }
}

// ============================================================================
// 3. 측점 관리(체크·구간선택·일괄수정) 및 내보내기/카톡공유 화면 (PointsAndExportScreen)
// ============================================================================
@Composable
fun PointsAndExportScreen(
    viewModel: GandanSurveyViewModel,
    onJumpToMap: () -> Unit
) {
    val points by viewModel.points.collectAsState()
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var showBatchEditDialog by remember { mutableStateOf(false) }
    var showBatchAntennaDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 내보내기 & 카카오톡 공유 카드
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📤 도면(DXF) · 좌표(CSV) 내보내기 및 카카오톡/이메일 공유", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { viewModel.exportCsvNow() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("CSV 저장", fontSize = 11.sp) }

                    Button(
                        onClick = { viewModel.exportDxfNow(DxfEncodingMode.CP949_KOREAN) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("DXF 저장", fontSize = 11.sp) }

                    Button(
                        onClick = { viewModel.exportKmlNow() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("KML 저장", fontSize = 11.sp) }

                    Button(
                        onClick = { viewModel.exportAndShareFullZipBundle(shareImmediately = false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("통합ZIP", fontSize = 11.sp) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.shareExportedFileNow("DXF_CP949") },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("💬 DXF 카톡공유", fontSize = 11.sp, color = Color(0xFFFACC15)) }

                    OutlinedButton(
                        onClick = { viewModel.shareExportedFileNow("CSV") },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("💬 CSV 카톡공유", fontSize = 11.sp, color = Color(0xFFFACC15)) }

                    OutlinedButton(
                        onClick = { viewModel.exportAndShareFullZipBundle(shareImmediately = true) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("💬 ZIP 카톡공유", fontSize = 11.sp, color = Color(0xFFFACC15)) }
                }
            }
        }

        // 다중 선택 & 구간 선택 & 일괄 수정 툴바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    selectedIds = if (selectedIds.size == points.size && points.isNotEmpty()) emptySet()
                    else points.map { it.id }.toSet()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(if (selectedIds.size == points.size && points.isNotEmpty()) "전체해제" else "전체선택(${selectedIds.size})", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = { showRangeDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("🔢 번호구간선택", fontSize = 11.sp)
            }

            Button(
                onClick = {
                    if (selectedIds.isEmpty() && points.isNotEmpty()) {
                        selectedIds = points.map { it.id }.toSet()
                    }
                    showBatchAntennaDialog = true
                },
                enabled = points.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("📏 일괄 안테나고 변경", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { showBatchEditDialog = true },
                enabled = selectedIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("✏️ 코드/표고 일괄수정", fontSize = 11.sp)
            }

            Button(
                onClick = {
                    viewModel.batchDeleteSurveyPoints(selectedIds)
                    selectedIds = emptySet()
                },
                enabled = selectedIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("🗑️ 선택삭제", fontSize = 11.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(points, key = { it.id }) { pt ->
                val isChecked = pt.id in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isChecked) Color(0xFF1E3A8A) else Color(0xFF1E293B))
                        .clickable {
                            selectedIds = if (isChecked) selectedIds - pt.id else selectedIds + pt.id
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = {
                            selectedIds = if (isChecked) selectedIds - pt.id else selectedIds + pt.id
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${pt.seqNumber} ${pt.name} [${pt.code}] (폴대:${String.format(Locale.US, "%.2fm", pt.antennaHeight)})",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = String.format(Locale.US, "N:%.3f  E:%.3f  EL:%.3f", pt.northing, pt.easting, pt.elevation),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            viewModel.setStakeoutTarget(pt)
                            onJumpToMap()
                        }) {
                            Text("측설", color = Color(0xFFFACC15), fontSize = 11.sp)
                        }
                        TextButton(onClick = { viewModel.openPhotoBoardForPoint(pt) }) {
                            Text("사진보드", color = Color(0xFF38BDF8), fontSize = 11.sp)
                        }
                        TextButton(onClick = { viewModel.deletePoint(pt.id) }) {
                            Text("삭제", color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (showRangeDialog) {
        SeqRangeSelectDialog(
            points = points,
            onSelectIds = { ids ->
                selectedIds = selectedIds + ids
                showRangeDialog = false
            },
            onDismiss = { showRangeDialog = false }
        )
    }

    if (showBatchAntennaDialog) {
        BatchAntennaHeightOnlyDialog(
            selectedCount = selectedIds.size,
            favoriteAntenna = viewModel.favoriteAntennaHeight.collectAsState().value,
            onApplyAntennaChange = { useRecordedOld, manualOldH, newH ->
                viewModel.batchUpdateSurveyPoints(
                    targetPointIds = selectedIds,
                    applyAntennaHeightChange = true,
                    useEachPointRecordedAntennaAsOld = useRecordedOld,
                    manualOldAntennaHeight = manualOldH,
                    newAntennaHeight = newH
                )
                showBatchAntennaDialog = false
            },
            onDismiss = { showBatchAntennaDialog = false }
        )
    }

    if (showBatchEditDialog) {
        BatchEditSurveyPointsDialog(
            selectedCount = selectedIds.size,
            onApplyBatch = { applyAnt, newAnt, applyDeltaZ, deltaZ, applyCode, newCode, applyMemo, newMemo ->
                viewModel.batchUpdateSurveyPoints(
                    targetPointIds = selectedIds,
                    applyAntennaHeightChange = applyAnt,
                    newAntennaHeight = newAnt,
                    useEachPointRecordedAntennaAsOld = true,
                    manualOldAntennaHeight = newAnt,
                    applyDirectDeltaZ = applyDeltaZ,
                    directDeltaZ = deltaZ,
                    applyNewCode = applyCode,
                    newCode = newCode,
                    applyNewMemo = applyMemo,
                    newMemo = newMemo
                )
                showBatchEditDialog = false
            },
            onDismiss = { showBatchEditDialog = false }
        )
    }
}

// ============================================================================
// 4. 블루투스 RTK 수신기 및 국토지리정보원 VRS NTRIP 설정 화면 (GnssAndNtripScreen)
// ============================================================================
@Composable
fun GnssAndNtripScreen(viewModel: GandanSurveyViewModel) {
    val sourceMode by viewModel.sourceMode.collectAsState()
    val gnssStatus by viewModel.gnssStatus.collectAsState()
    val ntripConfig by viewModel.ntripConfig.collectAsState()
    val mountpoints by viewModel.availableMountpoints.collectAsState()
    val autoReconnect by viewModel.autoReconnectEnabled.collectAsState()

    var pairedDevices by remember { mutableStateOf(viewModel.getPairedBluetoothDevices()) }
    var host by remember(ntripConfig.host) { mutableStateOf(ntripConfig.host) }
    var port by remember(ntripConfig.port) { mutableStateOf(ntripConfig.port.toString()) }
    var mount by remember(ntripConfig.mountpoint) { mutableStateOf(ntripConfig.mountpoint) }
    var user by remember(ntripConfig.username) { mutableStateOf(ntripConfig.username) }
    var pass by remember(ntripConfig.password) { mutableStateOf(ntripConfig.password) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val requiredFixMode by viewModel.requiredFixMode.collectAsState()
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🛰️ 측량 기록 허용 모드 (FIX / FLOAT / DGPS 선택)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("휴대폰 내장 GPS나 단독 측위로 테스트할 때는 [DGPS]를 선택하세요.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RequiredFixMode.values().forEach { rMode ->
                        val sel = requiredFixMode == rMode
                        Button(
                            onClick = { viewModel.setRequiredFixMode(rMode) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sel) Color(0xFF16A34A) else Color(0xFF0F172A)
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(6.dp)
                        ) {
                            Text(rMode.shortLabel, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }

        Text("📡 위치 수신 모드 선택", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PositioningSourceMode.values().forEach { mode ->
                val selected = sourceMode == mode
                Button(
                    onClick = { viewModel.setPositioningSourceMode(mode) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFF0284C7) else Color(0xFF1E293B)
                    ),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(6.dp)
                ) {
                    Text(mode.shortLabel, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        // 블루투스 RTK 수신기 연결 카드
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("🔵 블루투스 RTK 수신기 연결", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "현재 상태: ${if (gnssStatus.btConnected) "연결됨 (${gnssStatus.connectedDeviceName})" else "미연결"}",
                            color = if (gnssStatus.btConnected) Color(0xFF4ADE80) else Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            pairedDevices = viewModel.getPairedBluetoothDevices()
                            viewModel.startBluetoothScan()
                        }) {
                            Text("기기검색", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { viewModel.disconnectBluetoothReceiver() }) {
                            Text("해제", fontSize = 11.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("수신기 끊김 시 자동 재연결", color = Color.White, fontSize = 12.sp)
                    Switch(checked = autoReconnect, onCheckedChange = { viewModel.setAutoReconnectEnabled(it) })
                }

                pairedDevices.forEach { dev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F172A))
                            .clickable { viewModel.connectBluetoothReceiver(dev) }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(dev.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(dev.address, color = Color.Gray, fontSize = 11.sp)
                        }
                        Text("연결하기", color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 국토지리정보원 VRS (NTRIP) 설정 카드
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🌐 국토지리정보원 VRS (NTRIP) 설정", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "상태: ${gnssStatus.ntripStatusMessage} (${gnssStatus.rtcmBytesPerSec} B/s)",
                    color = if (gnssStatus.ntripConnected) Color(0xFF4ADE80) else Color(0xFFFACC15),
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("RTS2 호스트 주소") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("포트") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = mount,
                        onValueChange = { mount = it },
                        label = { Text("마운트포인트 (예: VRS-RTCM32)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            viewModel.updateNtripConfig(
                                ntripConfig.copy(
                                    host = host.trim(),
                                    port = port.toIntOrNull() ?: 2101,
                                    mountpoint = mount.trim(),
                                    username = user.trim(),
                                    password = pass.trim()
                                )
                            )
                            viewModel.fetchMountpoints()
                        }
                    ) {
                        Text("목록조회", fontSize = 11.sp)
                    }
                }

                if (mountpoints.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        mountpoints.forEach { mp ->
                            SuggestionChip(
                                onClick = { mount = mp },
                                label = { Text(mp, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("국토정보플랫폼 아이디") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = {
                        viewModel.updateNtripConfig(
                            ntripConfig.copy(
                                host = host.trim(),
                                port = port.toIntOrNull() ?: 2101,
                                mountpoint = mount.trim(),
                                username = user.trim(),
                                password = pass.trim()
                            )
                        )
                        viewModel.toggleNtripConnection()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (gnssStatus.ntripConnected) Color(0xFFDC2626) else Color(0xFF16A34A)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (gnssStatus.ntripConnected) "VRS 연결 중지" else "VRS 보정신호 연결 시작",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ============================================================================
// 5. 좌표계 설정, 지오이드, 폴대높이 및 현장 로컬 캘리브레이션 화면 (CoordAndCalibrationScreen)
// ============================================================================
@Composable
fun CoordAndCalibrationScreen(viewModel: GandanSurveyViewModel) {
    val selectedSystem by viewModel.coordinateSystem.collectAsState()
    val useKnGeoid18 by viewModel.useKnGeoid18.collectAsState()
    val antennaHeight by viewModel.antennaHeight.collectAsState()
    val favAntennaHeight by viewModel.favoriteAntennaHeight.collectAsState()
    val calParams by viewModel.calibrationParams.collectAsState()

    var antInput by remember(antennaHeight) { mutableStateOf(String.format(Locale.US, "%.3f", antennaHeight)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🌐 대한민국 공공측량 평면직각좌표계 선택", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        KoreanCoordinateSystem.values().forEach { sys ->
            val isSelected = sys == selectedSystem
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color(0xFF1E3A8A) else Color(0xFF1E293B))
                    .clickable { viewModel.setCoordinateSystem(sys, transformExistingPoints = true) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("${sys.displayName} (EPSG:${sys.epsgCode})", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("원점 경도 ${sys.centralMeridianDeg}° / 가산 N=${sys.falseNorthing.toInt()}m, E=${sys.falseEasting.toInt()}m", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                if (isSelected) {
                    Text("✅ 적용중", color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🏔️ KNGeoid18 국가 지오이드 정표고 보정", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("타원체고에서 지오이드고를 차감하여 해발고도(EL.) 산출", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Switch(checked = useKnGeoid18, onCheckedChange = { viewModel.setUseKnGeoid18(it) })
                }

                Divider(color = Color(0xFF334155))

                Text("📏 안테나 폴대 높이 설정", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = antInput,
                        onValueChange = { antInput = it },
                        label = { Text("폴대 높이 (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { antInput.toDoubleOrNull()?.let { viewModel.setAntennaHeight(it) } }
                    ) { Text("적용") }
                    OutlinedButton(
                        onClick = {
                            antInput.toDoubleOrNull()?.let {
                                viewModel.setFavoriteAntennaHeight(it, alsoApplyToCurrent = true)
                            }
                        }
                    ) { Text("⭐ 고정저장(${String.format(Locale.US, "%.2f", favAntennaHeight)}m)", fontSize = 11.sp) }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📐 현장 로컬 좌표 보정 (Site Calibration)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (calParams.enabled)
                        String.format(
                            Locale.US,
                            "적용중: ΔN=%+.3fm, ΔE=%+.3fm, ΔZ=%+.3fm (회전:%.4f°, 축척:%.6f)",
                            calParams.translationN, calParams.translationE, calParams.verticalShiftZ, calParams.rotationDeg, calParams.scaleFactor
                        )
                    else "현재 로컬 보정 미사용 (순수 국가좌표계 사용중)",
                    color = if (calParams.enabled) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// 6. 도면 로드, 수평기포관 ON/OFF, 도면이동 및 현장 특수 도구함 화면 (ToolboxAndDrawingScreen)
// ============================================================================
@Composable
fun ToolboxAndDrawingScreen(
    viewModel: GandanSurveyViewModel,
    onReturnToMap: () -> Unit,
    onOpenVWorldDialog: () -> Unit,
    onOpenDrawingShiftDialog: () -> Unit,
    onOpenLayerDialog: () -> Unit,
    onOpenThreeDViewerDialog: () -> Unit,
    onOpenDesignGradeDialog: () -> Unit,
    onOpenBlackboxDialog: () -> Unit,
    onOpenImuTiltDialog: () -> Unit
) {
    val cadEntities by viewModel.cadEntities.collectAsState()
    val bgMapType by viewModel.backgroundMapType.collectAsState()
    val imuBubbleVisible by viewModel.imuBubbleVisibleOnMap.collectAsState()
    val imuCompEnabled by viewModel.imuTiltCompensationEnabled.collectAsState()
    val pendingSnap by viewModel.pendingSnappedTarget.collectAsState()

    val cadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadExternalCadOrShpUri(uri)
            onReturnToMap()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🛠️ 도면(DXF/DWG/SHP) 및 현장 도구함", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        // 1. 수평기포관(전자기포관) 필요할 때만 켜기 카드 (기본 OFF)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🫧 수평기포관(전자기포관) 화면 표시",
                            color = Color(0xFFFACC15),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "기본값은 꺼짐(OFF)입니다. 정밀 폴대 수평 확인이 필요할 때만 켜서 사용하세요.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = imuBubbleVisible,
                        onCheckedChange = { viewModel.setImuBubbleVisibleOnMap(it) }
                    )
                }

                if (imuBubbleVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("폴대 기울기 IMU 좌표 자동보정 사용", color = Color.White, fontSize = 12.sp)
                        Switch(
                            checked = imuCompEnabled,
                            onCheckedChange = { viewModel.setImuTiltCompensation(it) }
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenImuTiltDialog,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("수평기포관 영점 캘리브레이션 및 허용각도 설정", fontSize = 12.sp)
                    }
                }
            }
        }

        // 2. 외부 CAD 도면 (DWG / DXF / SHP / CSV) 불러오기 및 도면 이동 카드
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "📐 외부 도면 불러오기 (DWG · DXF · SHP · CSV)",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "현재 로드된 도면 객체: ${cadEntities.size}개",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { cadPickerLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📂 도면/좌표 파일 열기", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onOpenLayerDialog,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🗂️ 레이어 켜기/끄기", fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.startInteractiveDrawingShiftFromSnap(pendingSnap)
                            onReturnToMap()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDB2777)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🎯 도면잡고 현황점으로 이동", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onOpenDrawingShiftDialog,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("↔️ 수치 평행이동(ΔN/ΔE)", fontSize = 11.sp)
                    }
                }
            }
        }

        // 3. 배경 위성지도 & V-World 연속지적도 설정 카드
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🗺️ 배경 위성지도 및 V-World 지적도", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BackgroundMapType.values().forEach { type ->
                        val selected = bgMapType == type
                        OutlinedButton(
                            onClick = { viewModel.setBackgroundMapType(type) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) Color(0xFF0284C7) else Color.Transparent,
                                contentColor = Color.White
                            )
                        ) {
                            Text(type.koreanLabel, fontSize = 11.sp)
                        }
                    }
                }
                Button(
                    onClick = onOpenVWorldDialog,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🏛️ V-World 연속지적도 & 지번 불러오기 (화면 중심 / 현위치)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 4. 3D 입체 도면 뷰어 / 굴삭기 절성토 / 블랙박스 복구
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🚜 현장 특수 기능", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenThreeDViewerDialog,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🧊 3D 입체 도면 뷰", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onOpenDesignGradeDialog,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🚜 굴삭기 절성토 설정", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onOpenBlackboxDialog,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🛡️ 블랙박스 자동백업 파일 복구", fontSize = 12.sp)
                }
            }
        }
    }
}

// ============================================================================
// 공통 다이얼로그 모음
// ============================================================================

@Composable
fun ProjectSelectDialog(
    currentProjectName: String,
    savedProjects: List<File>,
    onCreateNew: (String, Boolean) -> Unit,
    onLoadProject: (File) -> Unit,
    onSaveCurrent: () -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📁 프로젝트(현장) 관리", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("새 현장 이름") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { onCreateNew(newName, true) }) { Text("새로생성") }
                }
                Button(onClick = onSaveCurrent, modifier = Modifier.fillMaxWidth()) {
                    Text("💾 현재 프로젝트($currentProjectName) 즉시 저장")
                }
                Divider()
                Text("저장된 프로젝트 파일 목록:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                savedProjects.forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { onLoadProject(file) }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(file.nameWithoutExtension, color = Color.White, fontSize = 12.sp)
                        Text("불러오기", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun MeasureDetailInputDialog(
    initialName: String,
    initialPreset: LastMeasurePreset,
    initialAntenna: Double,
    quickCodes: List<QuickCodePreset>,
    autoOpenPhotoBoard: Boolean,
    onToggleAutoPhoto: (Boolean) -> Unit,
    onConfirmMeasure: (String, String, Int, String, AutoLineMode, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf(initialPreset.code) }
    var epochsStr by remember { mutableStateOf(initialPreset.epochs.toString()) }
    var memo by remember { mutableStateOf(initialPreset.memo) }
    var lineMode by remember { mutableStateOf(initialPreset.autoLineMode) }
    var antStr by remember { mutableStateOf(String.format(Locale.US, "%.3f", initialAntenna)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📍 측점 상세 입력 및 측정", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("점 번호 / 이름") }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("지형 코드 (레이어)") }, singleLine = true)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickCodes.forEach { qc ->
                        SuggestionChip(onClick = { code = qc.code }, label = { Text(qc.code, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(
                    value = antStr,
                    onValueChange = { antStr = it },
                    label = { Text("안테나 폴대 높이 (m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = epochsStr,
                    onValueChange = { epochsStr = it },
                    label = { Text("평균 측정 횟수 (1~30회)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("메모") }, singleLine = true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("측정 직후 현장 사진보드 자동열기", fontSize = 12.sp)
                    Switch(checked = autoOpenPhotoBoard, onCheckedChange = onToggleAutoPhoto)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirmMeasure(
                    name.trim(),
                    code.trim(),
                    epochsStr.toIntOrNull() ?: 1,
                    memo.trim(),
                    lineMode,
                    antStr.toDoubleOrNull() ?: initialAntenna
                )
            }) { Text("측정 저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun DrawingShiftDialog(
    snappedTarget: CadSnapTarget?,
    currentPlane: PlaneCoordinate,
    surveyPoints: List<SurveyPoint>,
    onStartInteractiveMapShift: () -> Unit = {},
    onApplyShift: (Double, Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var dnStr by remember { mutableStateOf("0.000") }
    var deStr by remember { mutableStateOf("0.000") }
    var dzStr by remember { mutableStateOf("0.000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎯 도면·배경 평행이동 맞춤", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartInteractiveMapShift,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDB2777)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🎯 화면에서 도면 모서리 잡고 → 측량 현황점으로 터치 이동",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = "위 버튼을 누르면 도면 화면에서 지적도/CAD 모서리를 스냅해 잡은 뒤, 측량한 포인트를 터치해 배경과 도면을 한꺼번에 옮길 수 있습니다.",
                    color = Color(0xFFFACC15),
                    fontSize = 11.sp
                )

                if (snappedTarget != null) {
                    Text(
                        text = String.format(Locale.US, "선택된 도면점: %s (N:%.3f, E:%.3f)", snappedTarget.label, snappedTarget.northing, snappedTarget.easting),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = {
                            dnStr = String.format(Locale.US, "%.4f", currentPlane.northing - snappedTarget.northing)
                            deStr = String.format(Locale.US, "%.4f", currentPlane.easting - snappedTarget.easting)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("현재 내 GPS 위치로 이동량 자동계산", fontSize = 11.sp)
                    }
                }

                OutlinedTextField(value = dnStr, onValueChange = { dnStr = it }, label = { Text("북쪽 이동량 ΔN (m)") }, singleLine = true)
                OutlinedTextField(value = deStr, onValueChange = { deStr = it }, label = { Text("동쪽 이동량 ΔE (m)") }, singleLine = true)
                OutlinedTextField(value = dzStr, onValueChange = { dzStr = it }, label = { Text("표고 이동량 ΔZ (m)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                onApplyShift(
                    dnStr.toDoubleOrNull() ?: 0.0,
                    deStr.toDoubleOrNull() ?: 0.0,
                    dzStr.toDoubleOrNull() ?: 0.0
                )
            }) { Text("이동 적용") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun VWorldCadastralDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val apiKey by viewModel.vworldApiKey.collectAsState()
    val radius by viewModel.vworldSearchRadiusMeters.collectAsState()
    val statusMsg by viewModel.vworldStatusMessage.collectAsState()

    var keyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var radiusInput by remember(radius) { mutableStateOf(radius.toString()) }
    var includeControlPts by remember { mutableStateOf(true) }
    var registerControlPtsToSurvey by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🏛️ V-World 연속지적도 & 지적도근점 불러오기", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(statusMsg, fontSize = 12.sp, color = Color(0xFF38BDF8))
                OutlinedTextField(
                    value = radiusInput,
                    onValueChange = { radiusInput = it },
                    label = { Text("조회 반경 (m, 50~500m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📐 지적도근점(삼각점·수준점·기준점) 함께 로드", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Checkbox(checked = includeControlPts, onCheckedChange = { includeControlPts = it })
                }
                if (includeControlPts) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📋 불러온 지적도근점을 내 측점/측설 목록에 등록", fontSize = 12.sp)
                        Checkbox(checked = registerControlPtsToSurvey, onCheckedChange = { registerControlPtsToSurvey = it })
                    }
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("V-World 인증키 (기본키 내장)") },
                    singleLine = true
                )

                Button(
                    onClick = {
                        viewModel.setVWorldApiKey(keyInput)
                        val r = radiusInput.toIntOrNull() ?: 120
                        viewModel.setVWorldSearchRadius(r)
                        viewModel.fetchVWorldCadastralMap(
                            radiusMeters = r,
                            useMapCenter = true,
                            replaceExistingCadastral = true,
                            includeControlPoints = includeControlPts,
                            registerControlPointsToSurveyPoints = includeControlPts && registerControlPtsToSurvey,
                            forceSampleMode = false
                        )
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔍 현재 보고 있는 화면 중심 지적도+도근점 로드", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        viewModel.setVWorldApiKey(keyInput)
                        val r = radiusInput.toIntOrNull() ?: 120
                        viewModel.setVWorldSearchRadius(r)
                        viewModel.fetchVWorldCadastralMap(
                            radiusMeters = r,
                            useMapCenter = false,
                            replaceExistingCadastral = true,
                            includeControlPoints = includeControlPts,
                            registerControlPointsToSurveyPoints = includeControlPts && registerControlPtsToSurvey,
                            forceSampleMode = false
                        )
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📍 현재 내 GPS 위치 중심 지적도+도근점 로드", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        viewModel.clearVWorldCadastralEntities()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("화면의 지적도 지우기")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun LayerManagerDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val cadEntities by viewModel.cadEntities.collectAsState()
    val points by viewModel.points.collectAsState()
    val hiddenLayers by viewModel.hiddenLayers.collectAsState()

    val allLayerNames = remember(cadEntities, points) {
        (cadEntities.map { it.layerName } + points.map { it.code }).distinct().sorted()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🗂️ 레이어 켜기/끄기 (${allLayerNames.size}개)", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.setAllLayersVisibility(true) }, modifier = Modifier.weight(1f)) {
                        Text("모두 켜기", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { viewModel.setAllLayersVisibility(false) }, modifier = Modifier.weight(1f)) {
                        Text("모두 끄기", fontSize = 11.sp)
                    }
                }
                allLayerNames.forEach { layerName ->
                    val isVisible = layerName !in hiddenLayers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleLayerVisibility(layerName) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(layerName, fontSize = 13.sp)
                        Checkbox(
                            checked = isVisible,
                            onCheckedChange = { viewModel.toggleLayerVisibility(layerName) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun DisplayConfigDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val cfg by viewModel.mapDisplayConfig.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("👁️ 도면 화면 표시 설정", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("점 이름(번호) 표시")
                    Switch(checked = cfg.showPointName, onCheckedChange = { viewModel.updateMapDisplayConfig(cfg.copy(showPointName = it)) })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("지형 코드 표시")
                    Switch(checked = cfg.showCode, onCheckedChange = { viewModel.updateMapDisplayConfig(cfg.copy(showCode = it)) })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("표고(EL) 수치 표시")
                    Switch(checked = cfg.showElevation, onCheckedChange = { viewModel.updateMapDisplayConfig(cfg.copy(showElevation = it)) })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("CAD 문자(TEXT) 표시")
                    Switch(checked = cfg.showCadText, onCheckedChange = { viewModel.updateMapDisplayConfig(cfg.copy(showCadText = it)) })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("주간 화이트 배경 모드")
                    Switch(checked = cfg.whiteBackground, onCheckedChange = { viewModel.updateMapDisplayConfig(cfg.copy(whiteBackground = it)) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun ImuTiltSettingDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val ant by viewModel.antennaHeight.collectAsState()
    val favAnt by viewModel.favoriteAntennaHeight.collectAsState()
    val bubbleVisible by viewModel.imuBubbleVisibleOnMap.collectAsState()
    val imuEnabled by viewModel.imuTiltCompensationEnabled.collectAsState()
    val maxTilt by viewModel.imuMaxTiltLimitDeg.collectAsState()
    var antStr by remember(ant) { mutableStateOf(String.format(Locale.US, "%.3f", ant)) }
    var favAntStr by remember(favAnt) { mutableStateOf(String.format(Locale.US, "%.3f", favAnt)) }
    var maxTiltStr by remember(maxTilt) { mutableStateOf(String.format(Locale.US, "%.1f", maxTilt)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📏 폴대(안테나) 높이 및 수평기포관 설정", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = antStr,
                    onValueChange = { antStr = it },
                    label = { Text("현재 작업 안테나 폴대 높이 (m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = favAntStr,
                        onValueChange = { favAntStr = it },
                        label = { Text("⭐ 평소 쓰는 기본 안테나고 (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            favAntStr.toDoubleOrNull()?.let {
                                viewModel.setFavoriteAntennaHeight(it, alsoApplyToCurrent = false)
                            }
                        }
                    ) { Text("기본등록", fontSize = 11.sp) }
                }
                Text(
                    text = "※ 현재 안테나고가 평소 쓰는 안테나고(${String.format(Locale.US, "%.3fm", favAnt)})와 다르게 설정되어 있으면 측량 시 확인 경고창이 1회 표시됩니다.",
                    fontSize = 11.sp,
                    color = Color(0xFFFACC15)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🫧 화면에 수평기포관 표시 (기본 OFF)")
                    Switch(checked = bubbleVisible, onCheckedChange = { viewModel.setImuBubbleVisibleOnMap(it) })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("폴대 기울기 IMU 좌표 자동보정")
                    Switch(checked = imuEnabled, onCheckedChange = { viewModel.setImuTiltCompensation(it) })
                }
                OutlinedTextField(
                    value = maxTiltStr,
                    onValueChange = { maxTiltStr = it },
                    label = { Text("수평 경고 기준 각도 (°)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.calibrateImuBubbleZero() }, modifier = Modifier.weight(1f)) {
                        Text("현재 자세 영점잡기", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { viewModel.resetImuBubbleZero() }, modifier = Modifier.weight(1f)) {
                        Text("영점 초기화", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                antStr.toDoubleOrNull()?.let { viewModel.setAntennaHeight(it) }
                maxTiltStr.toDoubleOrNull()?.let { viewModel.setImuMaxTiltLimitDeg(it) }
                onDismiss()
            }) { Text("적용") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun AddressSearchDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val results by viewModel.addressSearchResults.collectAsState()
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔍 지번/도로명 주소로 화면 이동", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("읍/면/동 + 지번 또는 도로명") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { viewModel.searchAddress(query) }) { Text("검색") }
                }
                results.forEach { item: TileMapManager.AddressSearchResult ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .clickable {
                                viewModel.moveMapToAddressResult(item)
                                onDismiss()
                            }
                            .padding(8.dp)
                    ) {
                        Text(item.displayName, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun SeqRangeSelectDialog(
    points: List<SurveyPoint>,
    onSelectIds: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val minSeq = points.minOfOrNull { it.seqNumber } ?: 1
    val maxSeq = points.maxOfOrNull { it.seqNumber } ?: 1
    var startStr by remember { mutableStateOf(minSeq.toString()) }
    var endStr by remember { mutableStateOf(maxSeq.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔢 측점 번호 구간 선택", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("선택할 측점 번호(seqNumber) 시작~끝 구간을 입력하세요.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startStr,
                        onValueChange = { startStr = it },
                        label = { Text("시작 번호") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endStr,
                        onValueChange = { endStr = it },
                        label = { Text("끝 번호") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = startStr.toIntOrNull() ?: minSeq
                val e = endStr.toIntOrNull() ?: maxSeq
                val low = min(s, e)
                val high = max(s, e)
                val matched = points.filter { it.seqNumber in low..high }.map { it.id }.toSet()
                onSelectIds(matched)
            }) { Text("구간 선택") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun BatchEditSurveyPointsDialog(
    selectedCount: Int,
    onApplyBatch: (Boolean, Double, Boolean, Double, Boolean, String, Boolean, String) -> Unit,
    onDismiss: () -> Unit
) {
    var newAntStr by remember { mutableStateOf("") }
    var deltaZStr by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var newMemo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✏️ 선택 측점($selectedCount 개) 일괄 수정", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "새 안테나(폴대) 높이를 입력하면 각 측점의 기록 당시 폴대 높이를 역산하여 표고(EL)가 자동으로 보정됩니다.",
                    fontSize = 12.sp,
                    color = Color(0xFFFACC15)
                )
                OutlinedTextField(
                    value = newAntStr,
                    onValueChange = { newAntStr = it },
                    label = { Text("새 안테나 폴대 높이 m (비워두면 유지)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = deltaZStr,
                    onValueChange = { deltaZStr = it },
                    label = { Text("표고 직접 가감 ΔZ m (예: -0.05)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newCode,
                    onValueChange = { newCode = it },
                    label = { Text("새 지형 코드 (비워두면 유지)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = newMemo,
                    onValueChange = { newMemo = it },
                    label = { Text("새 메모 (비워두면 유지)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val antVal = newAntStr.toDoubleOrNull()
                val dzVal = deltaZStr.toDoubleOrNull()
                onApplyBatch(
                    antVal != null,
                    antVal ?: 1.8,
                    dzVal != null,
                    dzVal ?: 0.0,
                    newCode.trim().isNotEmpty(),
                    newCode.trim(),
                    newMemo.trim().isNotEmpty(),
                    newMemo.trim()
                )
            }) { Text("일괄 적용") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun AddManualStakeoutPointDialog(
    onConfirm: (String, Double, Double, Double, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("ST-1") }
    var nStr by remember { mutableStateOf("") }
    var eStr by remember { mutableStateOf("") }
    var zStr by remember { mutableStateOf("0.0") }
    var code by remember { mutableStateOf("측설점") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("➕ 수동 측설 좌표 입력", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("점 이름") }, singleLine = true)
                OutlinedTextField(value = nStr, onValueChange = { nStr = it }, label = { Text("북쪽 좌표 N(X)") }, singleLine = true)
                OutlinedTextField(value = eStr, onValueChange = { eStr = it }, label = { Text("동쪽 좌표 E(Y)") }, singleLine = true)
                OutlinedTextField(value = zStr, onValueChange = { zStr = it }, label = { Text("표고 EL(Z)") }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("코드") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                val n = nStr.toDoubleOrNull()
                val e = eStr.toDoubleOrNull()
                val z = zStr.toDoubleOrNull() ?: 0.0
                if (n != null && e != null) {
                    onConfirm(name.trim(), n, e, z, code.trim())
                }
            }) { Text("추가 및 측설시작") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun DesignGradeDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val cfg by viewModel.designGradeConfig.collectAsState()
    var mode by remember { mutableStateOf(cfg.mode) }
    var targetZStr by remember { mutableStateOf(cfg.targetElevationZ.toString()) }
    var offsetStr by remember { mutableStateOf(cfg.verticalOffsetMeters.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🚜 굴삭기/토공 실시간 절성토 설정", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DesignGradeMode.values().forEach { m ->
                        OutlinedButton(
                            onClick = { mode = m },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (mode == m) Color(0xFF0284C7) else Color.Transparent,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text(m.shortLabel, fontSize = 11.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = targetZStr,
                    onValueChange = { targetZStr = it },
                    label = { Text("기준 계획고 EL. (m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = offsetStr,
                    onValueChange = { offsetStr = it },
                    label = { Text("수직 여유고 보정 (m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Button(
                    onClick = {
                        viewModel.zeroDesignElevationAtCurrentPosition()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("현재 내 발밑 표고를 기준 계획고(0점)로 설정")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.updateDesignGradeConfig(
                    cfg.copy(
                        mode = mode,
                        targetElevationZ = targetZStr.toDoubleOrNull() ?: cfg.targetElevationZ,
                        verticalOffsetMeters = offsetStr.toDoubleOrNull() ?: 0.0
                    )
                )
                onDismiss()
            }) { Text("적용") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun ThreeDCadViewerDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val points by viewModel.points.collectAsState()
    val cadEntities by viewModel.cadEntities.collectAsState()
    var yawDeg by remember { mutableFloatStateOf(35f) }
    var pitchDeg by remember { mutableFloatStateOf(55f) }
    var zScale by remember { mutableFloatStateOf(2.0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🧊 3D 입체 지형/도면 뷰어 (드래그하여 회전)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = onDismiss) { Text("닫기") }
                }

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                yawDeg = (yawDeg + pan.x * 0.35f) % 360f
                                pitchDeg = (pitchDeg - pan.y * 0.25f).coerceIn(10f, 88f)
                                zScale = (zScale * zoom).coerceIn(0.5f, 15f)
                            }
                        }
                ) {
                    val allN = points.map { it.northing }
                    val allE = points.map { it.easting }
                    val allZ = points.map { it.elevation }
                    val midN = if (allN.isNotEmpty()) allN.average() else 0.0
                    val midE = if (allE.isNotEmpty()) allE.average() else 0.0
                    val midZ = if (allZ.isNotEmpty()) allZ.average() else 0.0
                    val span = max(
                        (allN.maxOrNull() ?: 10.0) - (allN.minOrNull() ?: 0.0),
                        (allE.maxOrNull() ?: 10.0) - (allE.minOrNull() ?: 0.0)
                    ).coerceAtLeast(10.0)
                    val s = (min(size.width, size.height) * 0.38f) / span.toFloat()

                    val yawRad = Math.toRadians(yawDeg.toDouble())
                    val pitchRad = Math.toRadians(pitchDeg.toDouble())

                    fun project3D(n: Double, e: Double, z: Double): Offset {
                        val dx = (e - midE) * s
                        val dy = (n - midN) * s
                        val dz = (z - midZ) * s * zScale
                        val rx = dx * cos(yawRad) - dy * sin(yawRad)
                        val ry = dx * sin(yawRad) + dy * cos(yawRad)
                        val sx = (size.width / 2f + rx).toFloat()
                        val sy = (size.height / 2f - (ry * sin(pitchRad) + dz * cos(pitchRad))).toFloat()
                        return Offset(sx, sy)
                    }

                    cadEntities.take(2500).forEach { ent ->
                        val verts = ent.verticesNE
                        if (verts.size >= 2) {
                            val c = Color(ent.colorHex)
                            for (i in 0 until verts.size - 1) {
                                val p1 = project3D(verts[i].first, verts[i].second, ent.elevationZ)
                                val p2 = project3D(verts[i + 1].first, verts[i + 1].second, ent.elevationZ)
                                drawLine(c, p1, p2, 1.8f)
                            }
                        }
                    }

                    points.forEach { pt ->
                        val p = project3D(pt.northing, pt.easting, pt.elevation)
                        drawCircle(Color(0xFF4ADE80), radius = 6f, center = p)
                    }
                }
            }
        }
    }
}

@Composable
fun BlackboxRecoveryDialog(
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    val files by viewModel.blackboxBackups.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🛡️ 블랙박스 자동백업 복구", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (files.isEmpty()) {
                    Text("저장된 블랙박스 백업 파일이 없습니다.", fontSize = 12.sp)
                } else {
                    files.forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E293B))
                                .clickable {
                                    viewModel.restoreFromBlackboxFile(f)
                                    onDismiss()
                                }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(f.name, color = Color.White, fontSize = 12.sp)
                            Text("복구", color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun BatchAntennaHeightOnlyDialog(
    selectedCount: Int,
    favoriteAntenna: Double,
    onApplyAntennaChange: (Boolean, Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var useRecordedOld by remember { mutableStateOf(true) }
    var manualOldStr by remember { mutableStateOf("2.000") }
    var newAntStr by remember { mutableStateOf(String.format(Locale.US, "%.3f", favoriteAntenna)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📏 선택 측점(${selectedCount}개) 일괄 안테나고 변경", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "폴대(안테나) 높이를 잘못 입력하고 측량한 경우, 새 안테나고를 입력하면 모든 선택 측점의 표고(EL.)가 자동으로 역산 보정됩니다.",
                    fontSize = 12.sp,
                    color = Color(0xFF38BDF8)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("각 측점에 기록된 기존 안테나고 기준 사용", fontSize = 12.sp)
                    Switch(checked = useRecordedOld, onCheckedChange = { useRecordedOld = it })
                }
                if (!useRecordedOld) {
                    OutlinedTextField(
                        value = manualOldStr,
                        onValueChange = { manualOldStr = it },
                        label = { Text("잘못 입력했던 기존 안테나고 (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = newAntStr,
                    onValueChange = { newAntStr = it },
                    label = { Text("변경할 실제(새) 안테나고 (m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedButton(
                    onClick = { newAntStr = String.format(Locale.US, "%.3f", favoriteAntenna) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⭐ 평소 쓰는 기본 안테나고(${String.format(Locale.US, "%.3fm", favoriteAntenna)}) 자동입력", fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val oldH = manualOldStr.toDoubleOrNull() ?: 2.0
                val newH = newAntStr.toDoubleOrNull() ?: favoriteAntenna
                onApplyAntennaChange(useRecordedOld, oldH, newH)
            }) {
                Text("표고 자동역산 및 일괄적용", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun PhotoBoardDialog(
    point: SurveyPoint,
    viewModel: GandanSurveyViewModel,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf(point.memo) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraHighResLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            viewModel.stampBoardPhotoFromHighResCameraUri(point.id, uri, note)
            onDismiss()
        }
    }

    val cameraPreviewFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            viewModel.stampAndAttachBoardPhoto(point.id, bmp, note)
            onDismiss()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.stampBoardPhotoFromGalleryUri(point.id, uri, note)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📸 기점 좌표 · 사진설명 현장 촬영 (${point.name})", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("기점명: #${point.seqNumber} ${point.name} [${point.code}]", color = Color(0xFFFACC15), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            String.format(Locale.US, "N(북): %.4f  E(동): %.4f", point.northing, point.easting),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            String.format(Locale.US, "EL(표고): %.3f m (폴대 %.2fm)", point.elevation, point.antennaHeight),
                            color = Color(0xFF4ADE80),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("사진설명 / 현장 메모 (사진 하단 현황판에 함께 인쇄됨)") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val uri = viewModel.createHighResCameraUri()
                        if (uri != null) {
                            pendingCameraUri = uri
                            try {
                                cameraHighResLauncher.launch(uri)
                            } catch (_: Exception) {
                                cameraPreviewFallbackLauncher.launch(null)
                            }
                        } else {
                            cameraPreviewFallbackLauncher.launch(null)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📸 카메라로 직접 촬영 (좌표·사진설명 자동합성)", fontWeight = FontWeight.ExtraBold)
                }
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🖼️ 갤러리 사진 선택하여 현황판 합성")
                }
                if (point.photoPath.isNotBlank()) {
                    Button(
                        onClick = { viewModel.sharePointBoardPhoto(point) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("💬 촬영된 현황판 사진 카카오톡 공유", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}
