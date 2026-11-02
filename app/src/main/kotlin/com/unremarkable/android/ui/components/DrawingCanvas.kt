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

data class DrawnStroke(
    val path: List<Offset>,
    val tool: ToolType,
    val color: PenColor,
    val strokeWidth: Float,
    val opacity: Float = 1.0f
)

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
    var currentPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var undoStack by remember { mutableStateOf<List<List<DrawnStroke>>>(emptyList()) }

    val currentColorRgba = RmPalette.getColor(activeColor.value, isDarkMode)
    val strokeColor = Color(currentColorRgba.r, currentColorRgba.g, currentColorRgba.b)

    Box(modifier = modifier.fillMaxSize()) {
        // Drawing Surface
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isDrawingEnabled, activeTool, activeColor) {
                    if (!isDrawingEnabled) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPath = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPath = currentPath + change.position
                        },
                        onDragEnd = {
                            if (currentPath.isNotEmpty()) {
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
                                    // Remove strokes touched by eraser
                                    val eraserPoints = currentPath
                                    val remaining = strokes.filterNot { stroke ->
                                        stroke.path.any { pt ->
                                            eraserPoints.any { e -> (e - pt).getDistance() < 30f }
                                        }
                                    }
                                    onStrokesChanged(remaining)
                                } else {
                                    val newStroke = DrawnStroke(
                                        path = currentPath,
                                        tool = activeTool,
                                        color = activeColor,
                                        strokeWidth = width,
                                        opacity = opacity
                                    )
                                    onStrokesChanged(strokes + newStroke)
                                }
                                currentPath = emptyList()
                            }
                        }
                    )
                }
        ) {
            // Render existing strokes
            for (stroke in strokes) {
                if (stroke.path.size < 2) continue
                val strokeRgba = RmPalette.getColor(stroke.color.value, isDarkMode)
                val c = Color(strokeRgba.r, strokeRgba.g, strokeRgba.b).copy(alpha = stroke.opacity)
                val p = Path().apply {
                    moveTo(stroke.path.first().x, stroke.path.first().y)
                    for (i in 1 until stroke.path.size) {
                        lineTo(stroke.path[i].x, stroke.path[i].y)
                    }
                }
                drawPath(
                    path = p,
                    color = c,
                    style = CanvasStroke(
                        width = stroke.strokeWidth,
                        cap = if (stroke.tool == ToolType.HIGHLIGHTER) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round
                    ),
                    blendMode = if (isDarkMode && stroke.tool == ToolType.HIGHLIGHTER) BlendMode.Screen else BlendMode.SrcOver
                )
            }

            // Render current drawing path
            if (currentPath.size >= 2) {
                val c = if (activeTool == ToolType.ERASER) {
                    Color.Gray.copy(alpha = 0.3f)
                } else {
                    strokeColor.copy(alpha = if (activeTool == ToolType.HIGHLIGHTER) 0.4f else 1.0f)
                }
                val p = Path().apply {
                    moveTo(currentPath.first().x, currentPath.first().y)
                    for (i in 1 until currentPath.size) {
                        lineTo(currentPath[i].x, currentPath[i].y)
                    }
                }
                drawPath(
                    path = p,
                    color = c,
                    style = CanvasStroke(
                        width = if (activeTool == ToolType.HIGHLIGHTER) 28f else if (activeTool == ToolType.ERASER) 36f else 5f,
                        cap = StrokeCap.Round,
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

                    VerticalDivider(modifier = Modifier.height(28.dp))

                    // Color Picker Pills
                    val colors = listOf(PenColor.BLACK, PenColor.BLUE, PenColor.RED, PenColor.YELLOW, PenColor.GREEN)
                    for (c in colors) {
                        val cRgba = RmPalette.getColor(c.value, isDarkMode)
                        val composeCol = Color(cRgba.r, cRgba.g, cRgba.b)
                        Box(
                            modifier = Modifier
                                .size(if (activeColor == c) 24.dp else 18.dp)
                                .background(composeCol, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures { _, _ -> }
                                }
                        )
                    }

                    VerticalDivider(modifier = Modifier.height(28.dp))

                    // Undo
                    IconButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                val prev = undoStack.last()
                                undoStack = undoStack.dropLast(1)
                                onStrokesChanged(prev)
                            }
                        },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
