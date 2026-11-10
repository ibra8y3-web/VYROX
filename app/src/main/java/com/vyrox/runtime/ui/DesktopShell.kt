package com.vyrox.runtime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vyrox.engine.LinuxRootFsInfo
import com.vyrox.engine.WinePrefixInfo
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
    box64Installed: Boolean,
    winePrefixInfo: WinePrefixInfo?,
    isImporting: Boolean,
    activeRuntimeLabel: String?,
    isStoppingRuntimeProcess: Boolean,
    onStopRuntimeProcess: () -> Unit,
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
    onBuildBox64: (LinuxRootFsInfo) -> Unit,
    onCheckWinePackages: (LinuxRootFsInfo) -> Unit,
    onExtractZipEntry: (CapsuleFileEntry) -> Unit,
    onCloneCapsule: (CapsuleInfo) -> Unit,
    onRenameCapsule: (CapsuleInfo) -> Unit,
    onCreateCapsuleSnapshot: (CapsuleInfo) -> Unit,
    onManageCapsuleSnapshots: (CapsuleInfo) -> Unit,
    onBackupCapsule: (CapsuleInfo) -> Unit,
    onImportCapsuleBackup: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit
) {
    var selectedPanel by remember { mutableStateOf(DesktopPanel.OVERVIEW) }
    var settingsOpen by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF09172B),
                        Color(0xFF10294A),
                        Color(0xFF171D3D),
                        Color(0xFF090F20)
                    )
                )
            )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val glowA = Offset(size.width * 0.80f, size.height * 0.18f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF4D8DEB).copy(alpha = 0.30f), Color.Transparent),
                        center = glowA,
                        radius = size.width * 0.58f
                    ),
                    radius = size.width * 0.58f,
                    center = glowA
                )
                val glowB = Offset(size.width * 0.12f, size.height * 0.88f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF7A55C7).copy(alpha = 0.20f), Color.Transparent),
                        center = glowB,
                        radius = size.width * 0.48f
                    ),
                    radius = size.width * 0.48f,
                    center = glowB
                )
                val stars = listOf(
                    Offset(0.08f, 0.14f), Offset(0.22f, 0.32f), Offset(0.38f, 0.12f),
                    Offset(0.57f, 0.22f), Offset(0.72f, 0.42f), Offset(0.90f, 0.34f),
                    Offset(0.16f, 0.72f), Offset(0.47f, 0.82f), Offset(0.84f, 0.76f)
                )
                stars.forEachIndexed { index, point ->
                    drawCircle(
                        color = Color.White.copy(alpha = if (index % 3 == 0) 0.44f else 0.20f),
                        radius = if (index % 3 == 0) 2.1f else 1.2f,
                        center = Offset(size.width * point.x, size.height * point.y)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xB3162742))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VYROX", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                        Text(vxText("A desktop workspace inside Android"), color = Color(0xFFB6C9E8), fontSize = 11.sp)
                    }
                    Text(
                        vxText("Environments") + "  " + capsules.size + "   •   " +
                            vxText("Imported files") + "  " + installers.size,
                        color = Color(0xFFD5E2F7),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { settingsOpen = true }) {
                        Text("⚙  " + vxText("Settings"))
                    }
                }

                activeRuntimeLabel?.let { label ->
                    ActiveRuntimeOperationCard(
                        label = label,
                        isStopping = isStoppingRuntimeProcess,
                        onStop = onStopRuntimeProcess,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!statusMessage.isNullOrBlank()) {
                    Text(statusMessage, color = Color(0xFFFFD58D), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedPanel == DesktopPanel.OVERVIEW) {
                        DesktopHomePanel(
                            capsules = capsules,
                            installers = installers,
                            selectedCapsule = selectedCapsule,
                            onOpenCapsule = {
                                onOpenCapsule(it)
                                selectedPanel = DesktopPanel.FILES
                            },
                            onOpenPanel = { selectedPanel = it },
                            onImport = onImport
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color(0xD9142036),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        vxText(selectedPanel.title),
                                        modifier = Modifier.weight(1f),
                                        color = Color.White,
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    OutlinedButton(onClick = { selectedPanel = DesktopPanel.OVERVIEW }) {
                                        Text(vxText("Desktop"))
                                    }
                                }
                                DesktopPanelContent(
                                    panel = selectedPanel,
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
                                    box64Installed = box64Installed,
                                    winePrefixInfo = winePrefixInfo,
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
                                    onBuildBox64 = onBuildBox64,
                                    onCheckWinePackages = onCheckWinePackages,
                                    onExtractZipEntry = onExtractZipEntry,
                                    onCloneCapsule = onCloneCapsule,
                                    onRenameCapsule = onRenameCapsule,
                                    onCreateCapsuleSnapshot = onCreateCapsuleSnapshot,
                                    onManageCapsuleSnapshots = onManageCapsuleSnapshots,
                                    onBackupCapsule = onBackupCapsule,
                                    onImportCapsuleBackup = onImportCapsuleBackup,
                                    onOpenPanel = { selectedPanel = it }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xB3162742))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DesktopDockButton("⌂", "Home") { selectedPanel = DesktopPanel.OVERVIEW }
                    DesktopDockButton("▦", "Capsules") { selectedPanel = DesktopPanel.CAPSULES }
                    DesktopDockButton("▤", "Files") { selectedPanel = DesktopPanel.FILES }
                    DesktopDockButton("⇧", "Installers") { selectedPanel = DesktopPanel.INSTALLERS }
                    DesktopDockButton("◈", "Runtime") { selectedPanel = DesktopPanel.RUNTIME }
                    DesktopDockButton("⚙", "Settings") { settingsOpen = true }
                }
            }

            if (settingsOpen) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f))
                        .clickable { settingsOpen = false }
                )
                Card(
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(340.dp)
                        .padding(12.dp)
                        .zIndex(2f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF172740)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vxText("Settings"), modifier = Modifier.weight(1f), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { settingsOpen = false }) { Text(vxText("Close")) }
                        }
                        Text(vxText("Workspace and files"), color = Color(0xFF8EABD4), fontWeight = FontWeight.SemiBold)
                        SettingsEntry("▦", "Capsule Manager", "Create, open, clone and back up environments") {
                            selectedPanel = DesktopPanel.CAPSULES
                            settingsOpen = false
                        }
                        SettingsEntry("▤", "File Explorer", "Manage each environment's C: and D: files") {
                            selectedPanel = DesktopPanel.FILES
                            settingsOpen = false
                        }
                        SettingsEntry("⇧", "Installer Library", "Import and inspect EXE, MSI and ZIP files") {
                            selectedPanel = DesktopPanel.INSTALLERS
                            settingsOpen = false
                        }
                        Text(vxText("Runtime and device"), color = Color(0xFF8EABD4), fontWeight = FontWeight.SemiBold)
                        SettingsEntry("◈", "Linux and Box64", "Manage RootFS, test PRoot and build Box64") {
                            selectedPanel = DesktopPanel.RUNTIME
                            settingsOpen = false
                        }
                        Text(vxText("Language"), color = Color(0xFF8EABD4), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onLanguageChange("ar") },
                                enabled = language != "ar",
                                modifier = Modifier.weight(1f)
                            ) { Text("العربية") }
                            OutlinedButton(
                                onClick = { onLanguageChange("en") },
                                enabled = language != "en",
                                modifier = Modifier.weight(1f)
                            ) { Text("English") }
                        }
                        OutlinedButton(
                            onClick = {
                                settingsOpen = false
                                onToggleDesktopMode()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(vxText("Switch orientation")) }
                        Text(
                            vxText("Windows execution remains disabled until Wine and its per-environment prefix are verified."),
                            color = Color(0xFFB6C9E8),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopHomePanel(
    capsules: List<CapsuleInfo>,
    installers: List<ImportedInstaller>,
    selectedCapsule: CapsuleInfo?,
    onOpenCapsule: (CapsuleInfo) -> Unit,
    onOpenPanel: (DesktopPanel) -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(vxText("Your workspace"), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(vxText("A clean desktop for your Android-powered PC environment."), color = Color(0xFFB6C9E8), fontSize = 13.sp)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color(0x80304E79)), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(vxText("Current environment"), color = Color(0xFFB6C9E8), fontSize = 10.sp)
                    Text(selectedCapsule?.name ?: vxText("Not selected"), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(vxText("Desktop shortcuts"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DesktopShortcut("▦", "Capsules", "Environments") { onOpenPanel(DesktopPanel.CAPSULES) }
            DesktopShortcut("▤", "Files", "C:  •  D:") { onOpenPanel(DesktopPanel.FILES) }
            DesktopShortcut("⇧", "Installers", installers.size.toString() + " imported") { onOpenPanel(DesktopPanel.INSTALLERS) }
            DesktopShortcut("◈", "Runtime", "Linux  •  Box64") { onOpenPanel(DesktopPanel.RUNTIME) }
            capsules.forEach { capsule ->
                DesktopShortcut("▣", capsule.name, "Open environment") { onOpenCapsule(capsule) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(vxText("Installed applications"), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    vxText("Windows programs will appear here after a real Wine installation succeeds."),
                    color = Color(0xFF9FB5D5),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = onImport) { Text(vxText("Import setup file")) }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0x70203352)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("＋", color = Color(0xFF9CB8FF), fontSize = 30.sp, fontWeight = FontWeight.Light)
                Column(modifier = Modifier.weight(1f)) {
                    Text(vxText("No Windows apps installed yet"), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        vxText("Imported EXE and MSI files are setup files only. They are not displayed as installed apps until Wine installation is connected and verified."),
                        color = Color(0xFFB6C9E8),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (capsules.isEmpty()) {
            OutlinedButton(onClick = { onOpenPanel(DesktopPanel.CAPSULES) }) {
                Text(vxText("Create your first environment"))
            }
        }
    }
}

@Composable
private fun DesktopShortcut(glyph: String, title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x80304A70))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier.size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF5F8DE0), Color(0xFF6E65C8)))),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Text(vxText(title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
        Text(vxText(subtitle), color = Color(0xFFB9CBE5), fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun DesktopDockButton(glyph: String, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(glyph, color = Color(0xFF9EB9FF), fontSize = 20.sp)
        Text(vxText(label), color = Color(0xFFE0E9F8), fontSize = 9.sp)
    }
}

@Composable
private fun SettingsEntry(glyph: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF223753))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(glyph, color = Color(0xFF9EB9FF), fontSize = 21.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(vxText(title), color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(vxText(subtitle), color = Color(0xFFB6C9E8), fontSize = 11.sp)
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
    winePrefixInfo: WinePrefixInfo?,
    onCheckWinePackages: (LinuxRootFsInfo) -> Unit,
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
    onBuildBox64: (LinuxRootFsInfo) -> Unit,
    box64Installed: Boolean,
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
                        Text(vxText("Your PC. Your Phone. Your Environment."), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "A managed Android workspace designed for future Windows compatibility. Imported Windows files are stored privately and are not executed yet.",
                            color = Color(0xFFB5C3DA)
                        )
                    }
                }
                HostReadinessCard(hostCapabilities)
                RuntimeEngineStatusCard(hostCapabilities, rootFsImages, prootBinaryAvailable, box64Installed)
            }

            DesktopPanel.CAPSULES -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(vxText("Capsule workspaces"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Each Capsule has separate app-private files and virtual C:/D: folder layouts. This is logical file separation, not a virtual machine or a hardened security boundary.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                        OutlinedTextField(
                            value = capsuleName,
                            onValueChange = onCapsuleNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(vxText("New Capsule name")) }
                        )
                        Button(
                            onClick = onCreateCapsule,
                            enabled = capsuleName.trim().length >= 2,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(vxText("Create Capsule"))
                        }
                        OutlinedButton(
                            onClick = onImportCapsuleBackup,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isImporting
                        ) {
                            Text(vxText("Import Capsule backup"))
                        }
                    }
                }
                if (capsules.isEmpty()) {
                    Text(vxText("No Capsule workspaces created yet."), color = Color(0xFFACB9D0))
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
                            Text(vxText("Select a Capsule first"), fontWeight = FontWeight.Bold)
                            Text(vxText("The file explorer reads and changes the app-private C: and D: workspace folders. Open a Capsule to browse its files."))
                            Button(onClick = { onOpenPanel(DesktopPanel.CAPSULES) }) {
                                Text(vxText("Open Capsule Manager"))
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
                        onBuildBox64 = onBuildBox64,
                        onCheckWinePackages = onCheckWinePackages,
                        box64Installed = box64Installed,
                        winePrefixInfo = winePrefixInfo,
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
                        Text(vxText("VYROX Installer Library"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Importing stores an EXE/MSI in private app storage. It does not install or run Windows software until a compatible runtime is integrated.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                        Button(onClick = onImport, enabled = !isImporting) {
                            Text(if (isImporting) vxText("Importing...") else vxText("Import EXE / MSI / ZIP"))
                        }
                    }
                }
                if (installers.isEmpty()) {
                    Text(vxText("No imported installers."), color = Color(0xFFACB9D0))
                } else {
                    installers.forEach { installer ->
                        InstallerRow(installer = installer, onDelete = onDeleteInstaller)
                    }
                }
            }

            DesktopPanel.RUNTIME -> {
                HostReadinessCard(hostCapabilities)
                RuntimeEngineStatusCard(hostCapabilities, rootFsImages, prootBinaryAvailable, box64Installed)
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(statusMessage, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
        }
    }
}

