package com.vyrox.runtime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyrox.runtime.core.capsule.CapsuleFileEntry
import com.vyrox.runtime.core.capsule.CapsuleInfo
import com.vyrox.runtime.core.capsule.CapsuleSnapshotInfo
import com.vyrox.runtime.core.files.ImportedInstaller
import com.vyrox.runtime.core.host.HostCapabilities
import com.vyrox.engine.RuntimeCompatibilityPlanner
import com.vyrox.engine.RuntimeEngineCatalog
import com.vyrox.engine.VulkanProbeParser
import com.vyrox.engine.X64RuntimePrerequisites
import com.vyrox.engine.LinuxRootFsInfo
import com.vyrox.engine.LinuxRootFsArchitecture
import com.vyrox.engine.WinePrefixInfo
import java.util.Locale

private val VyroxColors = darkColorScheme(
    primary = Color(0xFF91A9FF),
    onPrimary = Color(0xFF10172B),
    background = Color(0xFF0B1020),
    surface = Color(0xFF151D31),
    onSurface = Color(0xFFE9EDFA),
    secondary = Color(0xFF78D8C2)
)

@Composable
fun VyroxApp(
    installers: List<ImportedInstaller>,
    capsules: List<CapsuleInfo>,
    capsuleSnapshots: List<CapsuleSnapshotInfo>,
    snapshotManagerCapsule: CapsuleInfo?,
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
    statusMessage: String?,
    isDesktopMode: Boolean,
    onToggleDesktopMode: () -> Unit,
    onImport: () -> Unit,
    onDeleteInstaller: (ImportedInstaller) -> Unit,
    onCreateCapsule: (String) -> Unit,
    onDeleteCapsule: (CapsuleInfo) -> Unit,
    onOpenCapsule: (CapsuleInfo) -> Unit,
    onCloseCapsuleExplorer: () -> Unit,
    onSelectCapsuleDrive: (Char) -> Unit,
    onNavigateCapsuleDirectory: (String) -> Unit,
    onCreateCapsuleDirectory: (String) -> Unit,
    onImportIntoCapsule: () -> Unit,
    onDeleteCapsuleEntry: (CapsuleFileEntry) -> Unit,
    onRenameCapsuleEntry: (CapsuleFileEntry, String) -> Unit,
    onCopyCapsuleEntry: (CapsuleFileEntry, String) -> Unit,
    onMoveCapsuleEntry: (CapsuleFileEntry, String) -> Unit,
    onSearchCapsuleEntries: (String) -> Unit,
    onSelectCapsuleSearchResult: (CapsuleFileEntry) -> Unit,
    onImportRootFs: () -> Unit,
    onRemoveRootFs: (LinuxRootFsInfo) -> Unit,
    onTestRootFs: (LinuxRootFsInfo) -> Unit,
    onBuildBox64: (LinuxRootFsInfo) -> Unit,
    onExtractZipEntry: (CapsuleFileEntry) -> Unit,
    onCloneCapsule: (CapsuleInfo) -> Unit,
    onRenameCapsule: (CapsuleInfo, String) -> Unit,
    onCreateCapsuleSnapshot: (CapsuleInfo) -> Unit,
    onManageCapsuleSnapshots: (CapsuleInfo) -> Unit,
    onRestoreCapsuleSnapshot: (CapsuleInfo, CapsuleSnapshotInfo) -> Unit,
    onDeleteCapsuleSnapshot: (CapsuleInfo, CapsuleSnapshotInfo) -> Unit,
    onCloseCapsuleSnapshotManager: () -> Unit,
    onBackupCapsule: (CapsuleInfo) -> Unit,
    onImportCapsuleBackup: () -> Unit
) {
    var capsuleName by remember { mutableStateOf("") }
    var folderName by remember(selectedCapsule?.id, selectedDrive, currentPath) {
        mutableStateOf("")
    }
    var pendingDeleteEntry by remember { mutableStateOf<CapsuleFileEntry?>(null) }
    var pendingRenameEntry by remember { mutableStateOf<CapsuleFileEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var pendingRenameCapsule by remember { mutableStateOf<CapsuleInfo?>(null) }
    var capsuleRenameValue by remember { mutableStateOf("") }
    var destinationPath by remember(selectedCapsule?.id, selectedDrive) { mutableStateOf("") }
    var searchQuery by remember(selectedCapsule?.id, selectedDrive) { mutableStateOf("") }

    MaterialTheme(colorScheme = VyroxColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isDesktopMode) {
                DesktopShell(
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
                    onCapsuleNameChange = { capsuleName = it },
                    folderName = folderName,
                    onFolderNameChange = { folderName = it },
                    destinationPath = destinationPath,
                    onDestinationPathChange = { destinationPath = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onImport = onImport,
                    onDeleteInstaller = onDeleteInstaller,
                    onCreateCapsule = {
                        onCreateCapsule(capsuleName)
                        capsuleName = ""
                    },
                    onDeleteCapsule = onDeleteCapsule,
                    onOpenCapsule = onOpenCapsule,
                    onCloseCapsuleExplorer = onCloseCapsuleExplorer,
                    onSelectCapsuleDrive = onSelectCapsuleDrive,
                    onNavigateCapsuleDirectory = onNavigateCapsuleDirectory,
                    onCreateCapsuleDirectory = {
                        onCreateCapsuleDirectory(folderName)
                        folderName = ""
                    },
                    onImportIntoCapsule = onImportIntoCapsule,
                    onDeleteCapsuleEntry = { pendingDeleteEntry = it },
                    onRenameCapsuleEntry = {
                        pendingRenameEntry = it
                        renameValue = it.name
                    },
                    onCopyCapsuleEntry = { onCopyCapsuleEntry(it, destinationPath) },
                    onMoveCapsuleEntry = { onMoveCapsuleEntry(it, destinationPath) },
                    onSearchCapsuleEntries = { onSearchCapsuleEntries(searchQuery) },
                    onSelectCapsuleSearchResult = onSelectCapsuleSearchResult,
                    onImportRootFs = onImportRootFs,
                    onRemoveRootFs = onRemoveRootFs,
                    onTestRootFs = onTestRootFs,
                    onBuildBox64 = onBuildBox64,
                    onExtractZipEntry = onExtractZipEntry,
                    onCloneCapsule = onCloneCapsule,
                    onRenameCapsule = { capsule ->
                        pendingRenameCapsule = capsule
                        capsuleRenameValue = capsule.name
                    },
                    onCreateCapsuleSnapshot = onCreateCapsuleSnapshot,
                    onManageCapsuleSnapshots = onManageCapsuleSnapshots,
                    onBackupCapsule = onBackupCapsule,
                    onImportCapsuleBackup = onImportCapsuleBackup,
                    onToggleDesktopMode = onToggleDesktopMode
                )
            } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = "VYROX",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Windows runtime environment for Android",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                    OutlinedButton(onClick = onToggleDesktopMode) {
                        Text("Switch to desktop workspace")
                    }
                }


                HostReadinessCard(hostCapabilities)
                RuntimeEngineStatusCard(hostCapabilities, rootFsImages, prootBinaryAvailable, box64Installed)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Capsule workspaces", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Create separate private storage workspaces. These folders are not virtual machines or hardened security sandboxes.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                        )
                        OutlinedTextField(
                            value = capsuleName,
                            onValueChange = { capsuleName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Capsule name") }
                        )
                        Button(
                            onClick = {
                                onCreateCapsule(capsuleName)
                                capsuleName = ""
                            },
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
                        if (capsules.isEmpty()) {
                            Text(
                                "No Capsules created yet.",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                capsules.forEach { capsule ->
                                    CapsuleRow(
                                        capsule = capsule,
                                        onOpen = onOpenCapsule,
                                        onDelete = onDeleteCapsule,
                                        onClone = onCloneCapsule,
                                        onRename = { selected ->
                                            pendingRenameCapsule = selected
                                            capsuleRenameValue = selected.name
                                        },
                                        onSnapshot = onCreateCapsuleSnapshot,
                                        onManageSnapshots = onManageCapsuleSnapshots,
                                        onBackup = onBackupCapsule
                                    )
                                }
                            }
                        }
                    }
                }

                if (selectedCapsule != null) {
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
                        onFolderNameChange = { folderName = it },
                        onDestinationPathChange = { destinationPath = it },
                        onSearchQueryChange = { searchQuery = it },
                        onSearch = { onSearchCapsuleEntries(searchQuery) },
                        onSelectSearchResult = onSelectCapsuleSearchResult,
                        onImportRootFs = onImportRootFs,
                        onRemoveRootFs = onRemoveRootFs,
                        onTestRootFs = onTestRootFs,
                        onBuildBox64 = onBuildBox64,
                        box64Installed = box64Installed,
                        winePrefixInfo = winePrefixInfo,
                        onRenameEntry = {
                            pendingRenameEntry = it
                            renameValue = it.name
                        },
                        onCopyEntry = { entry -> onCopyCapsuleEntry(entry, destinationPath) },
                        onMoveEntry = { entry -> onMoveCapsuleEntry(entry, destinationPath) },
                        onSelectDrive = onSelectCapsuleDrive,
                        onNavigate = onNavigateCapsuleDirectory,
                        onCreateFolder = {
                            onCreateCapsuleDirectory(folderName)
                            folderName = ""
                        },
                        onImportFile = onImportIntoCapsule,
                        onClose = onCloseCapsuleExplorer,
                        onRequestDelete = { pendingDeleteEntry = it },
                         onExtractZipEntry = onExtractZipEntry
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Foundation build", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Imports EXE/MSI installers and ZIP archives into private app storage. ZIP extraction is confined to a selected Capsule; Windows executables are not run yet.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                        )
                        Button(
                            onClick = onImport,
                            enabled = !isImporting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Text("  Importing...")
                            } else {
                                Text("Import EXE / MSI / ZIP")
                            }
                        }
                    }
                }

                if (statusMessage != null) {
                    Text(
                        text = statusMessage,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Imported installers",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = installers.size.toString(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (installers.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Text(
                            text = "No installers imported. Choose an EXE or MSI file to add it to this device's private VYROX storage.",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        installers.forEach { installer ->
                            InstallerRow(installer = installer, onDelete = onDeleteInstaller)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF202940))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text("Runtime engines", fontWeight = FontWeight.Bold)
                        Text("Wine compatibility: not integrated")
                        Text("CPU translation: not integrated")
                        Text("GPU / DirectX translation: not integrated")
                        Text(
                            "Capsule isolation and Windows process execution are future milestones.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                    }
                }
            }
            }
        }
    }


    if (snapshotManagerCapsule != null) {
        val capsule = snapshotManagerCapsule
        AlertDialog(
            onDismissRequest = onCloseCapsuleSnapshotManager,
            title = { Text("Snapshots — " + (capsule?.name ?: "")) },
            text = {
                if (capsuleSnapshots.isEmpty()) {
                    Text("No snapshots have been created for this Capsule.")
                } else {
                    Column(
                        modifier = Modifier
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        capsuleSnapshots.forEach { snapshot ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF202940)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(snapshot.fileName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        formatBytes(snapshot.sizeBytes) + " • SHA-256 " + snapshot.sha256.take(16) + "...",
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                capsule?.let { onRestoreCapsuleSnapshot(it, snapshot) }
                                            },
                                            enabled = capsule != null && !isImporting
                                        ) { Text("Restore") }
                                        OutlinedButton(
                                            onClick = {
                                                capsule?.let { onDeleteCapsuleSnapshot(it, snapshot) }
                                            },
                                            enabled = capsule != null && !isImporting
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = onCloseCapsuleSnapshotManager) { Text("Close") }
            }
        )
    }

    if (pendingRenameCapsule != null) {
        val capsule = pendingRenameCapsule
        AlertDialog(
            onDismissRequest = { pendingRenameCapsule = null },
            title = { Text("Rename Capsule") },
            text = {
                OutlinedTextField(
                    value = capsuleRenameValue,
                    onValueChange = { capsuleRenameValue = it },
                    singleLine = true,
                    label = { Text("New Capsule name") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        capsule?.let { onRenameCapsule(it, capsuleRenameValue) }
                        pendingRenameCapsule = null
                    },
                    enabled = capsuleRenameValue.trim().length >= 2
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingRenameCapsule = null }) { Text("Cancel") }
            }
        )
    }

    if (pendingRenameEntry != null) {
        val entry = pendingRenameEntry
        AlertDialog(
            onDismissRequest = { pendingRenameEntry = null },
            title = { Text("Rename ${entry?.name}") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("New name") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        entry?.let { onRenameCapsuleEntry(it, renameValue) }
                        pendingRenameEntry = null
                    },
                    enabled = renameValue.trim().isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingRenameEntry = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pendingDeleteEntry != null) {
        val entry = pendingDeleteEntry
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text("Remove ${entry?.name}?") },
            text = {
                Text(
                    if (entry?.isDirectory == true) {
                        "This folder and everything inside it will be deleted from the Capsule."
                    } else {
                        "This file will be deleted from the Capsule."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        entry?.let(onDeleteCapsuleEntry)
                        pendingDeleteEntry = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDeleteEntry = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}


@Composable
internal fun RuntimeEngineStatusCard(
    profile: HostCapabilities,
    rootFsImages: List<LinuxRootFsInfo>,
    prootBinaryAvailable: Boolean,
    box64Installed: Boolean
) {
    val vulkan = VulkanProbeParser.parse(profile.nativeVulkanInfo)
    val readiness = RuntimeCompatibilityPlanner.assessWindowsX64(
        X64RuntimePrerequisites(
            hostAbis = profile.supported64BitAbis.toSet(),
            arm64RootFsInstalled = rootFsImages.any {
                it.architecture == LinuxRootFsArchitecture.ARM64
            },
            x86_64WineWow64Installed = false,
            x86_64RuntimeLibrariesInstalled = false,
            box64Installed = box64Installed,
            prootInstalled = prootBinaryAvailable,
            displayBridgeInstalled = false,
            audioBridgeInstalled = false,
            inputBridgeInstalled = false,
            d3d11Requested = true,
            dxvkInstalled = false,
            vulkan = vulkan,
            windowsPeLoaderInstalled = false,
            capsuleDriveMappingInstalled = false,
            physicalDeviceExecutionVerified = false
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("Runtime engine stack", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Box64 0.4.4 in selected ARM64 RootFS: " +
                    if (box64Installed) "built and x86-64 syscall smoke-tested (full applications remain unverified)" else "not installed",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "PRoot APK binary: " + if (prootBinaryAvailable) {
                    "present (device execution not verified)"
                } else {
                    "not packaged for this device"
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Windows x64 modeled preflight: " + if (readiness.preflightPassed) "Ready" else "Not ready",
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
            readiness.blockers.take(5).forEach { blocker ->
                Text(
                    "• $blocker",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "DXVK 3.x Vulkan preflight: " +
                    if (vulkan.supportsDxvk3Requirements) "requirements detected" else "requirements not satisfied or not reported",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
            RuntimeEngineCatalog.components.forEach { component ->
                Text(
                    component.name + " — " + component.integrationState.name
                        .replace('_', ' ')
                        .lowercase(),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    component.role + " • " + component.license,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Catalog entries are upstream candidates, not installed engines. No Windows process is launched by this screen.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun HostReadinessCard(profile: HostCapabilities) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("Device readiness", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Supported ABIs: " + profile.supportedAbis.joinToString().ifBlank { "Unknown" })
            Text(
                "CPU cores: ${profile.logicalCpuCount} • RAM: ${formatBytes(profile.totalMemoryBytes)}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            Text(
                if (profile.supportsArm64RuntimeHost) {
                    "ARM64 64-bit host detected. This is suitable for the first engine-integration target."
                } else {
                    "ARM64 64-bit host ABI was not detected. The first engine target may not support this device."
                },
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                "Native bridge: " + (profile.nativeBuildInfo ?: "not available"),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Vulkan: " + (profile.nativeVulkanInfo ?: "not available on this device"),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            Text(
                "Vulkan detection does not prove DXVK or DirectX compatibility.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Hardware detection only: Wine, CPU translation, and Windows execution are not installed.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun CapsuleExplorerCard(
    capsule: CapsuleInfo,
    selectedDrive: Char,
    currentPath: String,
    entries: List<CapsuleFileEntry>,
    searchResults: List<CapsuleFileEntry>,
    rootFsImages: List<LinuxRootFsInfo>,
    folderName: String,
    destinationPath: String,
    searchQuery: String,
    isImporting: Boolean,
    onFolderNameChange: (String) -> Unit,
    onDestinationPathChange: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectSearchResult: (CapsuleFileEntry) -> Unit,
    onImportRootFs: () -> Unit,
    onRemoveRootFs: (LinuxRootFsInfo) -> Unit,
    onTestRootFs: (LinuxRootFsInfo) -> Unit,
    onBuildBox64: (LinuxRootFsInfo) -> Unit,
    box64Installed: Boolean,
    winePrefixInfo: WinePrefixInfo?,
    onRenameEntry: (CapsuleFileEntry) -> Unit,
    onCopyEntry: (CapsuleFileEntry) -> Unit,
    onMoveEntry: (CapsuleFileEntry) -> Unit,
    onSelectDrive: (Char) -> Unit,
    onNavigate: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onImportFile: () -> Unit,
    onClose: () -> Unit,
    onRequestDelete: (CapsuleFileEntry) -> Unit,
    onExtractZipEntry: (CapsuleFileEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = capsule.name + " — File explorer",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onClose) { Text("Close") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedDrive == 'C') {
                    Button(onClick = { onSelectDrive('C') }) { Text("C: System") }
                } else {
                    OutlinedButton(onClick = { onSelectDrive('C') }) { Text("C: System") }
                }
                if (selectedDrive == 'D') {
                    Button(onClick = { onSelectDrive('D') }) { Text("D: Data") }
                } else {
                    OutlinedButton(onClick = { onSelectDrive('D') }) { Text("D: Data") }
                }
            }

            val drivePath = if (currentPath.isBlank()) {
                "${selectedDrive}:\\"
            } else {
                "${selectedDrive}:\\${currentPath.replace('/', '\\')}"
            }
            Text(drivePath, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Files are stored inside this app. These folders are not mounted Windows drives.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = folderName,
                onValueChange = onFolderNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("New folder name") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreateFolder, enabled = folderName.trim().isNotEmpty()) {
                    Text("Create folder")
                }
                OutlinedButton(onClick = onImportFile, enabled = !isImporting) {
                    Text(if (isImporting) "Importing..." else "Import file")
                }
                if (currentPath.isNotBlank()) {
                    OutlinedButton(
                        onClick = { onNavigate(currentPath.substringBeforeLast('/', "")) }
                    ) {
                        Text("Up")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF202940)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Linux RootFS", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Wine prefix: " + (winePrefixInfo?.let { "verified Wine ${it.wineVersion} (${it.architecture})" }
                            ?: "no verified prefix in this Capsule's ARM64 RootFS"),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall
                    )
                    winePrefixInfo?.let { prefix ->
                        Text(
                            "C: ${prefix.guestDriveCPath}  •  D: ${prefix.guestDriveDPath}",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Separate Linux filesystems. The shell test executes uname and id inside the imported ARM64 RootFS; it does not run Windows programs.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onImportRootFs, enabled = !isImporting) {
                        Text(if (isImporting) "Importing RootFS..." else "Import Linux RootFS archive")
                    }
                    if (rootFsImages.isEmpty()) {
                        Text(
                            "No Linux RootFS installed in this Capsule.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        rootFsImages.forEach { rootFs ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rootFs.prettyName, fontWeight = FontWeight.Medium)
                                    Text(
                                        rootFs.architecture.label + " • " +
                                            rootFs.distributionId + " " + rootFs.versionId +
                                            " • " + formatBytes(rootFs.expandedBytes),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (rootFs.architecture == LinuxRootFsArchitecture.ARM64) {
                                    OutlinedButton(
                                        onClick = { onBuildBox64(rootFs) },
                                        enabled = !isImporting
                                    ) {
                                        Text(if (box64Installed) "Rebuild Box64" else "Build Box64 0.4.4")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onTestRootFs(rootFs) },
                                    enabled = !isImporting && rootFs.architecture == LinuxRootFsArchitecture.ARM64
                                ) {
                                    Text("Test shell")
                                }
                                OutlinedButton(onClick = { onRemoveRootFs(rootFs) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = destinationPath,
                onValueChange = onDestinationPathChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Copy / move destination path (blank = drive root)") }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Search this drive") }
                )
                Button(onClick = onSearch, enabled = searchQuery.trim().isNotEmpty()) {
                    Text("Search")
                }
            }

            if (searchResults.isNotEmpty()) {
                Text("Search results", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    searchResults.forEach { result ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                result.relativePath,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = { onSelectSearchResult(result) }) {
                                Text("Locate")
                            }
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    "This folder is empty.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach { entry ->
                        CapsuleFileRow(
                            entry = entry,
                            onOpen = { onNavigate(entry.relativePath) },
                            onRename = { onRenameEntry(entry) },
                            onCopy = { onCopyEntry(entry) },
                            onMove = { onMoveEntry(entry) },
                            onDelete = { onRequestDelete(entry) },
                            onExtractZip = { onExtractZipEntry(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleFileRow(
    entry: CapsuleFileEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onExtractZip: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202940)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(entry.name, fontWeight = FontWeight.SemiBold)
                Text(
                    if (entry.isDirectory) "Folder" else formatBytes(entry.sizeBytes),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (entry.isDirectory) {
                OutlinedButton(onClick = onOpen) { Text("Open") }
            }
            OutlinedButton(onClick = onRename) { Text("Rename") }
            OutlinedButton(onClick = onCopy) { Text("Copy") }
            OutlinedButton(onClick = onMove) { Text("Move") }
            if (!entry.isDirectory && entry.name.endsWith(".zip", ignoreCase = true)) {
                OutlinedButton(onClick = onExtractZip) { Text("Extract ZIP") }
            }
            OutlinedButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
internal fun CapsuleRow(
    capsule: CapsuleInfo,
    onOpen: (CapsuleInfo) -> Unit,
    onDelete: (CapsuleInfo) -> Unit,
    onClone: (CapsuleInfo) -> Unit,
    onRename: (CapsuleInfo) -> Unit,
    onSnapshot: (CapsuleInfo) -> Unit,
    onManageSnapshots: (CapsuleInfo) -> Unit,
    onBackup: (CapsuleInfo) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202940)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(capsule.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "C: system folders • D: data folders • private app storage",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(onClick = { onOpen(capsule) }) { Text("Open") }
                OutlinedButton(onClick = { onDelete(capsule) }) { Text("Delete") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { onClone(capsule) }, modifier = Modifier.weight(1f)) { Text("Clone") }
                OutlinedButton(onClick = { onRename(capsule) }, modifier = Modifier.weight(1f)) { Text("Rename") }
                OutlinedButton(onClick = { onBackup(capsule) }, modifier = Modifier.weight(1f)) { Text("Backup") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { onSnapshot(capsule) }, modifier = Modifier.weight(1f)) { Text("Snapshot") }
                OutlinedButton(onClick = { onManageSnapshots(capsule) }, modifier = Modifier.weight(1f)) { Text("Manage snapshots") }
            }
        }
    }
}

@Composable
internal fun InstallerRow(
    installer: ImportedInstaller,
    onDelete: (ImportedInstaller) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(installer.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = installer.formatLabel + " • " + installer.architectureLabel,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = formatBytes(installer.sizeBytes) + " • Stored privately • Not executed",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = { onDelete(installer) }) {
                Text("Remove")
            }
        }
    }
}

internal fun formatBytes(size: Long): String {
    val megabytes = size / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", megabytes)
    }
}
