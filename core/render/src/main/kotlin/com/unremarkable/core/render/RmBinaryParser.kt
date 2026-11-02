package com.unremarkable.core.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs

/**
 * Binary parser for native reMarkable `.rm` stroke & typed-text files (firmware v6 CRDT blocks and legacy v3/v5).
 */
object RmBinaryParser {

    private const val HEADER_LEN = 43
    private const val HEADER_PREFIX_V6 = "reMarkable .lines file, version=6"
    private const val HEADER_PREFIX_V5 = "reMarkable .lines file, version=5"
    private const val HEADER_PREFIX_V3 = "reMarkable .lines file, version=3"

    private const val TAG_ID = 0xF
    private const val TAG_LENGTH4 = 0xC
    private const val TAG_BYTE8 = 0x8
    private const val TAG_BYTE4 = 0x4
    private const val TAG_BYTE1 = 0x1

    private const val TEXT_TOP_Y = -88f
    private const val SPECIAL_ANCHOR_TOP = 281474976710654L
    private const val SPECIAL_ANCHOR_BOTTOM = 281474976710655L

    data class CrdtId(val part1: Int, val part2: Long) {
        companion object {
            val END = CrdtId(0, 0L)
            val START_SENTINEL = CrdtId(-1, 0L)
            val END_SENTINEL = CrdtId(Int.MAX_VALUE, 0L)
        }
    }

    private data class SeqItem<T>(
        val itemId: CrdtId,
        val leftId: CrdtId,
        val rightId: CrdtId,
        val deletedLength: Int,
        val value: T
    )

    private class GroupNode(
        val nodeId: CrdtId,
        var label: String = "",
        var visible: Boolean = true,
        var anchorId: CrdtId? = null,
        var anchorOriginX: Float = 0f,
        val items: MutableList<SeqItem<Any>> = mutableListOf()
    )

    fun parse(bytes: ByteArray): Page? {
        if (bytes.size < HEADER_LEN) return null
        val header = String(bytes, 0, HEADER_LEN, StandardCharsets.US_ASCII)
        return when {
            header.startsWith(HEADER_PREFIX_V6) -> parseV6(bytes)
            header.startsWith(HEADER_PREFIX_V5) -> parseLegacy(bytes, isV5 = true)
            header.startsWith(HEADER_PREFIX_V3) -> parseLegacy(bytes, isV5 = false)
            else -> null
        }
    }

    private fun parseLegacy(bytes: ByteArray, isV5: Boolean): Page? {
        return try {
            val buf = ByteBuffer.wrap(bytes, HEADER_LEN, bytes.size - HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.remaining() < 4) return Page()
            val numLayers = buf.int.coerceIn(0, 64)
            val layers = ArrayList<Layer>(numLayers)

            for (l in 0 until numLayers) {
                if (buf.remaining() < 4) break
                val numStrokes = buf.int.coerceIn(0, 100_000)
                val strokes = ArrayList<Stroke>(numStrokes.coerceAtMost(1024))
                for (s in 0 until numStrokes) {
                    val headerBytes = if (isV5) 24 else 20
                    if (buf.remaining() < headerBytes) break
                    val penId = buf.int
                    val colorId = buf.int
                    buf.int // unknown1
                    val baseWidth = buf.float
                    if (isV5) buf.int // unknown2
                    val numPoints = buf.int.coerceIn(0, 200_000)
                    val pts = ArrayList<StrokePoint>(numPoints.coerceAtMost(4096))
                    for (p in 0 until numPoints) {
                        if (buf.remaining() < 24) break
                        val x = buf.float
                        val y = buf.float
                        val speed = buf.float
                        val direction = buf.float
                        val width = buf.float
                        val pressure = buf.float
                        pts.add(StrokePoint(x, y, speed, direction, width, pressure))
                    }
                    if (pts.isNotEmpty()) {
                        strokes.add(
                            Stroke(
                                tool = ToolType.fromValue(penId),
                                color = PenColor.fromValue(colorId),
                                thicknessScale = baseWidth,
                                points = pts
                            )
                        )
                    }
                }
                layers.add(Layer("Layer ${l + 1}", strokes))
            }
            Page(
                width = Page.DEFAULT_WIDTH,
                height = Page.DEFAULT_HEIGHT,
                layers = layers,
                centerOriginX = false
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseV6(bytes: ByteArray): Page? {
        val r = V6Reader(bytes, HEADER_LEN)
        var paperSize: Pair<Int, Int>? = null
        val groups = LinkedHashMap<CrdtId, GroupNode>()
        fun groupFor(id: CrdtId): GroupNode = groups.getOrPut(id) { GroupNode(id) }

        val highlights = mutableListOf<HighlightRect>()
        var rawTextItems: List<SeqItem<Any>> = emptyList()
        var rawTextStyles: Map<CrdtId, ParagraphStyle> = emptyMap()
        var textPosX = -468f
        var textPosY = 234f
        var textWidth = 936f

        while (r.pos + 8 <= bytes.size) {
            val blockLen = r.readUint32().toInt()
            if (blockLen < 0 || r.pos + 4 + blockLen > bytes.size) break
            r.readUint8() // unknown (0)
            r.readUint8() // minVersion
            val curVersion = r.readUint8()
            val blockType = r.readUint8()
            val blockEnd = r.pos + blockLen

            try {
                when (blockType) {
                    0x0D -> { // SceneInfo
                        while (r.pos < blockEnd) {
                            val (idx, tagType) = r.readTagHeader()
                            if (idx == 5 && tagType == TAG_LENGTH4) {
                                val subEnd = r.enterLength4()
                                if (r.pos + 8 <= subEnd) {
                                    val w = r.readUint32().toInt()
                                    val h = r.readUint32().toInt()
                                    if (w > 0 && h > 0) paperSize = w to h
                                }
                                r.pos = subEnd
                            } else {
                                r.skipTagPayload(tagType, blockEnd)
                            }
                        }
                    }

                    0x01 -> { // SceneTreeBlock: links child group treeId under parentId
                        val treeId = r.readId(1)
                        r.readId(2) // nodeId
                        r.readBool(3) // isUpdate
                        val subEnd = r.readSubblock(4)
                        val parentId = r.readId(1)
                        r.pos = subEnd
                        val childGroup = groupFor(treeId)
                        groupFor(parentId).items.add(
                            SeqItem(treeId, CrdtId.END, CrdtId.END, 0, childGroup)
                        )
                    }

                    0x02 -> { // TreeNodeBlock: group label, visibility, and optional text anchor
                        val nodeId = r.readId(1)
                        val node = groupFor(nodeId)
                        while (r.pos < blockEnd) {
                            val (idx, tagType) = r.readTagHeader()
                            when {
                                idx == 2 && tagType == TAG_LENGTH4 -> node.label = r.readLwwStringBody()
                                idx == 3 && tagType == TAG_LENGTH4 -> node.visible = r.readLwwBoolBody()
                                idx == 7 && tagType == TAG_LENGTH4 -> node.anchorId = r.readLwwIdBody()
                                idx == 10 && tagType == TAG_LENGTH4 -> node.anchorOriginX = r.readLwwFloatBody()
                                else -> r.skipTagPayload(tagType, blockEnd)
                            }
                        }
                    }

                    0x04 -> { // SceneGroupItemBlock: ordered group reference inside parent group
                        val parentId = r.readId(1)
                        val itemId = r.readId(2)
                        val leftId = r.readId(3)
                        val rightId = r.readId(4)
                        val deletedLen = r.readInt(5)
                        if (deletedLen == 0 && r.hasSubblock(6, blockEnd)) {
                            val sub6End = r.readSubblock(6)
                            val itemType = r.readUint8()
                            if (itemType == 0x02) {
                                val targetGroupId = r.readId(2)
                                groupFor(parentId).items.add(
                                    SeqItem(itemId, leftId, rightId, 0, groupFor(targetGroupId))
                                )
                            }
                            r.pos = sub6End
                        }
                    }

                    0x05 -> { // SceneLineItemBlock: pen stroke
                        val parentId = r.readId(1)
                        val itemId = r.readId(2)
                        val leftId = r.readId(3)
                        val rightId = r.readId(4)
                        val deletedLen = r.readInt(5)
                        if (deletedLen == 0 && r.hasSubblock(6, blockEnd)) {
                            val sub6End = r.readSubblock(6)
                            val itemType = r.readUint8()
                            if (itemType == 0x03) {
                                val stroke = readLine(r, curVersion, sub6End)
                                if (stroke != null) {
                                    groupFor(parentId).items.add(
                                        SeqItem(itemId, leftId, rightId, 0, stroke)
                                    )
                                }
                            }
                            r.pos = sub6End
                        }
                    }

                    0x03 -> { // SceneGlyphItemBlock: PDF / text highlight rectangles
                        r.readId(1)
                        r.readId(2)
                        r.readId(3)
                        r.readId(4)
                        val deletedLen = r.readInt(5)
                        if (deletedLen == 0 && r.hasSubblock(6, blockEnd)) {
                            val sub6End = r.readSubblock(6)
                            val itemType = r.readUint8()
                            if (itemType == 0x01) {
                                r.readIntOptional(2, sub6End)
                                r.readIntOptional(3, sub6End)
                                val colorId = r.readInt(4)
                                r.readString(5)
                                val rectsEnd = r.readSubblock(6)
                                val numRects = r.readVarUint().toInt().coerceIn(0, 10_000)
                                val rawRects = ArrayList<FloatArray>(numRects)
                                for (i in 0 until numRects) {
                                    if (r.pos + 32 > rectsEnd) break
                                    rawRects.add(
                                        floatArrayOf(
                                            r.readFloat64().toFloat(),
                                            r.readFloat64().toFloat(),
                                            r.readFloat64().toFloat(),
                                            r.readFloat64().toFloat()
                                        )
                                    )
                                }
                                r.pos = rectsEnd
                                val explicitColor = r.readColorOptional(10, sub6End)
                                val penColor = PenColor.fromValue(colorId)
                                for (rc in rawRects) {
                                    highlights.add(HighlightRect(rc[0], rc[1], rc[2], rc[3], penColor, explicitColor))
                                }
                            }
                            r.pos = sub6End
                        }
                    }

                    0x07 -> { // RootTextBlock: CRDT typed text & paragraph styles
                        r.readId(1)
                        val sub2End = r.readSubblock(2)
                        val sub21End = r.readSubblock(1)
                        val sub211End = r.readSubblock(1)
                        val numTextItems = r.readVarUint().toInt().coerceIn(0, 100_000)
                        val items = ArrayList<SeqItem<Any>>(numTextItems)
                        for (i in 0 until numTextItems) {
                            if (r.pos >= sub211End) break
                            val itemEnd = r.readSubblock(0)
                            val itemId = r.readId(2)
                            val leftId = r.readId(3)
                            val rightId = r.readId(4)
                            val delLen = r.readInt(5)
                            var value: Any = ""
                            if (r.hasSubblock(6, itemEnd)) {
                                val strEnd = r.readSubblock(6)
                                val strLen = r.readVarUint().toInt().coerceAtLeast(0)
                                r.readUint8() // isAscii flag
                                val strBytes = r.readBytes(strLen.coerceAtMost(strEnd - r.pos))
                                val str = String(strBytes, StandardCharsets.UTF_8)
                                val fmt = r.readIntOptional(2, strEnd)
                                value = if (fmt != null && str.isEmpty()) fmt else str
                                r.pos = strEnd
                            }
                            r.pos = itemEnd
                            items.add(SeqItem(itemId, leftId, rightId, delLen, value))
                        }
                        r.pos = sub211End
                        r.pos = sub21End

                        val styles = HashMap<CrdtId, ParagraphStyle>()
                        if (r.hasSubblock(2, sub2End)) {
                            val sub22End = r.readSubblock(2)
                            val sub221End = r.readSubblock(1)
                            val numFormats = r.readVarUint().toInt().coerceIn(0, 50_000)
                            for (i in 0 until numFormats) {
                                if (r.pos >= sub221End) break
                                val charId = r.readRawCrdtId()
                                r.readId(1) // timestamp
                                val fmtSubEnd = r.readSubblock(2)
                                r.readUint8() // 17 marker
                                val styleCode = r.readUint8()
                                r.pos = fmtSubEnd
                                styles[charId] = ParagraphStyle.fromCode(styleCode)
                            }
                            r.pos = sub221End
                            r.pos = sub22End
                        }
                        r.pos = sub2End

                        if (r.hasSubblock(3, blockEnd)) {
                            val posEnd = r.readSubblock(3)
                            textPosX = r.readFloat64().toFloat()
                            textPosY = r.readFloat64().toFloat()
                            r.pos = posEnd
                        }
                        if (r.pos < blockEnd) {
                            textWidth = r.readFloatOptional(4, blockEnd) ?: textWidth
                        }
                        rawTextItems = items
                        rawTextStyles = styles
                    }
                }
            } catch (_: Exception) {
                // Skip malformed block cleanly using framing length
            } finally {
                r.pos = blockEnd
            }
        }

        val (paragraphs, anchorMap) = buildParagraphsAndAnchors(
            rawTextItems,
            rawTextStyles,
            textPosY,
            paperSize?.second ?: Page.DEFAULT_HEIGHT
        )

        // Traverse scene tree from root CrdtId(0, 1) (or fallback to all top-level groups)
        val layers = mutableListOf<Layer>()
        val visitedGroups = HashSet<CrdtId>()
        val rootGroup = groups[CrdtId(0, 1L)]

        fun collectStrokes(group: GroupNode, ax: Float, ay: Float, out: MutableList<Stroke>) {
            if (!visitedGroups.add(group.nodeId) || !group.visible) return
            val gx = if (group.anchorId != null) group.anchorOriginX else 0f
            val gy = group.anchorId?.let { anchorMap[it] ?: 0f } ?: 0f
            val totalAx = ax + gx
            val totalAy = ay + gy

            // Deduplicate groups added via both SceneTreeBlock and SceneGroupItemBlock
            val orderedValues = toposortValues(group.items)
            val seenChildGroups = HashSet<CrdtId>()
            for (v in orderedValues) {
                when (v) {
                    is Stroke -> {
                        if (totalAx == 0f && totalAy == 0f) {
                            out.add(v)
                        } else {
                            out.add(
                                v.copy(
                                    points = v.points.map { pt -> pt.copy(x = pt.x + totalAx, y = pt.y + totalAy) }
                                )
                            )
                        }
                    }
                    is GroupNode -> {
                        if (seenChildGroups.add(v.nodeId)) {
                            collectStrokes(v, totalAx, totalAy, out)
                        }
                    }
                }
            }
        }

        if (rootGroup != null) {
            visitedGroups.add(rootGroup.nodeId)
            val rootChildren = toposortValues(rootGroup.items)
            val directStrokes = mutableListOf<Stroke>()
            val seenLayers = HashSet<CrdtId>()
            for (child in rootChildren) {
                when (child) {
                    is GroupNode -> {
                        if (seenLayers.add(child.nodeId) && child.visible) {
                            val layerStrokes = mutableListOf<Stroke>()
                            collectStrokes(child, 0f, 0f, layerStrokes)
                            if (layerStrokes.isNotEmpty()) {
                                layers.add(Layer(child.label.ifBlank { "Layer ${layers.size + 1}" }, layerStrokes))
                            }
                        }
                    }
                    is Stroke -> directStrokes.add(child)
                }
            }
            if (directStrokes.isNotEmpty()) {
                layers.add(0, Layer("Layer 1", directStrokes))
            }
        }

        // Fallback for standalone groups not attached to CrdtId(0, 1)
        for (g in groups.values) {
            if (g.nodeId !in visitedGroups && g.visible) {
                val extra = mutableListOf<Stroke>()
                collectStrokes(g, 0f, 0f, extra)
                if (extra.isNotEmpty()) {
                    layers.add(Layer(g.label.ifBlank { "Layer ${layers.size + 1}" }, extra))
                }
            }
        }

        // Detect RM2 (1404x1872) vs Paper Pro (1620x2160) when SceneInfo omits paper_size
        val (pageW, pageH) = paperSize ?: run {
            var exceedsRm2 = false
            for (layer in layers) {
                for (s in layer.strokes) {
                    for (pt in s.points) {
                        if (abs(pt.x) > 706f || pt.y > 1880f) {
                            exceedsRm2 = true
                            break
                        }
                    }
                    if (exceedsRm2) break
                }
                if (exceedsRm2) break
            }
            if (exceedsRm2) Page.PAPER_PRO_WIDTH to Page.PAPER_PRO_HEIGHT
            else Page.DEFAULT_WIDTH to Page.DEFAULT_HEIGHT
        }

        return Page(
            width = pageW,
            height = pageH,
            layers = layers,
            centerOriginX = true,
            highlights = highlights,
            typedParagraphs = paragraphs,
            textPosX = textPosX,
            textPosY = textPosY,
            textWidth = textWidth
        )
    }

    private fun readLine(r: V6Reader, version: Int, subEnd: Int): Stroke? {
        val toolId = r.readInt(1)
        val colorId = r.readInt(2)
        val thicknessScale = r.readDouble(3).toFloat()
        r.readFloat(4) // startingLength
        val ptsEnd = r.readSubblock(5)
        val pointSize = if (version == 1) 24 else 14
        val dataLen = (ptsEnd - r.pos).coerceAtLeast(0)
        val numPoints = dataLen / pointSize
        val points = ArrayList<StrokePoint>(numPoints)

        for (i in 0 until numPoints) {
            val x = r.readFloat32()
            val y = r.readFloat32()
            if (version == 1) {
                val speed = r.readFloat32() * 4f
                val direction = (255.0 * r.readFloat32() / (2.0 * PI)).toFloat()
                val width = r.readFloat32() * 4f
                val pressure = r.readFloat32().coerceIn(0f, 1f)
                points.add(StrokePoint(x, y, speed, direction, width, pressure))
            } else {
                val speed = r.readUint16().toFloat()
                val width = r.readUint16().toFloat()
                val direction = r.readUint8().toFloat()
                val pressure = (r.readUint8() / 255f).coerceIn(0f, 1f)
                points.add(StrokePoint(x, y, speed, direction, width, pressure))
            }
        }
        r.pos = ptsEnd

        var explicitColor: Rgba? = null
        while (r.pos < subEnd) {
            val (idx, tagType) = r.readTagHeader()
            if (idx == 8 && tagType == TAG_BYTE4) {
                val packed = r.readUint32().toInt()
                explicitColor = Rgba(
                    r = (packed ushr 16) and 0xFF,
                    g = (packed ushr 8) and 0xFF,
                    b = packed and 0xFF,
                    a = ((packed ushr 24) and 0xFF) / 255f
                )
            } else {
                r.skipTagPayload(tagType, subEnd)
            }
        }

        if (points.isEmpty()) return null
        return Stroke(
            tool = ToolType.fromValue(toolId),
            color = PenColor.fromValue(colorId),
            thicknessScale = thicknessScale,
            points = points,
            explicitColor = explicitColor
        )
    }

    private fun buildParagraphsAndAnchors(
        rawItems: List<SeqItem<Any>>,
        styles: Map<CrdtId, ParagraphStyle>,
        textPosY: Float,
        defaultHeight: Int
    ): Pair<List<TypedParagraph>, Map<CrdtId, Float>> {
        val anchorMap = HashMap<CrdtId, Float>()
        if (rawItems.isEmpty()) {
            anchorMap[CrdtId(0, SPECIAL_ANCHOR_TOP)] = 0f
            anchorMap[CrdtId(0, SPECIAL_ANCHOR_BOTTOM)] = defaultHeight.toFloat()
            return emptyList<TypedParagraph>() to anchorMap
        }

        // Expand multi-character CRDT strings into single-character items so edits & anchors resolve accurately
        val expanded = mutableListOf<SeqItem<Any>>()
        for (item in rawItems) {
            when {
                item.deletedLength > 0 -> {
                    var curId = item.itemId
                    var curLeft = item.leftId
                    for (i in 0 until item.deletedLength) {
                        val nextRight = if (i < item.deletedLength - 1) CrdtId(curId.part1, curId.part2 + 1) else item.rightId
                        expanded.add(SeqItem(curId, curLeft, nextRight, 1, ""))
                        curLeft = curId
                        curId = nextRight
                    }
                }
                item.value is Int -> expanded.add(item)
                item.value is String && item.value.isNotEmpty() -> {
                    val s = item.value
                    var curId = item.itemId
                    var curLeft = item.leftId
                    for (i in s.indices) {
                        val nextRight = if (i < s.length - 1) CrdtId(curId.part1, curId.part2 + 1) else item.rightId
                        expanded.add(SeqItem(curId, curLeft, nextRight, 0, s[i].toString()))
                        curLeft = curId
                        curId = nextRight
                    }
                }
            }
        }

        val orderedIds = toposortIds(expanded)
        val byId = expanded.associateBy { it.itemId }

        val paragraphs = mutableListOf<TypedParagraph>()
        var idx = 0
        var ypos = textPosY + TEXT_TOP_Y
        val topY = ypos

        while (idx < orderedIds.size) {
            val firstItem = byId[orderedIds[idx]]
            val startId = if (firstItem != null && firstItem.deletedLength == 0 && firstItem.value == "\n") {
                idx++
                firstItem.itemId
            } else {
                CrdtId.END
            }

            val sb = StringBuilder()
            val charIds = mutableListOf<CrdtId>()
            var inlineBold = false

            while (idx < orderedIds.size) {
                val cur = byId[orderedIds[idx]] ?: break
                if (cur.deletedLength > 0) {
                    idx++
                    continue
                }
                val v = cur.value
                if (v is Int) {
                    if (v == 1) inlineBold = true else if (v == 2) inlineBold = false
                    idx++
                } else if (v == "\n") {
                    break
                } else if (v is String) {
                    sb.append(v)
                    charIds.add(cur.itemId)
                    idx++
                } else {
                    idx++
                }
            }

            val baseStyle = styles[startId] ?: ParagraphStyle.PLAIN
            val effectiveStyle = if (baseStyle == ParagraphStyle.PLAIN && inlineBold && sb.isNotEmpty()) {
                ParagraphStyle.BOLD
            } else {
                baseStyle
            }

            anchorMap[startId] = ypos
            for (cid in charIds) {
                anchorMap[cid] = ypos
            }
            ypos += effectiveStyle.lineHeight
            paragraphs.add(TypedParagraph(sb.toString(), effectiveStyle))
        }

        anchorMap[CrdtId(0, SPECIAL_ANCHOR_TOP)] = topY
        anchorMap[CrdtId(0, SPECIAL_ANCHOR_BOTTOM)] = ypos
        return paragraphs to anchorMap
    }

    private fun <T> toposortValues(items: List<SeqItem<T>>): List<T> {
        if (items.size <= 1) return items.filter { it.deletedLength == 0 }.map { it.value }
        val byId = LinkedHashMap<CrdtId, SeqItem<T>>()
        for (it in items) byId[it.itemId] = it
        return toposortIds(byId.values.toList()).mapNotNull { id ->
            byId[id]?.takeIf { it.deletedLength == 0 }?.value
        }
    }

    private fun <T> toposortIds(items: List<SeqItem<T>>): List<CrdtId> {
        if (items.isEmpty()) return emptyList()
        val itemMap = LinkedHashMap<CrdtId, SeqItem<T>>(items.size)
        for (it in items) itemMap[it.itemId] = it

        val inDegree = HashMap<CrdtId, Int>()
        val dependents = HashMap<CrdtId, MutableList<CrdtId>>()
        val allNodes = LinkedHashSet<CrdtId>()
        allNodes.add(CrdtId.START_SENTINEL)
        allNodes.add(CrdtId.END_SENTINEL)

        for (item in itemMap.values) {
            val id = item.itemId
            val left = if (item.leftId == CrdtId.END || item.leftId !in itemMap) CrdtId.START_SENTINEL else item.leftId
            val right = if (item.rightId == CrdtId.END || item.rightId !in itemMap) CrdtId.END_SENTINEL else item.rightId
            allNodes.add(id)
            inDegree[id] = (inDegree[id] ?: 0) + 1
            dependents.getOrPut(left) { mutableListOf() }.add(id)
            inDegree[right] = (inDegree[right] ?: 0) + 1
            dependents.getOrPut(id) { mutableListOf() }.add(right)
        }
        for (n in allNodes) inDegree.putIfAbsent(n, 0)

        fun rank(id: CrdtId): Int = when (id) {
            CrdtId.START_SENTINEL -> 0
            CrdtId.END_SENTINEL -> 2
            else -> 1
        }

        val ready = PriorityQueue<CrdtId>(
            compareBy<CrdtId>({ rank(it) }, { -it.part1 }, { it.part2 })
        )
        for (n in allNodes) {
            if ((inDegree[n] ?: 0) == 0) ready.add(n)
        }

        val result = ArrayList<CrdtId>(itemMap.size)
        while (ready.isNotEmpty()) {
            val node = ready.poll()
            if (node in itemMap) result.add(node)
            if (node == CrdtId.END_SENTINEL) break
            dependents[node]?.forEach { dep ->
                val nextDeg = (inDegree[dep] ?: 1) - 1
                inDegree[dep] = nextDeg
                if (nextDeg == 0) ready.add(dep)
            }
        }
        // Fallback if malformed cycle left items unvisited
        if (result.size < itemMap.size) {
            for (id in itemMap.keys) if (id !in result) result.add(id)
        }
        return result
    }

    private class V6Reader(private val data: ByteArray, var pos: Int) {

        fun readUint8(): Int {
            if (pos >= data.size) throw IndexOutOfBoundsException("EOF")
            return data[pos++].toInt() and 0xFF
        }

        fun readBool(): Boolean = readUint8() != 0

        fun readUint16(): Int {
            val b0 = readUint8()
            val b1 = readUint8()
            return b0 or (b1 shl 8)
        }

        fun readUint32(): Long {
            val b0 = readUint8().toLong()
            val b1 = readUint8().toLong()
            val b2 = readUint8().toLong()
            val b3 = readUint8().toLong()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        fun readFloat32(): Float = Float.fromBits(readUint32().toInt())

        fun readFloat64(): Double {
            val lo = readUint32()
            val hi = readUint32()
            return Double.fromBits(lo or (hi shl 32))
        }

        fun readBytes(len: Int): ByteArray {
            if (len <= 0) return ByteArray(0)
            if (pos + len > data.size) throw IndexOutOfBoundsException("EOF")
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun readVarUint(): Long {
            var shift = 0
            var result = 0L
            while (shift < 64) {
                val b = readUint8()
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        fun readRawCrdtId(): CrdtId = CrdtId(readUint8(), readVarUint())

        fun readTagHeader(): Pair<Int, Int> {
            val x = readVarUint()
            return (x ushr 4).toInt() to (x and 0xFL).toInt()
        }

        private fun expectTag(expectedIndex: Int, expectedType: Int) {
            val saved = pos
            val (idx, type) = readTagHeader()
            if (idx != expectedIndex || type != expectedType) {
                pos = saved
                throw IllegalStateException("Expected tag ($expectedIndex, $expectedType) got ($idx, $type)")
            }
        }

        private fun peekTag(expectedIndex: Int, expectedType: Int, limit: Int): Boolean {
            if (pos >= limit) return false
            val saved = pos
            return try {
                val (idx, type) = readTagHeader()
                idx == expectedIndex && type == expectedType && pos <= limit
            } catch (_: Exception) {
                false
            } finally {
                pos = saved
            }
        }

        fun hasSubblock(index: Int, limit: Int): Boolean = peekTag(index, TAG_LENGTH4, limit)

        fun enterLength4(): Int {
            val len = readUint32().toInt().coerceAtLeast(0)
            return (pos + len).coerceAtMost(data.size)
        }

        fun readSubblock(index: Int): Int {
            expectTag(index, TAG_LENGTH4)
            return enterLength4()
        }

        fun readId(index: Int): CrdtId {
            expectTag(index, TAG_ID)
            return readRawCrdtId()
        }

        fun readBool(index: Int): Boolean {
            expectTag(index, TAG_BYTE1)
            return readBool()
        }

        fun readInt(index: Int): Int {
            expectTag(index, TAG_BYTE4)
            return readUint32().toInt()
        }

        fun readFloat(index: Int): Float {
            expectTag(index, TAG_BYTE4)
            return readFloat32()
        }

        fun readDouble(index: Int): Double {
            expectTag(index, TAG_BYTE8)
            return readFloat64()
        }

        fun readIntOptional(index: Int, limit: Int): Int? =
            if (peekTag(index, TAG_BYTE4, limit)) readInt(index) else null

        fun readFloatOptional(index: Int, limit: Int): Float? =
            if (peekTag(index, TAG_BYTE4, limit)) readFloat(index) else null

        fun readColorOptional(index: Int, limit: Int): Rgba? {
            if (!peekTag(index, TAG_BYTE4, limit)) return null
            val packed = readInt(index)
            return Rgba(
                r = (packed ushr 16) and 0xFF,
                g = (packed ushr 8) and 0xFF,
                b = packed and 0xFF,
                a = ((packed ushr 24) and 0xFF) / 255f
            )
        }

        fun readString(index: Int): String {
            val end = readSubblock(index)
            val strLen = readVarUint().toInt().coerceAtLeast(0)
            readUint8() // isAscii
            val str = String(readBytes(strLen.coerceAtMost(end - pos)), StandardCharsets.UTF_8)
            pos = end
            return str
        }

        fun readLwwBoolBody(): Boolean {
            val end = enterLength4()
            readId(1)
            val v = readBool(2)
            pos = end
            return v
        }

        fun readLwwIdBody(): CrdtId {
            val end = enterLength4()
            readId(1)
            val v = readId(2)
            pos = end
            return v
        }

        fun readLwwFloatBody(): Float {
            val end = enterLength4()
            readId(1)
            val v = readFloat(2)
            pos = end
            return v
        }

        fun readLwwStringBody(): String {
            val end = enterLength4()
            readId(1)
            val v = readString(2)
            pos = end
            return v
        }

        fun skipTagPayload(tagType: Int, limit: Int) {
            when (tagType) {
                TAG_ID -> readRawCrdtId()
                TAG_BYTE1 -> pos = (pos + 1).coerceAtMost(limit)
                TAG_BYTE4 -> pos = (pos + 4).coerceAtMost(limit)
                TAG_BYTE8 -> pos = (pos + 8).coerceAtMost(limit)
                TAG_LENGTH4 -> {
                    val subEnd = enterLength4()
                    pos = subEnd.coerceAtMost(limit)
                }
                else -> pos = limit
            }
        }
    }
}
