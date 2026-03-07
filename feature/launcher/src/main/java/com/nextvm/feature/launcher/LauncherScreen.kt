package com.nextvm.feature.launcher

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextvm.core.model.EngineStatus
import com.nextvm.core.model.LauncherItem
import com.nextvm.core.model.SystemApp
import com.nextvm.core.model.SystemAppIcon
import com.nextvm.core.model.VirtualApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    onNavigateToInstall: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToRoute: (String) -> Unit = {},
    viewModel: LauncherViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LauncherEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is LauncherEffect.AppLaunched -> snackbarHostState.showSnackbar("Launched ${effect.appName}")
                is LauncherEffect.AppUninstalled -> snackbarHostState.showSnackbar("Uninstalled ${effect.appName}")
                is LauncherEffect.NavigateToRoute -> onNavigateToRoute(effect.route)
            }
        }
    }

    // Show initialization screen while engine is starting up
    if (uiState.engineStatus != EngineStatus.READY) {
        InitializingScreen(status = uiState.engineStatus)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NEXTVM",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (uiState.virtualAppCount > 0) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "${uiState.virtualAppCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onIntent(LauncherIntent.Refresh) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToInstall,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Install", fontWeight = FontWeight.SemiBold) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.error != null -> ErrorState(
                    error = uiState.error!!,
                    onRetry = { viewModel.onIntent(LauncherIntent.Refresh) }
                )
                uiState.launcherItems.isEmpty() -> EmptyState(onInstall = onNavigateToInstall)
                else -> LauncherGrid(
                    items = uiState.launcherItems,
                    onVirtualAppClick = { viewModel.onIntent(LauncherIntent.LaunchApp(it)) },
                    onVirtualAppLongClick = { viewModel.onIntent(LauncherIntent.ShowAppOptions(it)) },
                    onUninstall = { viewModel.onIntent(LauncherIntent.UninstallApp(it)) },
                    onSystemAppClick = { viewModel.onIntent(LauncherIntent.LaunchSystemApp(it.route)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherGrid(
    items: List<LauncherItem>,
    onVirtualAppClick: (VirtualApp) -> Unit,
    onVirtualAppLongClick: (VirtualApp) -> Unit,
    onUninstall: (VirtualApp) -> Unit,
    onSystemAppClick: (SystemApp) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(
            items,
            key = { _, item ->
                when (item) {
                    is LauncherItem.Virtual -> item.app.instanceId
                    is LauncherItem.System -> item.systemApp.id
                }
            }
        ) { index, item ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(index * 40L)
                visible = true
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.85f),
            ) {
                when (item) {
                    is LauncherItem.Virtual -> AppGridItem(
                        app = item.app,
                        onClick = { onVirtualAppClick(item.app) },
                        onLongClick = { onVirtualAppLongClick(item.app) },
                        onUninstall = { onUninstall(item.app) }
                    )
                    is LauncherItem.System -> SystemAppGridItem(
                        systemApp = item.systemApp,
                        onClick = { onSystemAppClick(item.systemApp) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SystemAppGridItem(
    systemApp: SystemApp,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon container with gradient background
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                val icon = when (systemApp.iconType) {
                    SystemAppIcon.FILE_MANAGER -> Icons.Default.Folder
                }
                Icon(
                    imageVector = icon,
                    contentDescription = systemApp.appName,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = systemApp.appName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridItem(
    app: VirtualApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUninstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    showMenu = true
                    onLongClick()
                }
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon container
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon!!.toBitmap(128, 128).asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = app.appName,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Running indicator
                if (app.isRunning) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(600, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = pulseAlpha))
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = app.appName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            // Context menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Uninstall") },
                    onClick = {
                        showMenu = false
                        onUninstall()
                    }
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Loading apps...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyState(onInstall: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "emptyFloat")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "floatAnim"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .offset(y = offsetY.dp)
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "No Virtual Apps Yet",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Install an APK to run apps inside\nyour secure virtual sandbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onInstall,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Install APK", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Initialization screen shown while the virtual engine is starting up.
 * Displayed while all engine subsystems are being initialized.
 */
@Composable
private fun InitializingScreen(status: EngineStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "init-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(64.dp)
        ) {
            // Animated logo
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Widgets,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = "NEXTVM",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = when (status) {
                    EngineStatus.NOT_INITIALIZED -> "Starting up..."
                    EngineStatus.INITIALIZING -> "Setting up virtual environment..."
                    EngineStatus.ERROR -> "Initialization failed"
                    EngineStatus.READY -> "Ready"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (status != EngineStatus.ERROR) {
                Spacer(modifier = Modifier.height(32.dp))

                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                )

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Configuring sandbox & connecting services",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
