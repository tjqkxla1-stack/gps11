package kr.koseco.gandansurvey.v90.util

import kr.koseco.gandansurvey.v90.model.CadEntityType
import kr.koseco.gandansurvey.v90.model.CadLayerEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ShpFileParser {

    fun parseShpBytes(bytes: ByteArray, fileName: String): List<CadLayerEntity> {
        if (bytes.size < 100) return emptyList()
        val entities = mutableListOf<CadLayerEntity>()
        try {
            val buf = ByteBuffer.wrap(bytes)
            var offset = 100
            while (offset + 12 <= bytes.size) {
                buf.order(ByteOrder.BIG_ENDIAN)
                val recNum = buf.getInt(offset)
                val contentLenWords = buf.getInt(offset + 4)
                val contentBytes = contentLenWords * 2
                val recStart = offset + 8
                if (recStart + contentBytes > bytes.size || contentBytes < 4) break

                buf.order(ByteOrder.LITTLE_ENDIAN)
                val shapeType = buf.getInt(recStart)
                when (shapeType) {
                    1, 11, 21 -> { // Point
                        if (recStart + 20 <= bytes.size) {
                            val xE = buf.getDouble(recStart + 4)
                            val yN = buf.getDouble(recStart + 12)
                            entities.add(
                                CadLayerEntity(
                                    layerName = "SHP_점",
                                    type = CadEntityType.POINT,
                                    verticesNE = listOf(Pair(yN, xE)),
                                    colorHex = 0xFF22C55E,
                                    textLabel = "SHP-$recNum",
                                    sourceType = "SHP"
                                )
                            )
                        }
                    }
                    3, 5, 13, 15, 23, 25 -> { // PolyLine / Polygon
                        if (recStart + 44 <= bytes.size) {
                            val numParts = buf.getInt(recStart + 36)
                            val numPoints = buf.getInt(recStart + 40)
                            val partsStart = recStart + 44
                            val pointsStart = partsStart + numParts * 4
                            if (numParts in 1..5000 && numPoints in 2..50000 && pointsStart + numPoints * 16 <= bytes.size) {
                                val partIndices = IntArray(numParts) { p -> buf.getInt(partsStart + p * 4) }
                                for (p in 0 until numParts) {
                                    val startPt = partIndices[p]
                                    val endPt = if (p + 1 < numParts) partIndices[p + 1] else numPoints
                                    val verts = mutableListOf<Pair<Double, Double>>()
                                    for (idx in startPt until endPt) {
                                        val ptPos = pointsStart + idx * 16
                                        val xE = buf.getDouble(ptPos)
                                        val yN = buf.getDouble(ptPos + 8)
                                        verts.add(Pair(yN, xE))
                                    }
                                    if (verts.size >= 2) {
                                        entities.add(
                                            CadLayerEntity(
                                                layerName = "SHP_폴리선",
                                                type = CadEntityType.POLYLINE,
                                                verticesNE = verts,
                                                isClosed = (shapeType == 5 || shapeType == 15 || shapeType == 25),
                                                colorHex = 0xFFFACC15,
                                                sourceType = "SHP"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                offset = recStart + contentBytes
            }
        } catch (_: Exception) {}
        return entities
    }
}
