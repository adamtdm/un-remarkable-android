package com.unremarkable.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as CanvasStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.unremarkable.core.render.PenColor
import com.unremarkable.core.render.RmPalette
import com.unremarkable.core.render.ToolType

class DrawnStroke(
    val points: List<Offset>,
    val tool: ToolType,
    val color: PenColor,
    val strokeWidth: Float,
    val opacity: Float = 1.0f
) {
    val path: Path = Path().apply {
        if (points.isNotEmpty()) {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }
    }

    private val minX: Float = points.minOfOrNull { it.x } ?: 0f
    private val maxX: Float = points.maxOfOrNull { it.x } ?: 0f
    private val minY: Float = points.minOfOrNull { it.y } ?: 0f
    private val maxY: Float = points.maxOfOrNull { it.y } ?: 0f

    fun intersects(eraserPoints: List<Offset>, threshold: Float): Boolean {
        if (points.isEmpty() || eraserPoints.isEmpty()) return false

        val eMinX = eraserPoints.minOfOrNull { it.x } ?: return false
        val eMaxX = eraserPoints.maxOfOrNull { it.x } ?: return false
        val eMinY = eraserPoints.minOfOrNull { it.y } ?: return false
        val eMaxY = eraserPoints.maxOfOrNull { it.y } ?: return false

        // Bounding box test
        if (eMaxX < minX - threshold || eMinX > maxX + threshold ||
            eMaxY < minY - threshold || eMinY > maxY + threshold
        ) {
            return false
        }

        val threshSq = threshold * threshold
        for (pt in points) {
            for (e in eraserPoints) {
                val dx = e.x - pt.x
                val dy = e.y - pt.y
                if (dx * dx + dy * dy <= threshSq) return true
            }
        }
        return false
    }
}

@Composable
fun DrawingCanvas(
    isDarkMode: Boolean,
    isDrawingEnabled: Boolean,
    strokes: List<DrawnStroke>,
    onStrokesChanged: (List<DrawnStroke>) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTool by remember { mutableStateOf(ToolType.FINELINER) }
    var activeColor by remember { mutableStateOf(PenColor.BLACK) }
    var undoStack by remember { mutableStateOf<List<List<DrawnStroke>>>(emptyList()) }

    // Live stroke state during active touch drag
    val livePoints = remember { mutableStateListOf<Offset>() }
    val livePath = remember { Path() }
    var liveTick by remember { mutableIntStateOf(0) }

    val currentColorRgba = RmPalette.getColor(activeColor.value, isDarkMode)
    val strokeColor = Color(currentColorRgba.r, currentColorRgba.g, currentColorRgba.b)

    Box(modifier = modifier.fillMaxSize()) {
        // Drawing Surface
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isDrawingEnabled, activeTool, activeColor, strokes) {
                    if (!isDrawingEnabled) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            livePoints.clear()
                            livePoints.add(offset)
                            livePath.reset()
                            livePath.moveTo(offset.x, offset.y)
                            liveTick++
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            livePoints.add(change.position)
                            livePath.lineTo(change.position.x, change.position.y)
                            liveTick++
                        },
                        onDragEnd = {
                            if (livePoints.isNotEmpty()) {
                                val width = when (activeTool) {
                                    ToolType.HIGHLIGHTER -> 28f
                                    ToolType.MARKER -> 10f
                                    ToolType.FINELINER -> 4f
                                    ToolType.PENCIL -> 3f
                                    ToolType.ERASER -> 36f
                                    else -> 5f
                                }
                                val opacity = if (activeTool == ToolType.HIGHLIGHTER) 0.35f else 1.0f

                                undoStack = undoStack + listOf(strokes)
                                if (activeTool == ToolType.ERASER) {
                                    // Remove strokes touched by eraser with bounding box acceleration
                                    val eraserPoints = livePoints.toList()
                                    val remaining = strokes.filterNot { stroke ->
                                        stroke.intersects(eraserPoints, 32f)
                                    }
                                    onStrokesChanged(remaining)
                                } else {
                                    val newStroke = DrawnStroke(
                                        points = livePoints.toList(),
                                        tool = activeTool,
                                        color = activeColor,
                                        strokeWidth = width,
                                        opacity = opacity
                                    )
                                    onStrokesChanged(strokes + newStroke)
                                }
                                livePoints.clear()
                                livePath.reset()
                                liveTick++
                            }
                        }
                    )
                }
        ) {
            // Reference liveTick so Canvas recomposes when dragging
            @Suppress("UNUSED_VARIABLE")
            val tick = liveTick

            // Render existing strokes (using cached Path instances)
            for (stroke in strokes) {
                if (stroke.points.size < 2) continue
                val strokeRgba = RmPalette.getColor(stroke.color.value, isDarkMode)
                val c = Color(strokeRgba.r, strokeRgba.g, strokeRgba.b).copy(alpha = stroke.opacity)
                drawPath(
                    path = stroke.path,
                    color = c,
                    style = CanvasStroke(
                        width = stroke.strokeWidth,
                        cap = if (stroke.tool == ToolType.HIGHLIGHTER) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round
                    ),
                    blendMode = if (isDarkMode && stroke.tool == ToolType.HIGHLIGHTER) BlendMode.Screen else BlendMode.SrcOver
                )
            }

            // Render live dragging stroke
            if (livePoints.size >= 2) {
                val c = if (activeTool == ToolType.ERASER) {
                    Color.Gray.copy(alpha = 0.3f)
                } else {
                    strokeColor.copy(alpha = if (activeTool == ToolType.HIGHLIGHTER) 0.4f else 1.0f)
                }
                val width = when (activeTool) {
                    ToolType.HIGHLIGHTER -> 28f
                    ToolType.MARKER -> 10f
                    ToolType.FINELINER -> 4f
                    ToolType.PENCIL -> 3f
                    ToolType.ERASER -> 36f
                    else -> 5f
                }
                drawPath(
                    path = livePath,
                    color = c,
                    style = CanvasStroke(
                        width = width,
                        cap = if (activeTool == ToolType.HIGHLIGHTER) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // Floating Pen Toolbar (when drawing enabled)
        if (isDrawingEnabled) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Tool Buttons
                    IconButton(
                        onClick = { activeTool = ToolType.FINELINER },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeTool == ToolType.FINELINER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(Icons.Default.BorderColor, contentDescription = "Fineliner", modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { activeTool = ToolType.MARKER },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeTool == ToolType.MARKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(Icons.Default.Brush, contentDescription = "Marker", modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { activeTool = ToolType.HIGHLIGHTER },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeTool == ToolType.HIGHLIGHTER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(Icons.Default.Highlight, contentDescription = "Highlighter", modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { activeTool = ToolType.PENCIL },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeTool == ToolType.PENCIL) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Pencil", modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { activeTool = ToolType.ERASER },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeTool == ToolType.ERASER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Eraser", modifier = Modifier.size(20.dp))
                    }

                    VerticalDivider(modifier = Modifier.height(24.dp))

                    // Color Pickers
                    val colors = listOf(PenColor.BLACK, PenColor.GRAY, PenColor.RED, PenColor.BLUE)
                    for (color in colors) {
                        val rgba = RmPalette.getColor(color.value, isDarkMode)
                        val c = Color(rgba.r, rgba.g, rgba.b)
                        IconButton(
                            onClick = { activeColor = color },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Surface(
                                color = c,
                                shape = CircleShape,
                                border = if (activeColor == color) {
                                    androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else null,
                                modifier = Modifier.size(20.dp)
                            ) {}
                        }
                    }

                    VerticalDivider(modifier = Modifier.height(24.dp))

                    // Undo Button
                    IconButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                val previousState = undoStack.last()
                                undoStack = undoStack.dropLast(1)
                                onStrokesChanged(previousState)
                            }
                        },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                }
            }
        }
    }
}
