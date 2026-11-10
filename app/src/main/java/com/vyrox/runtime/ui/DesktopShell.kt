package com.vyrox.runtime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vyrox.engine.LinuxRootFsInfo
import com.vyrox.runtime.core.capsule.CapsuleFileEntry
import com.vyrox.runtime.core.capsule.CapsuleInfo
import com.vyrox.runtime.core.files.ImportedInstaller
import com.vyrox.runtime.core.host.HostCapabilities

private enum class DesktopPanel(val title: String, val shortName: String) {
    OVERVIEW("Workspace", "Home"),
    CAPSULES("Capsule Manager", "Capsules"),
    FILES("VYROX File Explorer", "Files"),
    INSTALLERS("Installer Library", "Installers"),
    RUNTIME("Runtime & Device", "Runtime")
}

private class DesktopWindowState(
    val panel: DesktopPanel,
    x: Float,
    y: Float
) {
    var x by mutableStateOf(x)
    var y by mutableStateOf(y)
    var width by mutableStateOf(720f)
    var height by mutableStateOf(500f)
    var minimized by mutableStateOf(false)
    var maximized by mutableStateOf(false)
}

@Composable
internal fun DesktopShell(
    installers: List<ImportedInstaller>,
    capsules: List<CapsuleInfo>,
    selectedCapsule: CapsuleInfo?,
    selectedDrive: Char,
    currentPath: String,
    capsuleEntries: List<CapsuleFileEntry>,
    capsuleSearchResults: List<CapsuleFileEntry>,
    rootFsImages: List<LinuxRootFsInfo>,
    hostCapabilities: HostCapabilities,
    prootBinaryAvailable: Boolean,
    isImporting: Boolean,
    statusMessage: String?,
    capsuleName: String,
    onCapsuleNameChange: (String) -> Unit,
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    destinationPath: String,
    onDestinationPathChange: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onImport: () -> Unit,
    onDeleteInstaller: (ImportedInstaller) -> Unit,
    onCreateCapsule: () -> Unit,
    onDeleteCapsule: (CapsuleInfo) -> Unit,
    onOpenCapsule: (CapsuleInfo) -> Unit,
    onCloseCapsuleExplorer: () -> Unit,
    onSelectCapsuleDrive: (Char) -> Unit,
    onNavigateCapsuleDirectory: (String) -> Unit,
    onCreateCapsuleDirectory: () -> Unit,
    onImportIntoCapsule: () -> Unit,
    onDeleteCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onRenameCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onCopyCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onMoveCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onSearchCapsuleEntries: () -> Unit,
    onSelectCapsuleSearchResult: (CapsuleFileEntry) -> Unit,
    onImportRootFs: () -> Unit,
    onRemoveRootFs: (LinuxRootFsInfo) -> Unit,
    onTestRootFs: (LinuxRootFsInfo) -> Unit,
    onExtractZipEntry: (CapsuleFileEntry) -> Unit,
    onCloneCapsule: (CapsuleInfo) -> Unit,
    onRenameCapsule: (CapsuleInfo) -> Unit,
    onCreateCapsuleSnapshot: (CapsuleInfo) -> Unit,
    onManageCapsuleSnapshots: (CapsuleInfo) -> Unit,
    onBackupCapsule: (CapsuleInfo) -> Unit,
    onImportCapsuleBackup: () -> Unit,
    onToggleDesktopMode: () -> Unit
) {
    val windows = remember {
        mutableStateListOf(DesktopWindowState(DesktopPanel.OVERVIEW, 30f, 24f))
    }
    var startMenuOpen by remember { mutableStateOf(false) }

    fun openPanel(panel: DesktopPanel) {
        val existingIndex = windows.indexOfFirst { it.panel == panel }
        if (existingIndex >= 0) {
            val existing = windows.removeAt(existingIndex)
            existing.minimized = false
            windows.add(existing)
        } else {
            val offset = (windows.size * 26).toFloat()
            windows.add(
                DesktopWindowState(
                    panel = panel,
                    x = 30f + offset,
                    y = 24f + offset
                )
            )
        }
        startMenuOpen = false
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080D18))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Color(0xFF111A2C))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "VYROX",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "A COMPLETE PC RUNTIME INSIDE ANDROID",
                        color = Color(0xFF9CAAC4),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "${capsules.size} CAPSULES  •  ${installers.size} IMPORTED",
                    color = Color(0xFFB9C6DD),
                    fontSize = 10.sp
                )
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onToggleDesktopMode) {
                    Text("Phone admin")
                }
            }

            Row(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .width(82.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF10182A))
                        .padding(horizontal = 7.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DesktopLauncherButton("VX", "Home") { openPanel(DesktopPanel.OVERVIEW) }
                    DesktopLauncherButton("C", "Capsules") { openPanel(DesktopPanel.CAPSULES) }
                    DesktopLauncherButton("F", "Files") { openPanel(DesktopPanel.FILES) }
                    DesktopLauncherButton("I", "Install") { openPanel(DesktopPanel.INSTALLERS) }
                    DesktopLauncherButton("R", "Runtime") { openPanel(DesktopPanel.RUNTIME) }
                    Spacer(Modifier.weight(1f))
                    Text("ARM64", color = Color(0xFF78D8C2), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("LOCAL", color = Color(0xFF71819D), fontSize = 9.sp)
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF0B1424))
                ) {
                    val viewportWidth = maxWidth
                    val viewportHeight = maxHeight

                    windows.forEachIndexed { index, window ->
                        if (!window.minimized) {
                            DesktopWindowFrame(
                                window = window,
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                                zOrder = index.toFloat(),
                                onActivate = {
                                    val current = windows.indexOf(window)
                                    if (current >= 0 && current != windows.lastIndex) {
                                        windows.removeAt(current)
                                        windows.add(window)
                                    }
                                },
                                onMinimize = { window.minimized = true },
                                onToggleMaximize = { window.maximized = !window.maximized },
                                onClose = { windows.remove(window) }
                            ) {
                                DesktopPanelContent(
                                    panel = window.panel,
                                    installers = installers,
                                    capsules = capsules,
                                    selectedCapsule = selectedCapsule,
                                    selectedDrive = selectedDrive,
                                    currentPath = currentPath,
                                    capsuleEntries = capsuleEntries,
                                    capsuleSearchResults = capsuleSearchResults,
                                    rootFsImages = rootFsImages,
                                    hostCapabilities = hostCapabilities,
                                    prootBinaryAvailable = prootBinaryAvailable,
                                    isImporting = isImporting,
                                    statusMessage = statusMessage,
                                    capsuleName = capsuleName,
                                    onCapsuleNameChange = onCapsuleNameChange,
                                    folderName = folderName,
                                    onFolderNameChange = onFolderNameChange,
                                    destinationPath = destinationPath,
                                    onDestinationPathChange = onDestinationPathChange,
                                    searchQuery = searchQuery,
                                    onSearchQueryChange = onSearchQueryChange,
                                    onImport = onImport,
                                    onDeleteInstaller = onDeleteInstaller,
                                    onCreateCapsule = onCreateCapsule,
                                    onDeleteCapsule = onDeleteCapsule,
                                    onOpenCapsule = onOpenCapsule,
                                    onCloseCapsuleExplorer = onCloseCapsuleExplorer,
                                    onSelectCapsuleDrive = onSelectCapsuleDrive,
                                    onNavigateCapsuleDirectory = onNavigateCapsuleDirectory,
                                    onCreateCapsuleDirectory = onCreateCapsuleDirectory,
                                    onImportIntoCapsule = onImportIntoCapsule,
                                    onDeleteCapsuleEntry = onDeleteCapsuleEntry,
                                    onRenameCapsuleEntry = onRenameCapsuleEntry,
                                    onCopyCapsuleEntry = onCopyCapsuleEntry,
                                    onMoveCapsuleEntry = onMoveCapsuleEntry,
                                    onSearchCapsuleEntries = onSearchCapsuleEntries,
                                    onSelectCapsuleSearchResult = onSelectCapsuleSearchResult,
                                    onImportRootFs = onImportRootFs,
                                    onRemoveRootFs = onRemoveRootFs,
                                    onTestRootFs = onTestRootFs,
                                    onExtractZipEntry = onExtractZipEntry,
                                    onCloneCapsule = onCloneCapsule,
                                    onRenameCapsule = onRenameCapsule,
                                    onCreateCapsuleSnapshot = onCreateCapsuleSnapshot,
                                    onManageCapsuleSnapshots = onManageCapsuleSnapshots,
                                    onBackupCapsule = onBackupCapsule,
                                    onImportCapsuleBackup = onImportCapsuleBackup,
                                    onOpenPanel = ::openPanel
                                )
                            }
                        }
                    }

                    if (startMenuOpen) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .width(250.dp)
                                .zIndex(100f),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF17233A)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("VYROX Start", fontWeight = FontWeight.Bold, color = Color.White)
                                DesktopMenuItem("Workspace") { openPanel(DesktopPanel.OVERVIEW) }
                                DesktopMenuItem("Capsule Manager") { openPanel(DesktopPanel.CAPSULES) }
                                DesktopMenuItem("File Explorer") { openPanel(DesktopPanel.FILES) }
                                DesktopMenuItem("Installer Library") { openPanel(DesktopPanel.INSTALLERS) }
                                DesktopMenuItem("Runtime & Device") { openPanel(DesktopPanel.RUNTIME) }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Color(0xFF10182A))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Button(onClick = { startMenuOpen = !startMenuOpen }) {
                    Text("VYROX")
                }
                windows.forEach { window ->
                    OutlinedButton(
                        onClick = {
                            if (window.minimized) window.minimized = false
                            val index = windows.indexOf(window)
                            if (index >= 0 && index != windows.lastIndex) {
                                windows.removeAt(index)
                                windows.add(window)
                            }
                        },
                        enabled = true
                    ) {
                        Text(window.panel.shortName)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    statusMessage ?: "Private workspace  •  Runtime not yet device-verified",
                    color = Color(0xFF9DACC4),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DesktopLauncherButton(
    symbol: String,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF18253D))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(symbol, color = Color(0xFF91A9FF), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        Text(label, color = Color(0xFFD5DDF0), fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun DesktopMenuItem(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DesktopWindowFrame(
    window: DesktopWindowState,
    viewportWidth: Dp,
    viewportHeight: Dp,
    zOrder: Float,
    onActivate: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val minWidth = minOf(360.dp, viewportWidth)
    val minHeight = minOf(250.dp, viewportHeight)
    val width = if (window.maximized) viewportWidth else window.width.dp.coerceIn(minWidth, viewportWidth)
    val height = if (window.maximized) viewportHeight else window.height.dp.coerceIn(minHeight, viewportHeight)
    val x = if (window.maximized) 0.dp else window.x.dp.coerceIn(0.dp, (viewportWidth - width).coerceAtLeast(0.dp))
    val y = if (window.maximized) 0.dp else window.y.dp.coerceIn(0.dp, (viewportHeight - height).coerceAtLeast(0.dp))

    Card(
        modifier = Modifier
            .offset {
                with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
            }
            .size(width, height)
            .zIndex(zOrder + if (window.maximized) 10f else 0f)
            .clickable(onClick = onActivate),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141F34)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(Color(0xFF202D47))
                    .pointerInput(window.panel) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (!window.maximized) {
                                val dx = with(density) { dragAmount.x.toDp().value }
                                val dy = with(density) { dragAmount.y.toDp().value }
                                window.x = (window.x + dx).coerceIn(
                                    0f,
                                    (viewportWidth - width).value.coerceAtLeast(0f)
                                )
                                window.y = (window.y + dy).coerceIn(
                                    0f,
                                    (viewportHeight - height).value.coerceAtLeast(0f)
                                )
                            }
                            onActivate()
                        }
                    }
                    .padding(start = 14.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    window.panel.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onMinimize) { Text("—") }
                OutlinedButton(onClick = onToggleMaximize) { Text(if (window.maximized) "❐" else "□") }
                OutlinedButton(onClick = onClose) { Text("×") }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                content()
            }
            }
            if (!window.maximized) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(RoundedCornerShape(bottomEnd = 16.dp))
                    .background(Color(0xFF31476B))
                    .pointerInput(window.panel, viewportWidth, viewportHeight) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dx = with(density) { dragAmount.x.toDp().value }
                            val dy = with(density) { dragAmount.y.toDp().value }
                            window.width = (width.value + dx).coerceIn(
                                minWidth.value,
                                viewportWidth.value
                            )
                            window.height = (height.value + dy).coerceIn(
                                minHeight.value,
                                viewportHeight.value
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DesktopPanelContent(
    panel: DesktopPanel,
    installers: List<ImportedInstaller>,
    capsules: List<CapsuleInfo>,
    selectedCapsule: CapsuleInfo?,
    selectedDrive: Char,
    currentPath: String,
    capsuleEntries: List<CapsuleFileEntry>,
    capsuleSearchResults: List<CapsuleFileEntry>,
    rootFsImages: List<LinuxRootFsInfo>,
    hostCapabilities: HostCapabilities,
    prootBinaryAvailable: Boolean,
    isImporting: Boolean,
    statusMessage: String?,
    capsuleName: String,
    onCapsuleNameChange: (String) -> Unit,
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    destinationPath: String,
    onDestinationPathChange: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onImport: () -> Unit,
    onDeleteInstaller: (ImportedInstaller) -> Unit,
    onCreateCapsule: () -> Unit,
    onDeleteCapsule: (CapsuleInfo) -> Unit,
    onOpenCapsule: (CapsuleInfo) -> Unit,
    onCloseCapsuleExplorer: () -> Unit,
    onSelectCapsuleDrive: (Char) -> Unit,
    onNavigateCapsuleDirectory: (String) -> Unit,
    onCreateCapsuleDirectory: () -> Unit,
    onImportIntoCapsule: () -> Unit,
    onDeleteCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onRenameCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onCopyCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onMoveCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onSearchCapsuleEntries: () -> Unit,
    onSelectCapsuleSearchResult: (CapsuleFileEntry) -> Unit,
    onImportRootFs: () -> Unit,
    onRemoveRootFs: (LinuxRootFsInfo) -> Unit,
    onTestRootFs: (LinuxRootFsInfo) -> Unit,
    onExtractZipEntry: (CapsuleFileEntry) -> Unit,
    onCloneCapsule: (CapsuleInfo) -> Unit,
    onRenameCapsule: (CapsuleInfo) -> Unit,
    onCreateCapsuleSnapshot: (CapsuleInfo) -> Unit,
    onManageCapsuleSnapshots: (CapsuleInfo) -> Unit,
    onBackupCapsule: (CapsuleInfo) -> Unit,
    onImportCapsuleBackup: () -> Unit,
    onOpenPanel: (DesktopPanel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (panel) {
            DesktopPanel.OVERVIEW -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF202D47)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Your PC. Your Phone. Your Environment.", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "A managed Android workspace designed for future Windows compatibility. Imported Windows files are stored privately and are not executed yet.",
                            color = Color(0xFFB5C3DA)
                        )
                    }
                }
                HostReadinessCard(hostCapabilities)
                RuntimeEngineStatusCard(hostCapabilities, rootFsImages, prootBinaryAvailable)
            }

            DesktopPanel.CAPSULES -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Capsule workspaces", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Each Capsule has separate app-private files and virtual C:/D: folder layouts. This is logical file separation, not a virtual machine or a hardened security boundary.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                        OutlinedTextField(
                            value = capsuleName,
                            onValueChange = onCapsuleNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("New Capsule name") }
                        )
                        Button(
                            onClick = onCreateCapsule,
                            enabled = capsuleName.trim().length >= 2,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Create Capsule")
                        }
                        OutlinedButton(
                            onClick = onImportCapsuleBackup,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isImporting
                        ) {
                            Text("Import Capsule backup")
                        }
                    }
                }
                if (capsules.isEmpty()) {
                    Text("No Capsule workspaces created yet.", color = Color(0xFFACB9D0))
                } else {
                    capsules.forEach { capsule ->
                        CapsuleRow(
                            capsule = capsule,
                            onOpen = onOpenCapsule,
                            onDelete = onDeleteCapsule,
                            onClone = onCloneCapsule,
                            onRename = onRenameCapsule,
                            onSnapshot = onCreateCapsuleSnapshot,
                            onManageSnapshots = onManageCapsuleSnapshots,
                            onBackup = onBackupCapsule
                        )
                    }
                }
            }

            DesktopPanel.FILES -> {
                if (selectedCapsule == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Select a Capsule first", fontWeight = FontWeight.Bold)
                            Text("The file explorer reads and changes the app-private C: and D: workspace folders. Open a Capsule to browse its files.")
                            Button(onClick = { onOpenPanel(DesktopPanel.CAPSULES) }) {
                                Text("Open Capsule Manager")
                            }
                        }
                    }
                } else {
                    CapsuleExplorerCard(
                        capsule = selectedCapsule,
                        selectedDrive = selectedDrive,
                        currentPath = currentPath,
                        entries = capsuleEntries,
                        searchResults = capsuleSearchResults,
                        rootFsImages = rootFsImages,
                        folderName = folderName,
                        destinationPath = destinationPath,
                        searchQuery = searchQuery,
                        isImporting = isImporting,
                        onFolderNameChange = onFolderNameChange,
                        onDestinationPathChange = onDestinationPathChange,
                        onSearchQueryChange = onSearchQueryChange,
                        onSearch = onSearchCapsuleEntries,
                        onSelectSearchResult = onSelectCapsuleSearchResult,
                        onImportRootFs = onImportRootFs,
                        onRemoveRootFs = onRemoveRootFs,
                        onTestRootFs = onTestRootFs,
                        onExtractZipEntry = onExtractZipEntry,
                        onRenameEntry = onRenameCapsuleEntry,
                        onCopyEntry = onCopyCapsuleEntry,
                        onMoveEntry = onMoveCapsuleEntry,
                        onSelectDrive = onSelectCapsuleDrive,
                        onNavigate = onNavigateCapsuleDirectory,
                        onCreateFolder = onCreateCapsuleDirectory,
                        onImportFile = onImportIntoCapsule,
                        onClose = onCloseCapsuleExplorer,
                        onRequestDelete = onDeleteCapsuleEntry
                    )
                }
            }

            DesktopPanel.INSTALLERS -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("VYROX Installer Library", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Importing stores an EXE/MSI in private app storage. It does not install or run Windows software until a compatible runtime is integrated.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                        Button(onClick = onImport, enabled = !isImporting) {
                            Text(if (isImporting) "Importing..." else "Import EXE / MSI / ZIP")
                        }
                    }
                }
                if (installers.isEmpty()) {
                    Text("No imported installers.", color = Color(0xFFACB9D0))
                } else {
                    installers.forEach { installer ->
                        InstallerRow(installer = installer, onDelete = onDeleteInstaller)
                    }
                }
            }

            DesktopPanel.RUNTIME -> {
                HostReadinessCard(hostCapabilities)
                RuntimeEngineStatusCard(hostCapabilities, rootFsImages, prootBinaryAvailable)
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(statusMessage, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
        }
    }
}

