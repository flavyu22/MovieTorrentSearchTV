package io.github.flavyu22.movietorrentsearchtv.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.flavyu22.movietorrentsearchtv.model.AppStrings
import io.github.flavyu22.movietorrentsearchtv.ui.theme.SoftBlue
import io.github.flavyu22.movietorrentsearchtv.model.DiscoveredServer
import io.github.flavyu22.movietorrentsearchtv.ui.components.FocusableIconButton
import io.github.flavyu22.movietorrentsearchtv.viewmodel.TorrserverViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URI

@Composable
fun OptimizedTorrserverDialog(
    s: AppStrings,
    torrserverViewModel: TorrserverViewModel,
    onDismiss: () -> Unit
) {
    val config by torrserverViewModel.config.collectAsStateWithLifecycle()
    val discoveredServers by torrserverViewModel.discoveredServers.collectAsStateWithLifecycle()
    val connectionState by torrserverViewModel.connectionState.collectAsStateWithLifecycle()
    val issue by torrserverViewModel.issue.collectAsStateWithLifecycle()
    val isScanning by torrserverViewModel.isScanning.collectAsStateWithLifecycle()
    val manualAddress by torrserverViewModel.manualAddress.collectAsStateWithLifecycle()

    var showManualInput by remember { mutableStateOf(false) }
    var focusFirstResultAfterScan by remember { mutableStateOf(false) }

    // Focus management pentru TV
    val scanButtonFocus = remember { FocusRequester() }
    val closeButtonFocus = remember { FocusRequester() }
    val firstServerFocus = remember { FocusRequester() }
    val manualInputFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { scanButtonFocus.requestFocus() }
    }

    // Auto-focus pe primul server cand se gasesc servere
    LaunchedEffect(isScanning, discoveredServers) {
        if (focusFirstResultAfterScan && !isScanning && discoveredServers.isNotEmpty()) {
            focusFirstResultAfterScan = false
            androidx.compose.runtime.withFrameNanos { }
            runCatching { firstServerFocus.requestFocus() }
        }
    }

    LaunchedEffect(showManualInput) {
        if (showManualInput) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { manualInputFocus.requestFocus() }
        }
    }

    val selectedEndpoint = remember(config.primaryAddress) {
        runCatching {
            URI(config.primaryAddress).let { uri ->
                val effectivePort = when {
                    uri.port >= 0 -> uri.port
                    uri.scheme.equals("https", ignoreCase = true) -> 443
                    else -> 80
                }
                uri.host to effectivePort
            }
        }.getOrNull()
    }
    fun issueMessage(
        currentIssue: TorrserverViewModel.TorrserverIssue,
        address: String? = null,
    ): String = when (currentIssue) {
        TorrserverViewModel.TorrserverIssue.INVALID_ADDRESS -> s.invalidServerAddress
        TorrserverViewModel.TorrserverIssue.NO_SERVER_FOUND -> s.noServersFound
        TorrserverViewModel.TorrserverIssue.SCAN_FAILED -> s.serverScanFailed
        TorrserverViewModel.TorrserverIssue.SERVER_UNREACHABLE ->
            address?.let { s.connectionFailed.format(it) } ?: s.serverUnreachable
    }

    val displayedStatus = remember(connectionState, issue, s) {
        when (val state = connectionState) {
            is TorrserverViewModel.ConnectionState.Connected ->
                s.connectionSucceeded.format(state.address, state.latency) to true
            is TorrserverViewModel.ConnectionState.Error ->
                issueMessage(state.issue, state.address) to false
            TorrserverViewModel.ConnectionState.Disconnected ->
                issue?.let { issueMessage(it) to false }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            border = BorderStroke(1.dp, Color(0xFF333333))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.torrserverSettings,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Buton inchidere
                        val closeInteraction = remember { MutableInteractionSource() }
                        val isCloseFocused by closeInteraction.collectIsFocusedAsState()

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.focusRequester(closeButtonFocus),
                            interactionSource = closeInteraction
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = s.close,
                                tint = if (isCloseFocused) Color.White else Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Status curent
                if (config.primaryAddress.isNotEmpty()) {
                    item {
                        Surface(
                            color = Color(0xFF2A4A3A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = s.torrserverConnected.format(config.primaryAddress),
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        s.configuredServer,
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        config.primaryAddress,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                    item {
                        val disableInteraction = remember { MutableInteractionSource() }
                        val isDisableFocused by disableInteraction.collectIsFocusedAsState()
                        OutlinedButton(
                            onClick = torrserverViewModel::disableTorrserver,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            interactionSource = disableInteraction,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isDisableFocused) Color.White else Color.Transparent,
                                contentColor = if (isDisableFocused) Color.Black else Color.White,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isDisableFocused) Color.White else Color(0xFFE57373),
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(
                                Icons.Default.PowerSettingsNew,
                                contentDescription = s.disableTorrserver,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(s.disableTorrserver)
                        }
                    }
                }

                // Butoane actiuni
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Scan Button
                        val scanInteraction = remember { MutableInteractionSource() }
                        val isScanFocused by scanInteraction.collectIsFocusedAsState()

                        Button(
                            onClick = {
                                focusFirstResultAfterScan = true
                                torrserverViewModel.scanNetwork()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .focusRequester(scanButtonFocus),
                            interactionSource = scanInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isScanFocused) Color.White else SoftBlue
                            ),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isScanning
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    color = if (isScanFocused) SoftBlue else Color.White,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    s.scanning,
                                    color = if (isScanFocused) SoftBlue else Color.White
                                )
                            } else {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = s.scanNetwork,
                                    tint = if (isScanFocused) SoftBlue else Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    s.scanNetwork,
                                    color = if (isScanFocused) SoftBlue else Color.White
                                )
                            }
                        }

                        // Manual Input Toggle
                        val manualInteraction = remember { MutableInteractionSource() }
                        val isManualFocused by manualInteraction.collectIsFocusedAsState()

                        Button(
                            onClick = { showManualInput = !showManualInput },
                            modifier = Modifier.height(50.dp),
                            interactionSource = manualInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isManualFocused) Color.White else Color(0xFF444444)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                if (showManualInput) Icons.Default.KeyboardHide else Icons.Default.Edit,
                                contentDescription = s.torrserverSettings,
                                tint = if (isManualFocused) Color.Black else Color.White
                            )
                        }
                    }
                }

                // Input manual
                if (showManualInput) {
                    item {
                        OutlinedTextField(
                            value = manualAddress,
                            onValueChange = { torrserverViewModel.updateManualAddress(it) },
                            label = { Text(s.manualAddressHint, color = Color.Gray) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(manualInputFocus),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = SoftBlue,
                                unfocusedBorderColor = Color(0xFF444444)
                            ),
                            singleLine = true,
                            trailingIcon = {
                                Row {
                                    // Test button
                                    FocusableIconButton(
                                        icon = Icons.Default.NetworkCheck,
                                        contentDescription = s.refresh,
                                        tintNormal = SoftBlue,
                                        onClick = { torrserverViewModel.testConnection(manualAddress) }
                                    )
                                    // Save button
                                    FocusableIconButton(
                                        icon = Icons.Default.Save,
                                        contentDescription = s.save,
                                        tintNormal = Color(0xFF4CAF50),
                                        onClick = {
                                            if (torrserverViewModel.saveManualAddress(manualAddress)) {
                                                showManualInput = false
                                            }
                                        }
                                    )
                                }
                            }
                        )
                    }
                }

                // Error/Success Message
                displayedStatus?.let { (message, isSuccess) ->
                    item {
                        Surface(
                            color = if (isSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    liveRegion = if (isSuccess) LiveRegionMode.Polite else LiveRegionMode.Assertive
                                    contentDescription = message
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSuccess) Icons.Default.Check else Icons.Default.Error,
                                    contentDescription = message,
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    message,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!isSuccess) {
                                    IconButton(
                                        onClick = { torrserverViewModel.clearError() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = s.cancel,
                                            tint = Color.White,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Lista servere descoperite - Header
                item {
                    Text(
                        s.availableServers.format(discoveredServers.size),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                if (discoveredServers.isEmpty() && !isScanning) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Router,
                                    contentDescription = s.availableServers.format(0),
                                    tint = Color.Gray,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    s.noServersFound,
                                    color = Color.Gray,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = discoveredServers,
                        key = { it.address ?: "${it.ip}:${it.port}" }
                    ) { server ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        val isFirst = discoveredServers.firstOrNull() == server

                        ServerItem(
                            server = server,
                            isSelected = selectedEndpoint?.let { endpoint ->
                                endpoint.first == server.ip && endpoint.second == server.port
                            } == true,
                            isFocused = isFocused,
                            modifier = if (isFirst) Modifier.focusRequester(firstServerFocus) else Modifier,
                            interactionSource = interactionSource,
                            selectedDescription = s.selected,
                            onClick = {
                                torrserverViewModel.selectDiscoveredServer(server)
                            }
                        )
                    }
                }

                // Buton Confirmare (Gata)
                item {
                    val doneInteraction = remember { MutableInteractionSource() }
                    val isDoneFocused by doneInteraction.collectIsFocusedAsState()
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(top = 8.dp),
                        interactionSource = doneInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDoneFocused) Color.White else SoftBlue,
                            contentColor = if (isDoneFocused) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = s.confirm,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Info footer
                item {
                    Text(
                        s.torrserverInfo,
                        color = Color.Gray.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerItem(
    server: DiscoveredServer,
    isSelected: Boolean,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource,
    selectedDescription: String,
    onClick: () -> Unit
) {
    val displayAddress = server.address ?: "${server.ip}:${server.port}"
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(server.name)
                    append(", $displayAddress, ${server.latencyMs} ms")
                    if (isSelected) append(", $selectedDescription")
                }
            },
        interactionSource = interactionSource,
        color = when {
            isFocused -> Color.White
            isSelected -> SoftBlue.copy(alpha = 0.2f)
            else -> Color(0xFF2A2A2A)
        },
        shape = RoundedCornerShape(8.dp),
        border = if (isSelected && !isFocused) BorderStroke(2.dp, SoftBlue) else null
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon status
            Surface(
                color = if (server.isOnline) Color(0xFF4CAF50) else Color(0xFFFFA726),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.size(8.dp)
            ) {}

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    color = if (isFocused) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = displayAddress,
                    color = if (isFocused) Color.DarkGray else Color.Gray,
                    fontSize = 12.sp
                )
            }

            // Latency
            Text(
                text = "${server.latencyMs}ms",
                color = when {
                    isFocused -> Color.DarkGray
                    server.latencyMs < 50 -> Color(0xFF4CAF50)
                    server.latencyMs < 100 -> Color(0xFFFFA726)
                    else -> Color(0xFFE53935)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Selected indicator
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = selectedDescription,
                    tint = if (isFocused) Color.Black else SoftBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
