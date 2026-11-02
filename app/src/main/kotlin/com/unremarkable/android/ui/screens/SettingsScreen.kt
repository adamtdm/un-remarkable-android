package com.unremarkable.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unremarkable.android.storage.AppSettings
import com.unremarkable.android.storage.ThemeMode
import com.unremarkable.core.model.DeviceProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isAuthenticated: Boolean,
    deviceId: String,
    totalCacheSizeMb: Float,
    cachedBlobCount: Int,
    appSettings: AppSettings,
    onBack: () -> Unit,
    onPairAccount: () -> Unit,
    onUnpairAccount: () -> Unit,
    onForceResync: () -> Unit,
    onClearCache: () -> Unit,
    onThemeChanged: (ThemeMode) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showUnpairDialog by remember { mutableStateOf(false) }
    var currentTheme by remember { mutableStateOf(appSettings.themeMode) }
    var currentProfile by remember { mutableStateOf(appSettings.defaultDeviceProfile) }
    var autoSync by remember { mutableStateOf(appSettings.autoSyncOnStart) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. Account Section
            Text("reMarkable Account", style = MaterialTheme.typography.titleMedium)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isAuthenticated) "Cloud Sync Connected" else "Not Connected",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (isAuthenticated) "Device ID: ${deviceId.take(8)}..." else "Pair to sync your library",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isAuthenticated) {
                            Button(
                                onClick = { showUnpairDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Unpair")
                            }
                        } else {
                            Button(
                                onClick = onPairAccount,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Pair Now")
                            }
                        }
                    }
                }
            }

            // 2. Appearance Section
            Text("Appearance & Theme", style = MaterialTheme.typography.titleMedium)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = currentTheme == ThemeMode.SYSTEM,
                            onClick = {
                                currentTheme = ThemeMode.SYSTEM
                                appSettings.themeMode = ThemeMode.SYSTEM
                                onThemeChanged(ThemeMode.SYSTEM)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) {
                            Text("System")
                        }
                        SegmentedButton(
                            selected = currentTheme == ThemeMode.DARK,
                            onClick = {
                                currentTheme = ThemeMode.DARK
                                appSettings.themeMode = ThemeMode.DARK
                                onThemeChanged(ThemeMode.DARK)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) {
                            Text("Dark (OKLCH)")
                        }
                        SegmentedButton(
                            selected = currentTheme == ThemeMode.LIGHT,
                            onClick = {
                                currentTheme = ThemeMode.LIGHT
                                appSettings.themeMode = ThemeMode.LIGHT
                                onThemeChanged(ThemeMode.LIGHT)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) {
                            Text("Paper Light")
                        }
                    }
                }
            }

            // 3. Default Hardware Target Profile
            Text("Default Device Profile", style = MaterialTheme.typography.titleMedium)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val profiles = listOf(DeviceProfile.PAPER_PRO, DeviceProfile.RM2, DeviceProfile.RM1)
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(profile.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${profile.width}×${profile.height} (${if (profile.isColor) "Color Canvas" else "Monochrome"})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RadioButton(
                                selected = currentProfile == profile,
                                onClick = {
                                    currentProfile = profile
                                    appSettings.defaultDeviceProfile = profile
                                }
                            )
                        }
                    }
                }
            }

            // 4. Synchronization & Cache
            Text("Sync & Storage Cache", style = MaterialTheme.typography.titleMedium)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-sync on startup", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Checks for new notes when app opens",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoSync,
                            onCheckedChange = {
                                autoSync = it
                                appSettings.autoSyncOnStart = it
                            }
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Local Blob Cache", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "$cachedBlobCount blobs cached (${String.format(java.util.Locale.US, "%.1f", totalCacheSizeMb)} MB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onClearCache) {
                            Text("Clear")
                        }
                    }

                    HorizontalDivider()

                    OutlinedButton(
                        onClick = onForceResync,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Force Resync Entire Cloud Library")
                    }
                }
            }

            // App info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Un-remarkable for Android v1.0.0",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Open Source • Zero-Jailbreak Cloud Companion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair Account?") },
            text = { Text("This will unlink your device token and clear your local library and cached files.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnpairDialog = false
                        onUnpairAccount()
                    }
                ) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
