package com.unremarkable.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unremarkable.android.ui.theme.Zinc800
import com.unremarkable.android.ui.theme.Zinc900
import com.unremarkable.core.model.DocumentItem
import kotlinx.coroutines.delay

@Composable
fun ReaderScreen(
    document: DocumentItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPage by remember { mutableIntStateOf(1) }
    val totalPages = document.pageCount.coerceAtLeast(1)
    var isDarkMode by remember { mutableStateOf(false) }
    var isHudVisible by remember { mutableStateOf(true) }

    // Auto-hide HUD after 2.5 seconds
    LaunchedEffect(isHudVisible) {
        if (isHudVisible) {
            delay(2500)
            isHudVisible = false
        }
    }

    val canvasBackground = if (isDarkMode) Zinc900 else Color.White
    val inkColor = if (isDarkMode) Color(0xFFF4F4F5) else Color(0xFF18181B)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBackground)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val screenWidth = size.width
                    val x = offset.x
                    when {
                        x < screenWidth * 0.25f -> {
                            // Left 25%: Previous page
                            if (currentPage > 1) currentPage--
                        }
                        x > screenWidth * 0.75f -> {
                            // Right 25%: Next page
                            if (currentPage < totalPages) currentPage++
                        }
                        else -> {
                            // Center 50%: Toggle HUD
                            isHudVisible = !isHudVisible
                        }
                    }
                }
            }
    ) {
        // Document Content Placeholder / Render Area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = document.visibleName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = inkColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = if (isDarkMode) Zinc800 else Color(0xFFF4F4F5),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = null,
                            tint = inkColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Page $currentPage of $totalPages",
                            style = MaterialTheme.typography.bodyLarge,
                            color = inkColor.copy(alpha = 0.8f)
                        )
                        Text(
                            text = if (isDarkMode) "Perceptual OKLCH Dark Mode Active" else "Paper Mode Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = inkColor.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // Auto-Hiding Top HUD
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Zinc900.copy(alpha = 0.92f),
                contentColor = Color.White,
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }

                    Text(
                        text = document.visibleName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isDarkMode = !isDarkMode }) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle Dark Mode"
                            )
                        }
                    }
                }
            }
        }

        // Auto-Hiding Bottom HUD (Page Controls)
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Surface(
                color = Zinc900.copy(alpha = 0.92f),
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = { if (currentPage > 1) currentPage-- },
                        enabled = currentPage > 1
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                    }

                    Text(
                        text = "$currentPage / $totalPages",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = { if (currentPage < totalPages) currentPage++ },
                        enabled = currentPage < totalPages
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Page")
                    }
                }
            }
        }
    }
}
