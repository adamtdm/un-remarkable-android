package com.unremarkable.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unremarkable.core.converter.ConverterConfig
import com.unremarkable.core.converter.QualityProfiles
import com.unremarkable.core.model.DeviceProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterSheet(
    fileName: String,
    isConverting: Boolean = false,
    progressMessage: String = "",
    onDismiss: () -> Unit,
    onStartConversion: (ConverterConfig) -> Unit
) {
    var selectedProfile by remember { mutableStateOf(DeviceProfile.PAPER_PRO) }
    var qualityStop by remember { mutableFloatStateOf(3f) }
    var splitSpreads by remember { mutableStateOf(true) }
    var mangaOrder by remember { mutableStateOf(false) }

    val currentProfile = QualityProfiles.getProfile(qualityStop.toInt())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚡ Document & Comic Optimizer",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Target Device Profile
            Text(text = "Target Device Profile", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val profiles = listOf(DeviceProfile.PAPER_PRO, DeviceProfile.RM2, DeviceProfile.RM1)
                profiles.forEachIndexed { index, profile ->
                    SegmentedButton(
                        selected = selectedProfile == profile,
                        onClick = { selectedProfile = profile },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = profiles.size)
                    ) {
                        Text(if (profile == DeviceProfile.PAPER_PRO) "Paper Pro" else profile.displayName.replace("reMarkable ", ""))
                    }
                }
            }

            // 5-Stop Quality Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Quality Stop: ${currentProfile.name} (${currentProfile.stop}/5)", style = MaterialTheme.typography.labelLarge)
            }

            Slider(
                value = qualityStop,
                onValueChange = { qualityStop = it },
                valueRange = 1f..5f,
                steps = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = currentProfile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Split two-page spreads", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Detects landscape double pages and splits into portrait halves",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = splitSpreads,
                    onCheckedChange = { splitSpreads = it }
                )
            }

            if (splitSpreads) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Manga reading order (RTL)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Reads right-to-left: splits right half first, then left half",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = mangaOrder,
                        onCheckedChange = { mangaOrder = it }
                    )
                }
            }

            if (isConverting) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(text = progressMessage, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Button(
                    onClick = {
                        val config = ConverterConfig(
                            targetProfile = selectedProfile,
                            qualityStop = qualityStop.toInt(),
                            splitSpreads = splitSpreads,
                            mangaOrder = mangaOrder
                        )
                        onStartConversion(config)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Start Optimization & Send to Tablet")
                }
            }
        }
    }
}
