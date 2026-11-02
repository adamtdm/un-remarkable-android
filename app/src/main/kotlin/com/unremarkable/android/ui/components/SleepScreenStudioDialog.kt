package com.unremarkable.android.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.unremarkable.android.ui.theme.Zinc800
import com.unremarkable.android.ui.theme.Zinc900
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class RmDeviceModel(val displayName: String, val width: Int, val height: Int) {
    PAPER_PRO("reMarkable Paper Pro", 1620, 2160),
    RM2("reMarkable 2", 1404, 1872)
}

@Composable
fun SleepScreenStudioDialog(
    sourceBitmap: Bitmap?,
    documentTitle: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedDevice by remember { mutableStateOf(RmDeviceModel.PAPER_PRO) }
    var ownerName by remember { mutableStateOf("") }
    var ownerContact by remember { mutableStateOf("") }
    var isHighContrast by remember { mutableStateOf(true) }
    var isInverted by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    // Live preview bitmap generated in background
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(selectedDevice, ownerName, ownerContact, isHighContrast, isInverted, sourceBitmap) {
        withContext(Dispatchers.Default) {
            val previewW = 320
            val previewH = (previewW * (selectedDevice.height.toFloat() / selectedDevice.width.toFloat())).toInt()
            val bmp = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val bg = if (isInverted) AndroidColor.BLACK else AndroidColor.WHITE
            val fg = if (isInverted) AndroidColor.WHITE else AndroidColor.BLACK
            canvas.drawColor(bg)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }

            // Draw source bitmap scaled
            if (sourceBitmap != null) {
                val srcRect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
                val dstH = (previewH * 0.7f).toInt()
                val dstRect = Rect(16, 16, previewW - 16, dstH)
                canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)
            } else {
                paint.textSize = 18f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(documentTitle.take(24), previewW / 2f, previewH / 3f, paint)
            }

            // Draw Owner / Contact Banner
            if (ownerName.isNotBlank() || ownerContact.isNotBlank()) {
                val bannerTop = (previewH * 0.74f).toInt()
                val bannerPaint = Paint().apply {
                    color = if (isInverted) AndroidColor.DKGRAY else AndroidColor.LTGRAY
                }
                canvas.drawRect(Rect(12, bannerTop, previewW - 12, previewH - 16), bannerPaint)

                paint.textSize = 13f
                paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = true
                if (ownerName.isNotBlank()) {
                    canvas.drawText("Property of $ownerName", previewW / 2f, bannerTop + 24f, paint)
                }
                if (ownerContact.isNotBlank()) {
                    paint.textSize = 11f
                    paint.isFakeBoldText = false
                    canvas.drawText("If found: $ownerContact", previewW / 2f, bannerTop + 44f, paint)
                }
            }

            previewBitmap = bmp
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sleep Screen Studio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = "Craft and export custom suspended & poweroff splash screens for your reMarkable tablet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Canvas Frame Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Zinc900)
                        .border(1.dp, Zinc800, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Sleep Screen Preview",
                            modifier = Modifier
                                .fillMaxHeight(0.9f)
                                .aspectRatio(selectedDevice.width.toFloat() / selectedDevice.height.toFloat())
                                .border(1.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Device Target Selector
                Text("Target Device Model", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RmDeviceModel.entries.forEach { model ->
                        FilterChip(
                            selected = selectedDevice == model,
                            onClick = { selectedDevice = model },
                            label = { Text("${model.displayName} (${model.width}x${model.height})") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Custom Banner Fields
                Text("Owner & Return Info Overlay", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = ownerName,
                    onValueChange = { ownerName = it },
                    label = { Text("Your Name") },
                    placeholder = { Text("e.g. Alex Mercer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )

                OutlinedTextField(
                    value = ownerContact,
                    onValueChange = { ownerContact = it },
                    label = { Text("Contact Phone / Email / Telegram") },
                    placeholder = { Text("e.g. +1 555-0199 • alex@domain.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Contrast / Inversion Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Invert Colors (Dark Background)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isInverted, onCheckedChange = { isInverted = it })
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isGenerating = true
                            try {
                                val fullBmp = generateFullResolutionSplash(
                                    device = selectedDevice,
                                    source = sourceBitmap,
                                    title = documentTitle,
                                    name = ownerName,
                                    contact = ownerContact,
                                    inverted = isInverted
                                )
                                val outputFile = saveSplashToFile(context, fullBmp)
                                shareExportedSplash(context, outputFile)
                                Toast.makeText(context, "Exported suspended.png to Downloads!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rendering ${selectedDevice.width}x${selectedDevice.height}...")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export suspended.png (Share / Save)")
                    }
                }
            }
        }
    }
}

private fun generateFullResolutionSplash(
    device: RmDeviceModel,
    source: Bitmap?,
    title: String,
    name: String,
    contact: String,
    inverted: Boolean
): Bitmap {
    val fullBmp = Bitmap.createBitmap(device.width, device.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(fullBmp)
    val bg = if (inverted) AndroidColor.BLACK else AndroidColor.WHITE
    val fg = if (inverted) AndroidColor.WHITE else AndroidColor.BLACK
    canvas.drawColor(bg)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }

    if (source != null) {
        val srcRect = Rect(0, 0, source.width, source.height)
        val dstH = (device.height * 0.72f).toInt()
        val dstRect = Rect(40, 40, device.width - 40, dstH)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
    } else {
        paint.textSize = 64f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(title, device.width / 2f, device.height / 3f, paint)
    }

    if (name.isNotBlank() || contact.isNotBlank()) {
        val bannerTop = (device.height * 0.76f).toInt()
        val bannerPaint = Paint().apply {
            color = if (inverted) AndroidColor.DKGRAY else AndroidColor.LTGRAY
        }
        canvas.drawRect(Rect(40, bannerTop, device.width - 40, device.height - 40), bannerPaint)

        paint.textSize = 48f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        if (name.isNotBlank()) {
            canvas.drawText("Property of $name", device.width / 2f, bannerTop + 90f, paint)
        }
        if (contact.isNotBlank()) {
            paint.textSize = 36f
            paint.isFakeBoldText = false
            canvas.drawText("If found, please contact: $contact", device.width / 2f, bannerTop + 160f, paint)
        }
    }

    return fullBmp
}

private fun saveSplashToFile(context: Context, bitmap: Bitmap): File {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val targetDir = if (downloadsDir != null && downloadsDir.exists()) downloadsDir else context.filesDir
    val file = File(targetDir, "suspended.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file
}

private fun shareExportedSplash(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share reMarkable Sleep Screen"))
    } catch (_: Exception) {
        // Fallback: simple intent
    }
}
