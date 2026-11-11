package kr.koseco.gandansurvey.v90.util

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kr.koseco.gandansurvey.v90.coord.CalibrationSolver
import kr.koseco.gandansurvey.v90.coord.CoordinateConverter
import kr.koseco.gandansurvey.v90.model.CadEntityType
import kr.koseco.gandansurvey.v90.model.CadLayerEntity
import kr.koseco.gandansurvey.v90.model.CadPolyline
import kr.koseco.gandansurvey.v90.model.CalibrationPair
import kr.koseco.gandansurvey.v90.model.DxfEncodingMode
import kr.koseco.gandansurvey.v90.model.ExportColumnConfig
import kr.koseco.gandansurvey.v90.model.FixQuality
import kr.koseco.gandansurvey.v90.model.KoreanCoordinateSystem
import kr.koseco.gandansurvey.v90.model.SiteCalibrationParams
import kr.koseco.gandansurvey.v90.model.SurveyPoint
import kr.koseco.gandansurvey.v90.model.SurveyProjectData
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class SurveyFileShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = getFileForUri(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        for (col in cols) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(if (file.exists()) file.length() else 0L)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase(Locale.US) ?: ""
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".zip") -> "application/zip"
            else -> "application/*"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = getFileForUri(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun getFileForUri(uri: Uri): File {
        val ctx = context ?: throw IllegalStateException("Context is null")
        val fileName = uri.lastPathSegment ?: "shared_file.dxf"
        val shareDir = File(ctx.cacheDir, "shared_exports").apply { mkdirs() }
        return File(shareDir, fileName)
    }
}

object FileIoManager {

    data class ExternalCadParseResult(
        val importedPoints: List<SurveyPoint>,
        val entities: List<CadLayerEntity>,
        val message: String
    )

    private fun getRootSurveyDir(context: Context): File {
        val pubDown = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(pubDown, "간단측량")
        if (!dir.exists()) dir.mkdirs()
        return if (dir.canWrite()) dir else File(context.filesDir, "간단측량").apply { mkdirs() }
    }

    fun writeFileSafelyToDownloadAndInternal(
        context: Context,
        subDir: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream"
    ): File {
        val cleanName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val root = getRootSurveyDir(context)
        val targetDir = if (subDir.isBlank()) root else File(root, subDir).apply { mkdirs() }
        val outFile = File(targetDir, cleanName)
        try {
            outFile.writeBytes(bytes)
        } catch (_: Exception) {
            val fallbackDir = File(context.filesDir, if (subDir.isBlank()) "간단측량" else "간단측량/$subDir").apply { mkdirs() }
            val fallbackFile = File(fallbackDir, cleanName)
            fallbackFile.writeBytes(bytes)
            return fallbackFile
        }
        return outFile
    }

    fun writeBytesToUri(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shareFileExternally(context: Context, file: File, mimeType: String = "application/*"): Boolean {
        return try {
            val shareDir = File(context.cacheDir, "shared_exports").apply { mkdirs() }
            val cachedCopy = File(shareDir, file.name)
            file.copyTo(cachedCopy, overwrite = true)

            val uri = Uri.parse("content://kr.koseco.gandansurvey.v90.fileprovider/shared/${Uri.encode(cachedCopy.name)}")
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true)) "image/jpeg" else "application/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(cachedCopy.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resList = context.packageManager.queryIntentActivities(sendIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resList) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val chooser = Intent.createChooser(sendIntent, "측량 도면/파일 공유 (${file.name})").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * AutoCAD DXF 테이블/레이어 이름 규칙: 공백, 마침표(.), 콤마(,), 슬래시, 괄호 등을 언더바(_)로 치환하여
     * "부적절한 테이블 항목 이름 P_수평6.521 수직1.0" 오류가 절대 발생하지 않도록 보장합니다.
     */
    private fun sanitizeDxfLayerName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\s.<>/\\\\\":;?*|,=`'()\\[\\]{}&+]+"), "_")
            .trim('_')
        return cleaned.ifEmpty { "0" }.take(31)
    }

    fun buildDxfBytes(
        points: List<SurveyPoint>,
        polylines: List<CadPolyline>,
        encodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN,
        exportConfig: ExportColumnConfig = ExportColumnConfig()
    ): ByteArray {
        val sb = StringBuilder()
        val pointMap = points.associateBy { it.id }
        val textH = exportConfig.textHeightMetersDxf.coerceIn(0.1, 20.0)

        val layerNames = mutableSetOf("0", "SURVEY_POINTS", "SURVEY_TEXT", "SURVEY_ELEV", "SURVEY_LINES")
        points.forEach { pt ->
            layerNames.add(sanitizeDxfLayerName("P_" + pt.code.ifBlank { "지반" }))
        }
        polylines.forEach { poly ->
            layerNames.add(sanitizeDxfLayerName("L_" + poly.code.ifBlank { "현황선" }))
        }

        // HEADER
        sb.append("0\r\nSECTION\r\n2\r\nHEADER\r\n9\r\n\$ACADVER\r\n1\r\nAC1009\r\n0\r\nENDSEC\r\n")

        // TABLES (LAYER)
        sb.append("0\r\nSECTION\r\n2\r\nTABLES\r\n0\r\nTABLE\r\n2\r\nLAYER\r\n70\r\n${layerNames.size}\r\n")
        for (lyr in layerNames) {
            val aci = when {
                lyr.startsWith("L_") -> 3 // Green
                lyr.startsWith("P_") -> 4 // Cyan
                lyr == "SURVEY_ELEV" -> 2 // Yellow
                else -> 7
            }
            sb.append("0\r\nLAYER\r\n2\r\n$lyr\r\n70\r\n0\r\n62\r\n$aci\r\n6\r\nCONTINUOUS\r\n")
        }
        sb.append("0\r\nENDTAB\r\n0\r\nENDSEC\r\n")

        // ENTITIES
        sb.append("0\r\nSECTION\r\n2\r\nENTITIES\r\n")

        // 1. Export Polylines as POLYLINE + VERTEX + SEQEND (R12 compatible)
        if (exportConfig.exportPolylineDxf) {
            for (poly in polylines) {
                val pts = poly.pointIds.mapNotNull { pointMap[it] }
                if (pts.size >= 2) {
                    val lyr = sanitizeDxfLayerName("L_" + poly.code.ifBlank { "현황선" })
                    val flag = if (poly.isClosed && pts.size >= 3) 1 else 0
                    sb.append("0\r\nPOLYLINE\r\n8\r\n$lyr\r\n66\r\n1\r\n70\r\n$flag\r\n")
                    for (p in pts) {
                        sb.append(String.format(Locale.US, "0\r\nVERTEX\r\n8\r\n$lyr\r\n10\r\n%.4f\r\n20\r\n%.4f\r\n30\r\n%.4f\r\n", p.easting, p.northing, p.elevation))
                    }
                    sb.append("0\r\nSEQEND\r\n8\r\n$lyr\r\n")
                }
            }
        }

        // 2. Export Points & Labels
        for (pt in points) {
            val lyr = sanitizeDxfLayerName("P_" + pt.code.ifBlank { "지반" })
            val x = pt.easting
            val y = pt.northing
            val z = pt.elevation

            if (exportConfig.exportPointMarkerDxf) {
                sb.append(String.format(Locale.US, "0\r\nPOINT\r\n8\r\n$lyr\r\n10\r\n%.4f\r\n20\r\n%.4f\r\n30\r\n%.4f\r\n", x, y, z))
                val r = (textH * 0.25).coerceAtLeast(0.1)
                sb.append(String.format(Locale.US, "0\r\nCIRCLE\r\n8\r\n$lyr\r\n10\r\n%.4f\r\n20\r\n%.4f\r\n30\r\n%.4f\r\n40\r\n%.4f\r\n", x, y, z, r))
            }

            if (exportConfig.exportPointNameTextDxf || exportConfig.exportCodeTextDxf) {
                val labelParts = mutableListOf<String>()
                if (exportConfig.exportPointNameTextDxf) labelParts.add(pt.name)
                if (exportConfig.exportCodeTextDxf && pt.code.isNotBlank()) labelParts.add("(${pt.code})")
                val labelStr = labelParts.joinToString(" ")
                if (labelStr.isNotBlank()) {
                    sb.append(String.format(Locale.US, "0\r\nTEXT\r\n8\r\nSURVEY_TEXT\r\n10\r\n%.4f\r\n20\r\n%.4f\r\n30\r\n%.4f\r\n40\r\n%.3f\r\n1\r\n%s\r\n",
                        x + textH * 0.35, y + textH * 0.15, z, textH, labelStr))
                }
            }

            if (exportConfig.exportElevationTextDxf) {
                val elevStr = String.format(Locale.US, "%.3f", z)
                sb.append(String.format(Locale.US, "0\r\nTEXT\r\n8\r\nSURVEY_ELEV\r\n10\r\n%.4f\r\n20\r\n%.4f\r\n30\r\n%.4f\r\n40\r\n%.3f\r\n1\r\n%s\r\n",
                    x + textH * 0.35, y - textH * 0.95, z, textH * 0.85, elevStr))
            }
        }

        sb.append("0\r\nENDSEC\r\n0\r\nEOF\r\n")
        val charset = try {
            Charset.forName(encodingMode.charsetName)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        return sb.toString().toByteArray(charset)
    }

    fun exportDxf(
        context: Context,
        projectName: String,
        points: List<SurveyPoint>,
        polylines: List<CadPolyline>,
        encodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN,
        exportConfig: ExportColumnConfig = ExportColumnConfig()
    ): File {
        val bytes = buildDxfBytes(points, polylines, encodingMode, exportConfig)
        return writeFileSafelyToDownloadAndInternal(context, "", "${projectName}.dxf", bytes, "application/dxf")
    }

    fun buildCustomTextOrCsvBytes(
        points: List<SurveyPoint>,
        config: ExportColumnConfig = ExportColumnConfig()
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("﻿") // UTF-8 BOM for Excel Korean compatibility
        sb.appendLine("순번,측번,N(X북방향),E(Y동방향),Z(표고),코드,메모,시각")
        for (p in points) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%d,%s,%.4f,%.4f,%.4f,%s,%s,%s",
                    p.seqNumber, p.name, p.northing, p.easting, p.elevation, p.code, p.memo, p.timestamp
                )
            )
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun exportCustomTextOrCsv(
        context: Context,
        projectName: String,
        points: List<SurveyPoint>,
        config: ExportColumnConfig = ExportColumnConfig()
    ): File {
        val bytes = buildCustomTextOrCsvBytes(points, config)
        return writeFileSafelyToDownloadAndInternal(context, "", "${projectName}.csv", bytes, "text/csv")
    }

    fun buildEltBytes(
        projectName: String,
        system: KoreanCoordinateSystem,
        points: List<SurveyPoint>,
        polylines: List<CadPolyline>
    ): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("#PROJECT=$projectName")
        sb.appendLine("#COORD_SYS=${system.name}")
        for (p in points) {
            sb.appendLine(String.format(Locale.US, "PT,%d,%s,%.4f,%.4f,%.4f,%s,%s",
                p.seqNumber, p.name, p.northing, p.easting, p.elevation, p.code, p.memo))
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildGgaFileBytes(points: List<SurveyPoint>): ByteArray {
        val sb = StringBuilder()
        for (p in points) {
            sb.appendLine(String.format(Locale.US, "%s,%.8f,%.8f,%.3f,%s",
                p.name, p.latitude, p.longitude, p.ellipsoidalHeight, p.fixQuality.name))
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildWgsFileBytes(points: List<SurveyPoint>): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("No,Name,Latitude,Longitude,EllipsoidalHeight,ElevationZ,Code")
        for (p in points) {
            sb.appendLine(String.format(Locale.US, "%d,%s,%.8f,%.8f,%.4f,%.4f,%s",
                p.seqNumber, p.name, p.latitude, p.longitude, p.ellipsoidalHeight, p.elevation, p.code))
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildKmlBytes(projectName: String, points: List<SurveyPoint>): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>$projectName</name>")
        for (p in points) {
            if (p.latitude != 0.0 && p.longitude != 0.0) {
                sb.appendLine("<Placemark><name>${p.name} (${p.code})</name>")
                sb.appendLine("<Point><coordinates>${p.longitude},${p.latitude},${p.elevation}</coordinates></Point></Placemark>")
            }
        }
        sb.appendLine("</Document></kml>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun exportAllFormatsBundle(
        context: Context,
        projectName: String,
        system: KoreanCoordinateSystem,
        points: List<SurveyPoint>,
        polylines: List<CadPolyline>,
        encodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN,
        exportConfig: ExportColumnConfig = ExportColumnConfig()
    ): File {
        val sub = "${projectName}_전체내보내기"
        val dxf = writeFileSafelyToDownloadAndInternal(context, sub, "${projectName}.dxf", buildDxfBytes(points, polylines, encodingMode, exportConfig))
        writeFileSafelyToDownloadAndInternal(context, sub, "${projectName}.csv", buildCustomTextOrCsvBytes(points, exportConfig))
        writeFileSafelyToDownloadAndInternal(context, sub, "${projectName}.elt", buildEltBytes(projectName, system, points, polylines))
        writeFileSafelyToDownloadAndInternal(context, sub, "${projectName}.wgs", buildWgsFileBytes(points))
        writeFileSafelyToDownloadAndInternal(context, sub, "${projectName}.kml", buildKmlBytes(projectName, points))
        return dxf
    }

    fun exportFullProjectZipBundle(
        context: Context,
        projectName: String,
        system: KoreanCoordinateSystem,
        points: List<SurveyPoint>,
        polylines: List<CadPolyline>,
        encodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN,
        exportConfig: ExportColumnConfig = ExportColumnConfig()
    ): File {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun addEntry(name: String, data: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
            addEntry("${projectName}.dxf", buildDxfBytes(points, polylines, encodingMode, exportConfig))
            addEntry("${projectName}.csv", buildCustomTextOrCsvBytes(points, exportConfig))
            addEntry("${projectName}.elt", buildEltBytes(projectName, system, points, polylines))
            addEntry("${projectName}.wgs", buildWgsFileBytes(points))
            addEntry("${projectName}.kml", buildKmlBytes(projectName, points))

            points.forEach { pt ->
                if (pt.photoPath.isNotBlank()) {
                    val pFile = File(pt.photoPath)
                    if (pFile.exists()) {
                        addEntry("현장사진/${pFile.name}", pFile.readBytes())
                    }
                }
            }
        }
        return writeFileSafelyToDownloadAndInternal(context, "", "${projectName}_통합패키지.zip", baos.toByteArray(), "application/zip")
    }

    // =========================================================================
    // EXTERNAL DXF / DWG / CSV PARSER (with BLOCKS/PaperSpace filter & Layer Colors)
    // =========================================================================

    fun aciToColorHex(aci: Int): Long {
        val clean = abs(aci) % 256
        return when (clean) {
            1 -> 0xFFEF4444 // Red
            2 -> 0xFFFACC15 // Yellow
            3 -> 0xFF22C55E // Green
            4 -> 0xFF06B6D4 // Cyan
            5 -> 0xFF3B82F6 // Blue
            6 -> 0xFFEC4899 // Magenta
            7 -> 0xFFE2E8F0 // White/Light Gray
            8 -> 0xFF94A3B8 // Gray
            9 -> 0xFFCBD5E1
            in 10..19 -> 0xFFF87171
            in 20..39 -> 0xFFFB923C
            in 40..69 -> 0xFFFDE047
            in 70..109 -> 0xFF4ADE80
            in 110..149 -> 0xFF2DD4BF
            in 150..189 -> 0xFF60A5FA
            in 190..229 -> 0xFFC084FC
            in 230..249 -> 0xFFF472B6
            else -> 0xFF38BDF8
        }
    }

    fun parseExternalCadBytes(
        bytes: ByteArray,
        fileName: String,
        startSeq: Int = 1
    ): ExternalCadParseResult {
        val lower = fileName.lowercase(Locale.US)
        if (lower.endsWith(".dwg") || (bytes.size >= 6 && bytes[0] == 'A'.code.toByte() && bytes[1] == 'C'.code.toByte() && bytes[2] == '1'.code.toByte())) {
            return DwgFileParser.parseDwgBinary(bytes, fileName)
        }

        val text = try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.contains("�")) String(bytes, Charset.forName("EUC-KR")) else utf8
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }

        val lines = text.lines()
        val pairs = mutableListOf<Pair<Int, String>>()
        var idx = 0
        while (idx + 1 < lines.size) {
            val code = lines[idx].trim().toIntOrNull()
            if (code != null) {
                pairs.add(Pair(code, lines[idx + 1].trim()))
                idx += 2
            } else {
                idx += 1
            }
        }

        // 1. Parse TABLES -> LAYER for layer colors
        val layerColorMap = mutableMapOf<String, Long>()
        var inTables = false
        var inLayerTable = false
        var curLayerName = ""
        var curLayerAci = 7
        var curLayerTrueColor: Long? = null

        for ((code, value) in pairs) {
            if (code == 0 && value.equals("SECTION", true)) continue
            if (code == 2 && value.equals("TABLES", true)) { inTables = true; continue }
            if (inTables && code == 0 && value.equals("ENDSEC", true)) {
                if (curLayerName.isNotBlank()) {
                    layerColorMap[curLayerName] = curLayerTrueColor ?: aciToColorHex(curLayerAci)
                }
                inTables = false
                inLayerTable = false
                continue
            }
            if (inTables) {
                if (code == 0 && value.equals("LAYER", true)) {
                    if (curLayerName.isNotBlank()) {
                        layerColorMap[curLayerName] = curLayerTrueColor ?: aciToColorHex(curLayerAci)
                    }
                    inLayerTable = true
                    curLayerName = ""
                    curLayerAci = 7
                    curLayerTrueColor = null
                } else if (code == 0 && value.equals("ENDTAB", true)) {
                    if (curLayerName.isNotBlank()) {
                        layerColorMap[curLayerName] = curLayerTrueColor ?: aciToColorHex(curLayerAci)
                    }
                    inLayerTable = false
                } else if (inLayerTable) {
                    when (code) {
                        2 -> curLayerName = value
                        62 -> curLayerAci = value.toIntOrNull() ?: 7
                        420 -> {
                            val rgb = value.toLongOrNull()
                            if (rgb != null) curLayerTrueColor = 0xFF000000L or (rgb and 0xFFFFFFL)
                        }
                    }
                }
            }
        }

        // 2. Parse ONLY ENTITIES section (strictly skip BLOCKS section & Paper Space 67=1 & DEFPOINTS!)
        val entities = mutableListOf<CadLayerEntity>()
        var inEntitiesSection = false
        var pIdx = 0
        while (pIdx < pairs.size) {
            val (c, v) = pairs[pIdx]
            if (c == 0 && v.equals("SECTION", true)) {
                val nextPair = pairs.getOrNull(pIdx + 1)
                inEntitiesSection = (nextPair?.first == 2 && nextPair.second.equals("ENTITIES", true))
                pIdx += 2
                continue
            }
            if (c == 0 && v.equals("ENDSEC", true)) {
                inEntitiesSection = false
                pIdx++
                continue
            }
            if (!inEntitiesSection) {
                pIdx++
                continue
            }

            if (c == 0) {
                val entType = v.uppercase(Locale.US)
                pIdx++
                var layer = "0"
                var paperSpace = 0
                var aciColor: Int? = null
                var trueColor: Long? = null
                var x1 = 0.0; var y1 = 0.0; var z1 = 0.0
                var x2 = 0.0; var y2 = 0.0
                var radius = 0.0; var startAngle = 0.0; var endAngle = 360.0
                var isClosed = false
                var textVal = ""
                val lwVerts = mutableListOf<Triple<Double, Double, Double>>() // x, y, bulge
                var curLwX: Double? = null
                var curLwY: Double? = null
                var curBulge = 0.0

                if (entType == "POLYLINE") {
                    while (pIdx < pairs.size && pairs[pIdx].first != 0) {
                        val (gc, gv) = pairs[pIdx]
                        when (gc) {
                            8 -> layer = gv
                            67 -> paperSpace = gv.toIntOrNull() ?: 0
                            62 -> aciColor = gv.toIntOrNull()
                            420 -> gv.toLongOrNull()?.let { trueColor = 0xFF000000L or (it and 0xFFFFFFL) }
                            70 -> isClosed = ((gv.toIntOrNull() ?: 0) and 1) != 0
                        }
                        pIdx++
                    }
                    val polyVerts = mutableListOf<Pair<Double, Double>>()
                    while (pIdx < pairs.size) {
                        val (vc, vv) = pairs[pIdx]
                        if (vc == 0 && vv.equals("VERTEX", true)) {
                            pIdx++
                            var vx = 0.0; var vy = 0.0; var vFlag = 0
                            while (pIdx < pairs.size && pairs[pIdx].first != 0) {
                                val (gc, gv) = pairs[pIdx]
                                when (gc) {
                                    10 -> vx = gv.toDoubleOrNull() ?: 0.0
                                    20 -> vy = gv.toDoubleOrNull() ?: 0.0
                                    70 -> vFlag = gv.toIntOrNull() ?: 0
                                }
                                pIdx++
                            }
                            if ((vFlag and 16) == 0) {
                                polyVerts.add(Pair(vy, vx))
                            }
                        } else if (vc == 0 && vv.equals("SEQEND", true)) {
                            pIdx++
                            while (pIdx < pairs.size && pairs[pIdx].first != 0) pIdx++
                            break
                        } else {
                            break
                        }
                    }
                    if (paperSpace == 0 && !layer.equals("DEFPOINTS", true) && polyVerts.size >= 2) {
                        val col = resolveEntityColor(layer, aciColor, trueColor, layerColorMap)
                        entities.add(CadLayerEntity(layerName = layer, type = CadEntityType.POLYLINE, verticesNE = polyVerts, isClosed = isClosed, colorHex = col, sourceType = "DXF"))
                    }
                    continue
                }

                while (pIdx < pairs.size && pairs[pIdx].first != 0) {
                    val (gc, gv) = pairs[pIdx]
                    when (gc) {
                        8 -> layer = gv
                        67 -> paperSpace = gv.toIntOrNull() ?: 0
                        62 -> aciColor = gv.toIntOrNull()
                        420 -> gv.toLongOrNull()?.let { trueColor = 0xFF000000L or (it and 0xFFFFFFL) }
                        10 -> {
                            val d = gv.toDoubleOrNull() ?: 0.0
                            if (entType == "LWPOLYLINE") {
                                if (curLwX != null && curLwY != null) {
                                    lwVerts.add(Triple(curLwX!!, curLwY!!, curBulge))
                                    curBulge = 0.0
                                }
                                curLwX = d
                            } else x1 = d
                        }
                        20 -> {
                            val d = gv.toDoubleOrNull() ?: 0.0
                            if (entType == "LWPOLYLINE") curLwY = d else y1 = d
                        }
                        30 -> z1 = gv.toDoubleOrNull() ?: 0.0
                        11 -> x2 = gv.toDoubleOrNull() ?: 0.0
                        21 -> y2 = gv.toDoubleOrNull() ?: 0.0
                        40 -> radius = gv.toDoubleOrNull() ?: 0.0
                        42 -> if (entType == "LWPOLYLINE") curBulge = gv.toDoubleOrNull() ?: 0.0
                        50 -> startAngle = gv.toDoubleOrNull() ?: 0.0
                        51 -> endAngle = gv.toDoubleOrNull() ?: 360.0
                        70 -> if (entType == "LWPOLYLINE") isClosed = ((gv.toIntOrNull() ?: 0) and 1) != 0
                        1 -> textVal = cleanDxfMText(gv)
                    }
                    pIdx++
                }
                if (entType == "LWPOLYLINE" && curLwX != null && curLwY != null) {
                    lwVerts.add(Triple(curLwX!!, curLwY!!, curBulge))
                }

                if (paperSpace != 0 || layer.equals("DEFPOINTS", true)) continue
                val col = resolveEntityColor(layer, aciColor, trueColor, layerColorMap)

                when (entType) {
                    "LINE" -> {
                        entities.add(
                            CadLayerEntity(
                                layerName = layer,
                                type = CadEntityType.LINE,
                                verticesNE = listOf(Pair(y1, x1), Pair(y2, x2)),
                                elevationZ = z1,
                                colorHex = col,
                                sourceType = "DXF"
                            )
                        )
                    }
                    "LWPOLYLINE" -> {
                        if (lwVerts.size >= 2) {
                            val expanded = interpolateBulgeVertices(lwVerts, isClosed)
                            entities.add(
                                CadLayerEntity(
                                    layerName = layer,
                                    type = CadEntityType.POLYLINE,
                                    verticesNE = expanded,
                                    elevationZ = z1,
                                    isClosed = isClosed,
                                    colorHex = col,
                                    sourceType = "DXF"
                                )
                            )
                        }
                    }
                    "CIRCLE" -> {
                        if (radius > 0.001) {
                            val circlePts = (0..24).map { step ->
                                val rad = Math.toRadians(step * 15.0)
                                Pair(y1 + radius * sin(rad), x1 + radius * cos(rad))
                            }
                            entities.add(
                                CadLayerEntity(
                                    layerName = layer,
                                    type = CadEntityType.CIRCLE,
                                    verticesNE = circlePts,
                                    elevationZ = z1,
                                    isClosed = true,
                                    colorHex = col,
                                    sourceType = "DXF"
                                )
                            )
                        }
                    }
                    "ARC" -> {
                        if (radius > 0.001) {
                            var sweep = endAngle - startAngle
                            while (sweep <= 0.0) sweep += 360.0
                            val steps = (sweep / 12.0).toInt().coerceIn(4, 30)
                            val arcPts = (0..steps).map { s ->
                                val ang = Math.toRadians(startAngle + sweep * (s.toDouble() / steps))
                                Pair(y1 + radius * sin(ang), x1 + radius * cos(ang))
                            }
                            entities.add(
                                CadLayerEntity(
                                    layerName = layer,
                                    type = CadEntityType.ARC,
                                    verticesNE = arcPts,
                                    elevationZ = z1,
                                    isClosed = false,
                                    colorHex = col,
                                    sourceType = "DXF"
                                )
                            )
                        }
                    }
                    "POINT" -> {
                        entities.add(
                            CadLayerEntity(
                                layerName = layer,
                                type = CadEntityType.POINT,
                                verticesNE = listOf(Pair(y1, x1)),
                                elevationZ = z1,
                                colorHex = col,
                                sourceType = "DXF"
                            )
                        )
                    }
                    "TEXT", "MTEXT" -> {
                        if (textVal.isNotBlank()) {
                            entities.add(
                                CadLayerEntity(
                                    layerName = layer,
                                    type = CadEntityType.TEXT,
                                    verticesNE = listOf(Pair(y1, x1)),
                                    elevationZ = z1,
                                    colorHex = col,
                                    textLabel = textVal,
                                    sourceType = "DXF"
                                )
                            )
                        }
                    }
                }
            } else {
                pIdx++
            }
        }

        val layerCount = entities.map { it.layerName }.distinct().size
        return ExternalCadParseResult(
            importedPoints = emptyList(),
            entities = entities,
            message = "DXF 도면 로드 완료: 객체 ${entities.size}개 (레이어 ${layerCount}개 색상 반영)"
        )
    }

    private fun resolveEntityColor(
        layer: String,
        aciColor: Int?,
        trueColor: Long?,
        layerColorMap: Map<String, Long>
    ): Long {
        if (trueColor != null) return trueColor
        if (aciColor != null && aciColor != 256 && aciColor != 0) {
            return aciToColorHex(aciColor)
        }
        return layerColorMap[layer] ?: aciToColorHex((layer.hashCode() and 0x7FFFFFFF) % 7 + 1)
    }

    private fun cleanDxfMText(raw: String): String {
        return raw
            .replace(Regex("\\\\[A-Za-z][^;]*;"), "")
            .replace("\\P", " ")
            .replace("{", "")
            .replace("}", "")
            .trim()
    }

    private fun interpolateBulgeVertices(
        verts: List<Triple<Double, Double, Double>>,
        isClosed: Boolean
    ): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        val count = if (isClosed) verts.size else verts.size - 1
        for (i in 0 until count) {
            val (x1, y1, bulge) = verts[i]
            val (x2, y2, _) = verts[(i + 1) % verts.size]
            out.add(Pair(y1, x1))
            if (abs(bulge) > 0.01) {
                val chord = hypot(x2 - x1, y2 - y1)
                if (chord > 1e-4) {
                    val theta = 4.0 * kotlin.math.atan(bulge)
                    val radius = chord / (2.0 * sin(abs(theta) / 2.0))
                    val midX = (x1 + x2) * 0.5
                    val midY = (y1 + y2) * 0.5
                    val d = radius * cos(abs(theta) / 2.0)
                    val sign = if (bulge > 0) 1.0 else -1.0
                    val nx = -(y2 - y1) / chord * sign
                    val ny = (x2 - x1) / chord * sign
                    val cx = midX + nx * d * (if (abs(theta) <= Math.PI) 1.0 else -1.0)
                    val cy = midY + ny * d * (if (abs(theta) <= Math.PI) 1.0 else -1.0)
                    val startAng = atan2(y1 - cy, x1 - cx)
                    val steps = 8
                    for (s in 1 until steps) {
                        val a = startAng + theta * (s.toDouble() / steps)
                        out.add(Pair(cy + radius * sin(a), cx + radius * cos(a)))
                    }
                }
            }
        }
        if (!isClosed && verts.isNotEmpty()) {
            val last = verts.last()
            out.add(Pair(last.second, last.first))
        }
        return out
    }

    fun parseCsvOrTxtPoints(text: String, startSeq: Int): List<SurveyPoint> {
        val list = mutableListOf<SurveyPoint>()
        var seq = startSeq
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        for (line in text.lines()) {
            val clean = line.trim().removePrefix("﻿")
            if (clean.isEmpty() || clean.startsWith("#") || clean.startsWith("순번") || clean.startsWith("No", true)) continue
            val parts = clean.split(Regex("[,\t;]+")).map { it.trim() }
            if (parts.size >= 3) {
                val nums = parts.mapNotNull { it.toDoubleOrNull() }
                if (nums.size >= 2) {
                    val nVal = if (parts.size >= 4 && parts[2].toDoubleOrNull() != null) parts[2].toDouble() else nums[0]
                    val eVal = if (parts.size >= 4 && parts[3].toDoubleOrNull() != null) parts[3].toDouble() else nums[1]
                    val zVal = if (parts.size >= 5 && parts[4].toDoubleOrNull() != null) parts[4].toDouble() else (nums.getOrNull(2) ?: 0.0)
                    val nameStr = if (parts[0].toDoubleOrNull() == null) parts[0] else (parts.getOrNull(1) ?: "$seq")
                    val codeStr = parts.getOrNull(5) ?: "측설점"
                    list.add(
                        SurveyPoint(
                            seqNumber = seq++,
                            name = nameStr,
                            code = codeStr,
                            northing = nVal,
                            easting = eVal,
                            elevation = zVal,
                            timestamp = stamp
                        )
                    )
                }
            }
        }
        return list
    }

    // =========================================================================
    // PROJECT SAVE / LOAD & BLACKBOX AUTO-RECOVERY
    // =========================================================================

    fun buildProjectJsonBytes(project: SurveyProjectData): ByteArray {
        val root = JSONObject()
        root.put("projectName", project.projectName)
        root.put("coordinateSystem", project.coordinateSystem.name)
        root.put("createdAt", project.createdAt)

        val ptArr = JSONArray()
        for (p in project.points) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("seqNumber", p.seqNumber)
            o.put("name", p.name)
            o.put("code", p.code)
            o.put("northing", p.northing)
            o.put("easting", p.easting)
            o.put("elevation", p.elevation)
            o.put("latitude", p.latitude)
            o.put("longitude", p.longitude)
            o.put("ellipsoidalHeight", p.ellipsoidalHeight)
            o.put("geoidSeparation", p.geoidSeparation)
            o.put("antennaHeight", p.antennaHeight)
            o.put("fixQuality", p.fixQuality.name)
            o.put("hrms", p.hrms)
            o.put("vrms", p.vrms)
            o.put("epochCount", p.epochCount)
            o.put("timestamp", p.timestamp)
            o.put("memo", p.memo)
            o.put("photoPath", p.photoPath)
            ptArr.put(o)
        }
        root.put("points", ptArr)

        val polyArr = JSONArray()
        for (f in project.figures) {
            val o = JSONObject()
            o.put("id", f.id)
            o.put("code", f.code)
            o.put("isClosed", f.isClosed)
            o.put("colorHex", f.colorHex)
            val ids = JSONArray()
            f.pointIds.forEach { ids.put(it) }
            o.put("pointIds", ids)
            polyArr.put(o)
        }
        root.put("figures", polyArr)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun saveProjectFile(context: Context, project: SurveyProjectData): File {
        val bytes = buildProjectJsonBytes(project)
        val internalDir = File(context.filesDir, "projects").apply { mkdirs() }
        val internalFile = File(internalDir, "${project.projectName}.gsproj")
        internalFile.writeBytes(bytes)
        writeFileSafelyToDownloadAndInternal(context, "프로젝트", "${project.projectName}.gsproj", bytes)
        return internalFile
    }

    fun listSavedProjects(context: Context): List<File> {
        val internalDir = File(context.filesDir, "projects").apply { mkdirs() }
        return internalDir.listFiles { f -> f.extension.equals("gsproj", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun loadProjectFromFile(file: File): SurveyProjectData? {
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val name = root.optString("projectName", file.nameWithoutExtension)
            val sysName = root.optString("coordinateSystem", KoreanCoordinateSystem.CENTRAL_GRS80.name)
            val sys = try { KoreanCoordinateSystem.valueOf(sysName) } catch (_: Exception) { KoreanCoordinateSystem.CENTRAL_GRS80 }
            val pts = mutableListOf<SurveyPoint>()
            val ptArr = root.optJSONArray("points")
            if (ptArr != null) {
                for (i in 0 until ptArr.length()) {
                    val o = ptArr.getJSONObject(i)
                    val fq = try { FixQuality.valueOf(o.optString("fixQuality", "RTK_FIXED")) } catch (_: Exception) { FixQuality.RTK_FIXED }
                    pts.add(
                        SurveyPoint(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            seqNumber = o.optInt("seqNumber", i + 1),
                            name = o.optString("name", "${i + 1}"),
                            code = o.optString("code", "지반"),
                            northing = o.optDouble("northing", 0.0),
                            easting = o.optDouble("easting", 0.0),
                            elevation = o.optDouble("elevation", 0.0),
                            latitude = o.optDouble("latitude", 0.0),
                            longitude = o.optDouble("longitude", 0.0),
                            ellipsoidalHeight = o.optDouble("ellipsoidalHeight", 0.0),
                            geoidSeparation = o.optDouble("geoidSeparation", 0.0),
                            antennaHeight = o.optDouble("antennaHeight", 1.800),
                            fixQuality = fq,
                            hrms = o.optDouble("hrms", 0.01),
                            vrms = o.optDouble("vrms", 0.015),
                            epochCount = o.optInt("epochCount", 1),
                            timestamp = o.optString("timestamp", ""),
                            memo = o.optString("memo", ""),
                            photoPath = o.optString("photoPath", "")
                        )
                    )
                }
            }
            val polys = mutableListOf<CadPolyline>()
            val polyArr = root.optJSONArray("figures")
            if (polyArr != null) {
                for (i in 0 until polyArr.length()) {
                    val o = polyArr.getJSONObject(i)
                    val idList = mutableListOf<String>()
                    val ids = o.optJSONArray("pointIds")
                    if (ids != null) {
                        for (k in 0 until ids.length()) idList.add(ids.getString(k))
                    }
                    polys.add(
                        CadPolyline(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            code = o.optString("code", "현황선"),
                            pointIds = idList,
                            isClosed = o.optBoolean("isClosed", false),
                            colorHex = o.optLong("colorHex", 0xFF38BDF8)
                        )
                    )
                }
            }
            SurveyProjectData(
                projectName = name,
                coordinateSystem = sys,
                createdAt = root.optString("createdAt", ""),
                points = pts,
                figures = polys,
                cadEntities = emptyList()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveBlackboxSnapshot(context: Context, project: SurveyProjectData, actionLabel: String) {
        try {
            val dir = File(context.filesDir, "blackbox").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
            val cleanAct = actionLabel.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
            val file = File(dir, "BB_${stamp}_${cleanAct}.gsproj")
            file.writeBytes(buildProjectJsonBytes(project))
            val all = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            if (all.size > 15) {
                all.drop(15).forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    fun listBlackboxBackups(context: Context): List<File> {
        val dir = File(context.filesDir, "blackbox").apply { mkdirs() }
        return dir.listFiles { f -> f.extension.equals("gsproj", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun loadLatestBlackboxSnapshot(context: Context): SurveyProjectData? {
        val latest = listBlackboxBackups(context).firstOrNull() ?: return null
        return loadProjectFromFile(latest)
    }

    fun saveTrfFile(
        context: Context,
        trfFileName: String,
        system: KoreanCoordinateSystem,
        params: SiteCalibrationParams,
        pairs: List<CalibrationPair>
    ): File {
        val content = CalibrationSolver.buildTrfFileContent(system, params, pairs)
        val dir = File(context.filesDir, "trf").apply { mkdirs() }
        val file = File(dir, "${trfFileName}.trf")
        file.writeText(content, Charsets.UTF_8)
        writeFileSafelyToDownloadAndInternal(context, "좌표계보정", "${trfFileName}.trf", content.toByteArray(Charsets.UTF_8))
        return file
    }

    fun listSavedTrfFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "trf").apply { mkdirs() }
        return dir.listFiles { f -> f.extension.equals("trf", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun parseTrfFile(
        content: String,
        system: KoreanCoordinateSystem,
        useKnGeoid18: Boolean
    ): List<CalibrationPair> {
        val pairs = mutableListOf<CalibrationPair>()
        for (line in content.lines()) {
            val l = line.trim()
            if (l.startsWith("PAIR=")) {
                val p = l.removePrefix("PAIR=").split(",")
                if (p.size >= 7) {
                    pairs.add(
                        CalibrationPair(
                            pointName = p[0],
                            knownN = p[1].toDoubleOrNull() ?: 0.0,
                            knownE = p[2].toDoubleOrNull() ?: 0.0,
                            knownZ = p[3].toDoubleOrNull() ?: 0.0,
                            measuredN = p[4].toDoubleOrNull() ?: 0.0,
                            measuredE = p[5].toDoubleOrNull() ?: 0.0,
                            measuredZ = p[6].toDoubleOrNull() ?: 0.0,
                            measuredLat = p.getOrNull(7)?.toDoubleOrNull() ?: 0.0,
                            measuredLon = p.getOrNull(8)?.toDoubleOrNull() ?: 0.0,
                            measuredEllipH = p.getOrNull(9)?.toDoubleOrNull() ?: 0.0,
                            enabled = p.getOrNull(10)?.toBooleanStrictOrNull() ?: true
                        )
                    )
                }
            }
        }
        return pairs
    }

    // =========================================================================
    // FIELD BOARD WATERMARK PHOTO COMPOSER
    // =========================================================================

    fun createHighResCameraCaptureUri(context: Context): Uri? {
        return try {
            val dir = File(context.cacheDir, "shared_exports").apply { mkdirs() }
            val tempFile = File(dir, "cam_capture_${System.currentTimeMillis()}.jpg")
            Uri.parse("content://kr.koseco.gandansurvey.v90.fileprovider/shared/${Uri.encode(tempFile.name)}")
        } catch (_: Exception) {
            null
        }
    }

    fun decodeHighResBitmapFromUri(context: Context, uri: Uri, deleteTempUriAfterRead: Boolean = false): Bitmap? {
        return try {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun composeAndSaveBoardPhoto(
        context: Context,
        rawBitmap: Bitmap?,
        projectName: String,
        point: SurveyPoint,
        coordinateSystem: KoreanCoordinateSystem,
        azimuthDeg: Float,
        customNote: String
    ): File {
        val w = rawBitmap?.width?.coerceAtLeast(1080) ?: 1280
        val h = rawBitmap?.height?.coerceAtLeast(800) ?: 960
        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)

        if (rawBitmap != null) {
            canvas.drawBitmap(rawBitmap, 0f, 0f, null)
        } else {
            canvas.drawColor(Color.parseColor("#0F172A"))
        }

        val boardW = (w * 0.56f).coerceAtLeast(480f)
        val boardH = (h * 0.30f).coerceAtLeast(240f)
        val left = 28f
        val top = h - boardH - 28f
        val rect = RectF(left, top, left + boardW, top + boardH)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 15, 23, 42)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRoundRect(rect, 18f, 18f, bgPaint)
        canvas.drawRoundRect(rect, 18f, 18f, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (boardH * 0.115f).coerceIn(22f, 42f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val lines = listOf(
            "현장명: $projectName",
            "측  점: #${point.seqNumber} ${point.name} [${point.code}]",
            String.format(Locale.US, "N(북): %.4f  E(동): %.4f", point.northing, point.easting),
            String.format(Locale.US, "Z(표고): %.3fm (폴대 %.2fm)", point.elevation, point.antennaHeight),
            "메  모: ${customNote.ifBlank { point.memo.ifBlank { point.timestamp } }}"
        )
        var yPos = top + textPaint.textSize * 1.35f
        for (ln in lines) {
            canvas.drawText(ln, left + 22f, yPos, textPaint)
            yPos += textPaint.textSize * 1.42f
        }

        val baos = ByteArrayOutputStream()
        outBmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        val fileName = "${projectName}_#${point.seqNumber}_${point.name}.jpg"
        return writeFileSafelyToDownloadAndInternal(context, "현장사진", fileName, baos.toByteArray(), "image/jpeg")
    }
}
