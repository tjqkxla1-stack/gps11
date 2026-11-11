package kr.koseco.gandansurvey.v90.util

import kr.koseco.gandansurvey.v90.model.CadEntityType
import kr.koseco.gandansurvey.v90.model.CadLayerEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object DwgFileParser {

    fun parseDwgBinary(bytes: ByteArray, fileName: String): FileIoManager.ExternalCadParseResult {
        if (bytes.size < 32) {
            return FileIoManager.ExternalCadParseResult(emptyList(), emptyList(), "DWG 파일 크기가 너무 작습니다.")
        }
        val versionHeader = String(bytes, 0, minOf(6, bytes.size), Charsets.US_ASCII)
        // Extract IEEE-754 double coordinate pairs in Korea TM or local coordinate range from DWG stream
        val entities = mutableListOf<CadLayerEntity>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val limit = bytes.size - 24
        var idx = 32
        val tmSegments = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()

        while (idx < limit && tmSegments.size < 6000) {
            val x1 = buf.getDouble(idx)
            val y1 = buf.getDouble(idx + 8)
            val x2 = buf.getDouble(idx + 16)
            val y2 = if (idx + 32 <= bytes.size) buf.getDouble(idx + 24) else 0.0

            val isTm1 = x1 in 50000.0..450000.0 && y1 in 100000.0..750000.0
            val isTm2 = x2 in 50000.0..450000.0 && y2 in 100000.0..750000.0
            if (isTm1 && isTm2) {
                val dist = hypot(y2 - y1, x2 - x1)
                if (dist in 0.05..2000.0) {
                    tmSegments.add(Pair(Pair(y1, x1), Pair(y2, x2)))
                    idx += 32
                    continue
                }
            }
            idx += 2
        }

        tmSegments.forEachIndexed { i, (p1, p2) ->
            entities.add(
                CadLayerEntity(
                    layerName = "DWG_도면",
                    type = CadEntityType.LINE,
                    verticesNE = listOf(p1, p2),
                    colorHex = if (i % 3 == 0) 0xFF38BDF8 else if (i % 3 == 1) 0xFF22C55E else 0xFFFACC15,
                    sourceType = "DWG"
                )
            )
        }

        val msg = if (entities.isNotEmpty()) {
            "DWG($versionHeader) 도면 객체 ${entities.size}개 로드 완료"
        } else {
            "DWG($versionHeader) 압축 포맷입니다. AutoCAD에서 DXF(2004~2018)로 저장하면 모든 레이어 색상·원·호가 100% 정확히 열립니다."
        }
        return FileIoManager.ExternalCadParseResult(emptyList(), entities, msg)
    }
}
