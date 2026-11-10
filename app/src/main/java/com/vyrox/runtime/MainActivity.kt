package com.vyrox.runtime

import android.net.Uri
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vyrox.runtime.core.capsule.CapsuleFileEntry
import com.vyrox.runtime.core.capsule.CapsuleInfo
import com.vyrox.runtime.core.capsule.CapsuleStorage
import com.vyrox.runtime.core.capsule.WindowsProgramManager
import com.vyrox.runtime.core.capsule.InstalledWindowsProgram
import com.vyrox.runtime.core.capsule.CapsuleSnapshotInfo
import com.vyrox.runtime.core.files.ImportedInstaller
import com.vyrox.runtime.core.files.InstallerStorage
import com.vyrox.runtime.core.engine.ProotExecutableLocator
import com.vyrox.runtime.core.host.HostCapabilities
import com.vyrox.runtime.core.host.HostCapabilitiesProbe
import com.vyrox.engine.LinuxRootFsInfo
import com.vyrox.engine.LinuxRootFsManager
import com.vyrox.engine.LinuxRootFsArchitecture
import com.vyrox.engine.Box64BuildPlan
import com.vyrox.engine.WinePrefixInfo
import com.vyrox.engine.WinePrefixManager
import com.vyrox.engine.WinePackagePreflight
import com.vyrox.engine.WineRuntimePlan
import com.vyrox.engine.GraphicsTranslationPlan
import com.vyrox.engine.WindowsInstallerKind
import com.vyrox.engine.WinePrefixCreationTicket
import com.vyrox.engine.ProotCommandPlanner
import com.vyrox.engine.ProotBindMount
import com.vyrox.engine.ProotLaunchRequest
import com.vyrox.engine.RuntimeProcessSupervisor
import com.vyrox.engine.ManagedRuntimeProcess
import com.vyrox.engine.RuntimeProcessResult
import com.vyrox.engine.ProotCommandPlan
import com.vyrox.runtime.ui.VyroxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var desktopModeEnabled by mutableStateOf(true)
    private var appLanguage by mutableStateOf("en")
    private lateinit var installerStorage: InstallerStorage
    private lateinit var capsuleStorage: CapsuleStorage
    private lateinit var windowsProgramManager: WindowsProgramManager
    private lateinit var hostCapabilities: HostCapabilities
    private lateinit var rootFsManager: LinuxRootFsManager
    private var prootBinaryAvailable by mutableStateOf(false)
    private var box64Installed by mutableStateOf(false)
    private var wineInstalled by mutableStateOf(false)
    private var graphicsTranslationInstalled by mutableStateOf(false)
    private var winePrefixInfo by mutableStateOf<WinePrefixInfo?>(null)
    private val installers = mutableStateListOf<ImportedInstaller>()
    private val capsules = mutableStateListOf<CapsuleInfo>()
    private val capsuleSnapshots = mutableStateListOf<CapsuleSnapshotInfo>()
    private val capsuleEntries = mutableStateListOf<CapsuleFileEntry>()
    private val capsuleSearchResults = mutableStateListOf<CapsuleFileEntry>()
    private val rootFsImages = mutableStateListOf<LinuxRootFsInfo>()
    private val windowsPrograms = mutableStateListOf<InstalledWindowsProgram>()
    private var windowsDesktopVisible by mutableStateOf(false)
    private var windowsDesktopFrame by mutableStateOf<Bitmap?>(null)
    private var windowsDesktopStatus by mutableStateOf("Starting Windows desktop...")
    private var activeVncClient: VncRfbClient? = null
    private var activeAudioBridge: PcmAudioBridge? = null

    private var selectedCapsule by mutableStateOf<CapsuleInfo?>(null)
    private var snapshotManagerCapsule by mutableStateOf<CapsuleInfo?>(null)
    private var selectedDrive by mutableStateOf('C')
    private var selectedPath by mutableStateOf("")
    private var pendingCapsuleImport: CapsuleImportTarget? = null
    private var pendingRootFsCapsuleId: String? = null
    private var pendingCapsuleBackupId: String? = null

    private var isImporting by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var activeRuntimeProcess by mutableStateOf<ManagedRuntimeProcess?>(null)
    private var activeRuntimeLabel by mutableStateOf<String?>(null)
    private var isStoppingRuntimeProcess by mutableStateOf(false)
    private var runtimeStopRequested = false

    private val openInstaller = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importInstaller(uri)
    }

    private val openCapsuleFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingCapsuleImport
        pendingCapsuleImport = null
        if (uri != null && target != null) importIntoCapsule(uri, target)
    }

    private val openRootFsArchive = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val capsuleId = pendingRootFsCapsuleId
        pendingRootFsCapsuleId = null
        if (uri != null && capsuleId != null) importRootFsArchive(uri, capsuleId)
    }

    private val createCapsuleBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val capsuleId = pendingCapsuleBackupId
        pendingCapsuleBackupId = null
        if (uri != null && capsuleId != null) exportCapsuleBackup(uri, capsuleId)
    }

    private val openCapsuleBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importCapsuleBackup(uri)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desktopModeEnabled = savedInstanceState?.getBoolean(STATE_DESKTOP_MODE) ?: true
        appLanguage = getSharedPreferences("vyrox_preferences", MODE_PRIVATE)
            .getString("language", "en")?.takeIf { it == "ar" } ?: "en"
        requestedOrientation = if (desktopModeEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        installerStorage = InstallerStorage(applicationContext)
        capsuleStorage = CapsuleStorage(File(filesDir, "capsules"))
        windowsProgramManager = WindowsProgramManager(capsuleStorage)
        rootFsManager = LinuxRootFsManager(File(filesDir, "capsules"))
        hostCapabilities = HostCapabilitiesProbe.read(applicationContext)
        prootBinaryAvailable = ProotExecutableLocator.locate(
            nativeLibraryDirectory = File(applicationInfo.nativeLibraryDir),
            supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
        ) != null
        installers.addAll(installerStorage.listImported())
        capsules.addAll(capsuleStorage.listCapsules())

        setContent {
            VyroxApp(
                installers = installers.toList(),
                capsules = capsules.toList(),
                capsuleSnapshots = capsuleSnapshots.toList(),
                snapshotManagerCapsule = snapshotManagerCapsule,
                selectedCapsule = selectedCapsule,
                selectedDrive = selectedDrive,
                currentPath = selectedPath,
                capsuleEntries = capsuleEntries.toList(),
                capsuleSearchResults = capsuleSearchResults.toList(),
                rootFsImages = rootFsImages.toList(),
                hostCapabilities = hostCapabilities,
                prootBinaryAvailable = prootBinaryAvailable,
                box64Installed = box64Installed,
                wineInstalled = wineInstalled,
                graphicsTranslationInstalled = graphicsTranslationInstalled,
                winePrefixInfo = winePrefixInfo,
                installedWindowsPrograms = windowsPrograms.toList(),
                windowsDesktopVisible = windowsDesktopVisible,
                windowsDesktopFrame = windowsDesktopFrame,
                windowsDesktopStatus = windowsDesktopStatus,
                isImporting = isImporting,
                activeRuntimeLabel = activeRuntimeLabel,
                isStoppingRuntimeProcess = isStoppingRuntimeProcess,
                onStopRuntimeProcess = ::stopRuntimeProcess,
                statusMessage = statusMessage,
                isDesktopMode = desktopModeEnabled,
                language = appLanguage,
                onLanguageChange = { selectedLanguage ->
                    appLanguage = if (selectedLanguage == "ar") "ar" else "en"
                    getSharedPreferences("vyrox_preferences", MODE_PRIVATE)
                        .edit().putString("language", appLanguage).apply()
                },
                onToggleDesktopMode = {
                    desktopModeEnabled = !desktopModeEnabled
                    requestedOrientation = if (desktopModeEnabled) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                },
                onImport = {
                    if (!isImporting) openInstaller.launch(arrayOf("*/*"))
                },
                onDeleteInstaller = ::deleteInstaller,
                onInstallImportedInstaller = ::installImportedInstaller,
                onCreateCapsule = ::createCapsule,
                onDeleteCapsule = ::deleteCapsule,
                onOpenCapsule = ::openCapsule,
                onCloseCapsuleExplorer = ::closeCapsuleExplorer,
                onSelectCapsuleDrive = ::selectCapsuleDrive,
                onNavigateCapsuleDirectory = ::navigateCapsuleDirectory,
                onCreateCapsuleDirectory = ::createCapsuleDirectory,
                onImportIntoCapsule = ::requestCapsuleImport,
                onDeleteCapsuleEntry = ::deleteCapsuleEntry,
                onRenameCapsuleEntry = ::renameCapsuleEntry,
                onCopyCapsuleEntry = ::copyCapsuleEntry,
                onMoveCapsuleEntry = ::moveCapsuleEntry,
                onSearchCapsuleEntries = ::searchCapsuleEntries,
                onSelectCapsuleSearchResult = ::selectCapsuleSearchResult,
                onImportRootFs = ::requestRootFsImport,
                onRemoveRootFs = ::removeRootFs,
                onTestRootFs = ::testRootFs,
                onBuildBox64 = ::buildBox64,
                onCheckWinePackages = ::checkWinePackages,
                onInstallWine = ::installWine,
                onCreateWinePrefix = ::createWinePrefix,
                onInstallGraphicsTranslation = ::installGraphicsTranslation,
                onRunWindowsEntry = ::runWindowsEntry,
                onLaunchWindowsProgram = ::launchWindowsProgram,
                onCloseWindowsDesktop = ::closeWindowsDesktop,
                onVncPointer = ::sendWindowsPointer,
                onVncKeySym = ::sendWindowsKeySym,
                onVncText = ::sendWindowsText,
                onExtractZipEntry = ::extractZipEntry,
                onCloneCapsule = ::cloneCapsule,
                onRenameCapsule = ::renameCapsule,
                onCreateCapsuleSnapshot = ::createCapsuleSnapshot,
                onManageCapsuleSnapshots = ::manageCapsuleSnapshots,
                onRestoreCapsuleSnapshot = ::restoreCapsuleSnapshot,
                onDeleteCapsuleSnapshot = ::deleteCapsuleSnapshot,
                onCloseCapsuleSnapshotManager = ::closeCapsuleSnapshotManager,
                onBackupCapsule = ::requestCapsuleBackup,
                onImportCapsuleBackup = ::requestImportCapsuleBackup
            )
        }
    }

    override fun onDestroy() {
        activeVncClient?.close()
        activeAudioBridge?.close()
        activeVncClient = null
        activeAudioBridge = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DESKTOP_MODE, desktopModeEnabled)
        super.onSaveInstanceState(outState)
    }

    private fun importInstaller(uri: Uri) {
        if (isImporting) return

        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Importing into private app storage..."
            try {
                val displayName = queryDisplayName(uri)
                val imported = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Android could not open the selected file.")
                    input.use {
                        installerStorage.importInstaller(displayName, it)
                    }
                }

                installers.add(0, imported)
                statusMessage = "Imported " + imported.displayName + ". It has not been executed."
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "The installer could not be imported."
            } finally {
                isImporting = false
            }
        }
    }

    private fun installImportedInstaller(installer: ImportedInstaller) {
        if (isImporting || windowsDesktopVisible) return
        val capsule = selectedCapsule ?: run {
            statusMessage = "Open the target Capsule before installing this Windows program."
            return
        }
        val prefix = winePrefixInfo?.takeIf { it.capsuleId == capsule.id } ?: run {
            statusMessage = "Create and verify this Capsule's Wine prefix before installing an EXE/MSI."
            return
        }
        if (!box64Installed || !wineInstalled) {
            statusMessage = "Build Box64 and install Wine 11+ before installing Windows software."
            return
        }
        val kind = when (installer.displayName.substringAfterLast('.', "").lowercase()) {
            "exe" -> {
                if (installer.architectureLabel !in setOf("x86", "x86-64")) {
                    statusMessage = "This Wine 11 x86-64 runtime can launch x86/x86-64 EXE files only. This file is " +
                        installer.architectureLabel + "."
                    return
                }
                WindowsInstallerKind.EXE
            }
            "msi" -> WindowsInstallerKind.MSI
            else -> {
                statusMessage = "ZIP files are archives. Extract them into the Capsule instead of installing them as EXE/MSI."
                return
            }
        }

        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Copying " + installer.displayName + " into Capsule " + capsule.name + "..."
            var copiedEntry: CapsuleFileEntry? = null
            try {
                copiedEntry = withContext(Dispatchers.IO) {
                    val children = capsuleStorage.listDirectory(capsule.id, 'C', "")
                    val downloads = children.firstOrNull {
                        it.isDirectory && it.name.equals("Downloads", ignoreCase = true)
                    }?.relativePath ?: capsuleStorage.createDirectory(
                        capsule.id, 'C', "", "Downloads"
                    ).relativePath
                    val uniquePrefix = UUID.randomUUID().toString().take(8) + "_"
                    val extensionStart = installer.displayName.lastIndexOf('.')
                    val stem = if (extensionStart > 0) installer.displayName.substring(0, extensionStart)
                        else installer.displayName
                    val suffix = if (extensionStart > 0) installer.displayName.substring(extensionStart) else ""
                    val targetName = uniquePrefix +
                        stem.take((120 - uniquePrefix.length - suffix.length).coerceAtLeast(1)) + suffix
                    installerStorage.openImportedInstaller(installer).use { input ->
                        capsuleStorage.importFile(
                            capsuleId = capsule.id,
                            driveLetter = 'C',
                            parentRelativePath = downloads,
                            displayName = targetName,
                            input = input
                        )
                    }
                }
                statusMessage = "Copied " + installer.displayName +
                    " into C:\\Downloads. Starting it inside this Capsule..."
            } catch (exception: Exception) {
                statusMessage = "Could not install " + installer.displayName + ": " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                isImporting = false
            }

            val entry = copiedEntry ?: return@launch
            if (selectedCapsule?.id != capsule.id || winePrefixInfo?.capsuleId != capsule.id) {
                statusMessage = "The installer was copied into " + capsule.name +
                    " but the selected Capsule changed. Open that Capsule to run it."
                return@launch
            }
            runWindowsTarget(
                capsule = capsule,
                guestTargetPath = prefix.guestDriveCPath + "/" + entry.relativePath,
                kind = kind,
                displayName = installer.displayName
            )
        }
    }

    private fun deleteInstaller(installer: ImportedInstaller) {
        lifecycleScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    installerStorage.deleteInstaller(installer.storedFileName)
                }
                if (deleted) {
                    installers.removeAll { it.storedFileName == installer.storedFileName }
                    statusMessage = "Removed " + installer.displayName + "."
                } else {
                    statusMessage = "The stored installer could not be removed."
                }
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not remove the installer."
            }
        }
    }


    private fun createCapsule(rawName: String) {
        lifecycleScope.launch {
            try {
                val capsule = withContext(Dispatchers.IO) {
                    capsuleStorage.createCapsule(rawName)
                }
                capsules.add(0, capsule)
                statusMessage = "Created Capsule: " + capsule.name
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not create the Capsule."
            }
        }
    }

    private fun deleteCapsule(capsule: CapsuleInfo) {
        lifecycleScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    capsuleStorage.deleteCapsule(capsule.id)
                }
                if (deleted) {
                    capsules.removeAll { it.id == capsule.id }
                    if (selectedCapsule?.id == capsule.id) closeCapsuleExplorer()
                    statusMessage = "Deleted Capsule: " + capsule.name
                } else {
                    statusMessage = "The Capsule could not be deleted."
                }
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not delete the Capsule."
            }
        }
    }


    private fun cloneCapsule(capsule: CapsuleInfo) {
        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Cloning Capsule files..."
            try {
                val cloned = withContext(Dispatchers.IO) {
                    capsuleStorage.cloneCapsule(capsule.id, capsule.name + " Copy")
                }
                capsules.add(0, cloned)
                statusMessage = "Cloned " + capsule.name + " into " + cloned.name
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not clone this Capsule."
            } finally {
                isImporting = false
            }
        }
    }

    private fun renameCapsule(capsule: CapsuleInfo, newName: String) {
        lifecycleScope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) {
                    capsuleStorage.renameCapsule(capsule.id, newName)
                }
                val index = capsules.indexOfFirst { it.id == capsule.id }
                if (index >= 0) capsules[index] = renamed
                if (selectedCapsule?.id == capsule.id) selectedCapsule = renamed
                statusMessage = "Renamed Capsule to " + renamed.name
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not rename this Capsule."
            }
        }
    }

    private fun createCapsuleSnapshot(capsule: CapsuleInfo) {
        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Creating Capsule snapshot..."
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    capsuleStorage.createSnapshot(capsule.id)
                }
                statusMessage = "Snapshot saved (" + snapshot.sizeBytes + " bytes, SHA-256 " +
                    snapshot.sha256.take(12) + "...)"
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not create a Capsule snapshot."
            } finally {
                isImporting = false
            }
        }
    }

    private fun manageCapsuleSnapshots(capsule: CapsuleInfo) {
        lifecycleScope.launch {
            try {
                val snapshots = withContext(Dispatchers.IO) {
                    capsuleStorage.listSnapshots(capsule.id)
                }
                snapshotManagerCapsule = capsule
                capsuleSnapshots.clear()
                capsuleSnapshots.addAll(snapshots)
                statusMessage = "Found " + snapshots.size + " Capsule snapshots."
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not list Capsule snapshots."
            }
        }
    }

    private fun restoreCapsuleSnapshot(capsule: CapsuleInfo, snapshot: CapsuleSnapshotInfo) {
        if (isImporting) return
        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Restoring Capsule snapshot..."
            try {
                withContext(Dispatchers.IO) {
                    capsuleStorage.restoreSnapshot(capsule.id, snapshot.fileName)
                }
                statusMessage = "Restored snapshot " + snapshot.fileName
                if (selectedCapsule?.id == capsule.id) {
                    selectedPath = ""
                    capsuleEntries.clear()
                    refreshCapsuleDirectory()
                }
                manageCapsuleSnapshots(capsule)
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not restore the Capsule snapshot."
            } finally {
                isImporting = false
            }
        }
    }

    private fun deleteCapsuleSnapshot(capsule: CapsuleInfo, snapshot: CapsuleSnapshotInfo) {
        if (isImporting) return
        lifecycleScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    capsuleStorage.deleteSnapshot(capsule.id, snapshot.fileName)
                }
                if (deleted) {
                    capsuleSnapshots.removeAll { it.fileName == snapshot.fileName }
                    statusMessage = "Deleted snapshot " + snapshot.fileName
                } else {
                    statusMessage = "The selected snapshot could not be deleted."
                }
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not delete the Capsule snapshot."
            }
        }
    }

    private fun closeCapsuleSnapshotManager() {
        snapshotManagerCapsule = null
        capsuleSnapshots.clear()
    }

    private fun requestCapsuleBackup(capsule: CapsuleInfo) {
        if (isImporting) return
        pendingCapsuleBackupId = capsule.id
        val safeName = capsule.name
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "VYROX-Capsule" }
        createCapsuleBackup.launch("$safeName.vxcapsule.zip")
    }

    private fun exportCapsuleBackup(uri: Uri, capsuleId: String) {
        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Exporting Capsule backup..."
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Android could not open the backup destination.")
                    output.use { capsuleStorage.exportCapsuleBackup(capsuleId, it) }
                }
                statusMessage = "Capsule backup exported successfully."
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not export the Capsule backup."
            } finally {
                isImporting = false
            }
        }
    }

    private fun requestImportCapsuleBackup() {
        if (isImporting) return
        openCapsuleBackup.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    private fun importCapsuleBackup(uri: Uri) {
        if (isImporting) return
        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Validating and importing Capsule backup..."
            try {
                val displayName = queryDisplayName(uri)
                val baseName = displayName.substringBeforeLast('.').take(48).trim()
                val restoredName = (baseName.ifBlank { "Imported Capsule" }) + " Restored"
                val restored = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Android could not open the Capsule backup.")
                    input.use { capsuleStorage.importCapsuleBackup(restoredName, it) }
                }
                capsules.add(0, restored)
                statusMessage = "Restored Capsule backup as " + restored.name
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not import this Capsule backup."
            } finally {
                isImporting = false
            }
        }
    }

    private fun openCapsule(capsule: CapsuleInfo) {
        box64Installed = false
        wineInstalled = false
        winePrefixInfo = null
        windowsPrograms.clear()
        selectedCapsule = capsule
        selectedDrive = 'C'
        selectedPath = ""
        capsuleEntries.clear()
        capsuleSearchResults.clear()
        rootFsImages.clear()
        refreshCapsuleDirectory()
        refreshRootFsImages()
        refreshWindowsPrograms(capsule.id)
    }

    private fun closeCapsuleExplorer() {
        box64Installed = false
        wineInstalled = false
        winePrefixInfo = null
        windowsPrograms.clear()
        selectedCapsule = null
        selectedPath = ""
        capsuleEntries.clear()
        capsuleSearchResults.clear()
        rootFsImages.clear()
    }

    private fun selectCapsuleDrive(driveLetter: Char) {
        if (driveLetter != 'C' && driveLetter != 'D') return
        selectedDrive = driveLetter
        selectedPath = ""
        capsuleSearchResults.clear()
        refreshCapsuleDirectory()
    }

    private fun navigateCapsuleDirectory(relativePath: String) {
        selectedPath = relativePath
        capsuleSearchResults.clear()
        refreshCapsuleDirectory()
    }

    private fun refreshCapsuleDirectory() {
        val capsule = selectedCapsule ?: return
        val drive = selectedDrive
        val path = selectedPath
        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    capsuleStorage.listDirectory(capsule.id, drive, path)
                }
                if (selectedCapsule?.id == capsule.id &&
                    selectedDrive == drive &&
                    selectedPath == path
                ) {
                    capsuleEntries.clear()
                    capsuleEntries.addAll(entries)
                }
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not read the Capsule folder."
            }
        }
    }

    private fun createCapsuleDirectory(rawName: String) {
        val capsule = selectedCapsule ?: return
        val drive = selectedDrive
        val path = selectedPath
        lifecycleScope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    capsuleStorage.createDirectory(capsule.id, drive, path, rawName)
                }
                statusMessage = "Created folder: " + created.name
                refreshCapsuleDirectory()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not create the folder."
            }
        }
    }

    private fun deleteCapsuleEntry(entry: CapsuleFileEntry) {
        val capsule = selectedCapsule ?: return
        val drive = selectedDrive
        lifecycleScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    capsuleStorage.deleteEntry(capsule.id, drive, entry.relativePath)
                }
                statusMessage = if (deleted) {
                    "Removed: " + entry.name
                } else {
                    "The item could not be removed."
                }
                refreshCapsuleDirectory()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not remove the item."
            }
        }
    }

    private fun renameCapsuleEntry(entry: CapsuleFileEntry, newName: String) {
        val capsule = selectedCapsule ?: return
        val drive = selectedDrive
        lifecycleScope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) {
                    capsuleStorage.renameEntry(capsule.id, drive, entry.relativePath, newName)
                }
                statusMessage = "Renamed: " + renamed.name
                capsuleSearchResults.clear()
                refreshCapsuleDirectory()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not rename the Capsule item."
            }
        }
    }

    private fun copyCapsuleEntry(entry: CapsuleFileEntry, destinationPath: String) {
        val capsule = selectedCapsule ?: return
        val drive = selectedDrive
        lifecycleScope.launch {
            try {
                val copied = withContext(Dispatchers.IO) {
                    capsuleStorage.copyEntry(capsule.id, drive, entry.relativePath, destinationPath)
                }
                statusMessage = "Copied to: " + copied.relativePath
                capsuleSearchResults.clear()
                refreshCapsuleDirectory()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not copy the Capsule item."
            }
        }
    }

    private fun moveCapsuleEntry(entry: CapsuleFileEntry, destinationPath: String) {
        val capsule = selectedCapsule ?: return
        val drive = selectedDrive
        lifecycleScope.launch {
            try {
                val moved = withContext(Dispatchers.IO) {
                    capsuleStorage.moveEntry(capsule.id, drive, entry.relativePath, destinationPath)
                }
                statusMessage = "Moved to: " + moved.relativePath
                capsuleSearchResults.clear()
                refreshCapsuleDirectory()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not move the Capsule item."
            }
        }
    }

    private fun searchCapsuleEntries(query: String) {
        val capsule = selectedCapsule ?: return
        val drive = selectedDrive
        lifecycleScope.launch {
            try {
                val matches = withContext(Dispatchers.IO) {
                    capsuleStorage.searchEntries(capsule.id, drive, query)
                }
                if (selectedCapsule?.id == capsule.id && selectedDrive == drive) {
                    capsuleSearchResults.clear()
                    capsuleSearchResults.addAll(matches)
                    statusMessage = "Search results: " + matches.size
                }
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not search this Capsule drive."
            }
        }
    }

    private fun selectCapsuleSearchResult(entry: CapsuleFileEntry) {
        val targetPath = if (entry.isDirectory) {
            entry.relativePath
        } else {
            entry.relativePath.substringBeforeLast('/', "")
        }
        navigateCapsuleDirectory(targetPath)
    }

    private fun refreshRootFsImages() {
        val capsule = selectedCapsule ?: return
        lifecycleScope.launch {
            try {
                val rootFsState = withContext(Dispatchers.IO) {
                    val installed = rootFsManager.listInstalled(capsule.id)
                    val arm64RootFs = installed.firstOrNull {
                        it.architecture == LinuxRootFsArchitecture.ARM64
                    }
                    val hasBox64 = arm64RootFs
                        ?.let { info ->
                            rootFsManager.getRootFsDirectory(capsule.id, info.architecture)
                                ?.let(Box64BuildPlan::isInstalled)
                        } == true
                    val hasWine = arm64RootFs
                        ?.let { info ->
                            rootFsManager.getRootFsDirectory(capsule.id, info.architecture)
                                ?.let(WineRuntimePlan::isInstalled)
                        } == true
                    val prefixInfo = arm64RootFs
                        ?.let { info ->
                            rootFsManager.getRootFsDirectory(capsule.id, info.architecture)
                                ?.let { WinePrefixManager(it).inspect(capsule.id) }
                        }
                    val hasGraphicsTranslation = prefixInfo?.let { prefix ->
                        capsuleStorage.getDriveRoot(capsule.id, 'C')
                            ?.let { driveC -> GraphicsTranslationPlan.isInstalled(prefix.directory, driveC) }
                    } == true
                    RootFsReadiness(installed, hasBox64, hasWine, prefixInfo, hasGraphicsTranslation)
                }
                if (selectedCapsule?.id == capsule.id) {
                    rootFsImages.clear()
                    rootFsImages.addAll(rootFsState.images)
                    box64Installed = rootFsState.box64Installed
                    wineInstalled = rootFsState.wineInstalled
                    winePrefixInfo = rootFsState.winePrefixInfo
                    graphicsTranslationInstalled = rootFsState.graphicsTranslationInstalled
                }
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not read installed Linux RootFS images."
            }
        }
    }

    private fun requestRootFsImport() {
        val capsule = selectedCapsule ?: return
        if (isImporting) return
        pendingRootFsCapsuleId = capsule.id
        openRootFsArchive.launch(arrayOf("*/*"))
    }

    private fun importRootFsArchive(uri: Uri, capsuleId: String) {
        if (isImporting) return
        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Importing and validating Linux RootFS archive..."
            try {
                val displayName = queryDisplayName(uri)
                val info = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Android could not open the selected RootFS archive.")
                    input.use {
                        rootFsManager.importArchive(capsuleId, displayName, it)
                    }
                }
                if (selectedCapsule?.id == capsuleId) refreshRootFsImages()
                statusMessage = "Installed " + info.prettyName + " (" + info.architecture.label + ") as a file-backed RootFS."
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not import Linux RootFS."
            } finally {
                isImporting = false
            }
        }
    }

    private fun removeRootFs(info: LinuxRootFsInfo) {
        val capsule = selectedCapsule ?: return
        lifecycleScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    rootFsManager.remove(capsule.id, info.architecture)
                }
                statusMessage = if (removed) {
                    "Removed RootFS: " + info.prettyName
                } else {
                    "The RootFS could not be removed."
                }
                refreshRootFsImages()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not remove RootFS."
            }
        }
    }

    private fun requestCapsuleImport() {
        val capsule = selectedCapsule ?: return
        if (isImporting) return
        pendingCapsuleImport = CapsuleImportTarget(
            capsuleId = capsule.id,
            driveLetter = selectedDrive,
            parentRelativePath = selectedPath
        )
        openCapsuleFile.launch(arrayOf("*/*"))
    }

    private fun importIntoCapsule(uri: Uri, target: CapsuleImportTarget) {
        if (isImporting) return
        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Importing file into Capsule..."
            try {
                val displayName = queryDisplayName(uri)
                val imported = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Android could not open the selected file.")
                    input.use {
                        capsuleStorage.importFile(
                            target.capsuleId,
                            target.driveLetter,
                            target.parentRelativePath,
                            displayName,
                            it
                        )
                    }
                }
                statusMessage = "Imported " + imported.name + " into the Capsule."
                refreshCapsuleDirectory()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not import the file into the Capsule."
            } finally {
                isImporting = false
            }
        }
    }


    /**
     * Read-only Wine package compatibility check. No APT indexes, sources,
     * dpkg architecture settings or packages are changed by this operation.
     */
    private fun checkWinePackages(rootFs: LinuxRootFsInfo) {
        val capsule = selectedCapsule ?: run {
            statusMessage = "Open a Capsule before checking Wine packages."
            return
        }
        if (isImporting) return
        if (rootFs.architecture != LinuxRootFsArchitecture.ARM64) {
            statusMessage = "Wine preflight currently requires an ARM64 Linux RootFS."
            return
        }

        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Checking existing Wine package candidates. This is read-only."
            try {
                val plan = withContext(Dispatchers.IO) {
                    val nativeDirectory = File(applicationInfo.nativeLibraryDir)
                    val proot = ProotExecutableLocator.locate(
                        nativeLibraryDirectory = nativeDirectory,
                        supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
                    ) ?: throw IOException("The Android PRoot executable or static loader is not packaged in this APK.")
                    val rootFsDirectory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        LinuxRootFsArchitecture.ARM64
                    ) ?: throw IOException("The selected ARM64 RootFS is no longer installed in this Capsule.")

                    val allowedRoots = listOf(
                        filesDir,
                        cacheDir,
                        nativeDirectory,
                        File("/dev"),
                        File("/proc")
                    )
                    ProotCommandPlanner.build(
                        ProotLaunchRequest(
                            prootExecutable = proot,
                            nativeLibraryRoot = nativeDirectory,
                            rootFsDirectory = rootFsDirectory,
                            rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                            guestCommand = "/bin/sh",
                            guestWorkingDirectory = "/",
                            arguments = listOf("-c", WinePackagePreflight.createShellScript()),
                            bindMounts = listOf(
                                ProotBindMount(File("/dev/null"), "/dev/null"),
                                ProotBindMount(File("/proc"), "/proc")
                            ),
                            allowedHostRoots = allowedRoots
                        )
                    )
                }
                val execution = runManagedRuntimeProcess(
                    plan = plan,
                    runId = "wine-preflight-" + System.currentTimeMillis(),
                    maxLogBytes = 256L * 1024L,
                    timeoutMillis = 60_000L,
                    label = "Wine package compatibility check"
                )
                val output = withContext(Dispatchers.IO) {
                    runCatching { execution.result.logFile.readText() }
                        .getOrDefault("")
                        .takeLast(12_000)
                }
                statusMessage = when {
                    execution.stoppedByUser ->
                        "Wine package preflight stopped by you. No packages were changed.\n" + output
                    execution.result.timedOut ->
                        "Wine package preflight timed out.\n" + output
                    execution.result.exitCode != 0 ->
                        "Wine package preflight shell exited with code " +
                            execution.result.exitCode + ".\n" + output
                    else -> WinePackagePreflight.parse(output).summary()
                }
            } catch (exception: Exception) {
                statusMessage = "Wine package preflight failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                isImporting = false
            }
        }
    }

    private fun installWine(rootFs: LinuxRootFsInfo) {
        val capsule = selectedCapsule ?: run {
            statusMessage = "Open a Capsule before installing Wine."
            return
        }
        if (isImporting) return
        if (rootFs.architecture != LinuxRootFsArchitecture.ARM64) {
            statusMessage = "Wine WoW64 installation currently requires an ARM64 Linux RootFS."
            return
        }
        if (!box64Installed) {
            statusMessage = "Build and verify Box64 before installing the x86-64 Wine runtime."
            return
        }

        lifecycleScope.launch {
            isImporting = true
            wineInstalled = false
            statusMessage = "Installing WineHQ stable WoW64 inside this Capsule's Linux RootFS. This can download several hundred megabytes."
            var prepared: PreparedRootFsShell? = null
            try {
                val preparedPlan = withContext(Dispatchers.IO) {
                    val directory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        LinuxRootFsArchitecture.ARM64
                    ) ?: throw IOException("The selected ARM64 RootFS is no longer installed.")
                    createRootFsShellPlan(
                        rootFsDirectory = directory,
                        shellScript = WineRuntimePlan.createInstallShellScript(),
                        extraMounts = emptyList(),
                        fakeRoot = true
                    )
                }
                prepared = preparedPlan
                val execution = runManagedRuntimeProcess(
                    plan = preparedPlan.plan,
                    runId = "wine-install-" + System.currentTimeMillis(),
                    maxLogBytes = 8L * 1024L * 1024L,
                    timeoutMillis = 60L * 60L * 1000L,
                    label = "Install WineHQ stable WoW64"
                )
                val output = withContext(Dispatchers.IO) {
                    runCatching { execution.result.logFile.readText() }
                        .getOrDefault("Wine installation log could not be read.")
                        .takeLast(16_000)
                }
                val verified = withContext(Dispatchers.IO) {
                    WineRuntimePlan.isInstalled(
                        rootFsManager.getRootFsDirectory(capsule.id, LinuxRootFsArchitecture.ARM64)
                            ?: throw IOException("The RootFS disappeared after Wine installation.")
                    )
                }
                wineInstalled = !execution.stoppedByUser &&
                    execution.result.exitCode == 0 &&
                    !execution.result.timedOut &&
                    output.contains("VYROX_WINE_READY") &&
                    verified
                statusMessage = when {
                    execution.stoppedByUser ->
                        "Wine installation was stopped. The runtime is not marked as installed.\n" + output
                    execution.result.timedOut ->
                        "Wine installation timed out. The runtime is not marked as installed.\n" + output
                    wineInstalled ->
                        "WineHQ stable WoW64 is installed and its x86-64 loader and wineboot were verified through Box64. A Windows graphical display is not connected yet.\n" + output
                    else ->
                        "Wine installation did not pass the exit-code, Wine 11+ version, Box64 and loader verification checks.\n" + output
                }
            } catch (exception: Exception) {
                wineInstalled = false
                statusMessage = "Wine installation failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                withContext(Dispatchers.IO) { prepared?.resolverFile?.delete() }
                isImporting = false
                refreshRootFsImages()
            }
        }
    }

    private fun createWinePrefix(rootFs: LinuxRootFsInfo) {
        val capsule = selectedCapsule ?: run {
            statusMessage = "Open a Capsule before creating a Wine prefix."
            return
        }
        if (isImporting) return
        if (!box64Installed || !wineInstalled) {
            statusMessage = "Build Box64 and install WineHQ stable before creating a Windows prefix."
            return
        }

        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Creating a real Wine 11 WoW64 prefix and mounting this Capsule's C: and D: drives..."
            var setup: PreparedWinePrefix? = null
            var finalized = false
            try {
                val preparedSetup = withContext(Dispatchers.IO) {
                    val directory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        LinuxRootFsArchitecture.ARM64
                    ) ?: throw IOException("The selected ARM64 RootFS is no longer installed.")
                    val manager = WinePrefixManager(directory)
                    val ticket = manager.prepareCreation(capsule.id)
                    try {
                        val preparedShell = createRootFsShellPlan(
                            rootFsDirectory = directory,
                            shellScript = WineRuntimePlan.createPrefixShellScript(ticket.guestPrefixPath, directory),
                            extraMounts = listOf(
                                ProotBindMount(ticket.hostDriveCDirectory, ticket.guestDriveCPath),
                                ProotBindMount(ticket.hostDriveDDirectory, ticket.guestDriveDPath)
                            ),
                            fakeRoot = false
                        )
                        PreparedWinePrefix(directory, manager, ticket, preparedShell)
                    } catch (exception: Exception) {
                        manager.abortCreation(ticket)
                        throw exception
                    }
                }
                setup = preparedSetup
                val execution = runManagedRuntimeProcess(
                    plan = preparedSetup.shell.plan,
                    runId = "wine-prefix-" + System.currentTimeMillis(),
                    maxLogBytes = 2L * 1024L * 1024L,
                    timeoutMillis = 20L * 60L * 1000L,
                    label = "Create Capsule Wine prefix"
                )
                val output = withContext(Dispatchers.IO) {
                    runCatching { execution.result.logFile.readText() }
                        .getOrDefault("Wine prefix initialization log could not be read.")
                        .takeLast(12_000)
                }
                if (
                    execution.stoppedByUser ||
                    execution.result.timedOut ||
                    execution.result.exitCode != 0 ||
                    !output.contains("VYROX_WINEBOOT_OK")
                ) {
                    throw IOException(
                        "wineboot did not finish successfully; no prefix was activated.\n" + output
                    )
                }
                val prefix = withContext(Dispatchers.IO) {
                    preparedSetup.manager.finalizeCreation(
                        preparedSetup.ticket,
                        execution.result.exitCode ?: -1,
                        output
                    )
                }
                finalized = true
                winePrefixInfo = prefix
                statusMessage =
                    "A real Wine " + prefix.wineVersion + " WoW64 prefix is verified. C: and D: point to this Capsule's existing file-manager folders.\n" +
                        output
                refreshWindowsPrograms(capsule.id)
            } catch (exception: Exception) {
                statusMessage = "Wine prefix creation failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                withContext(Dispatchers.IO) {
                    setup?.let {
                        if (!finalized) it.manager.abortCreation(it.ticket)
                        it.shell.resolverFile.delete()
                    }
                }
                isImporting = false
                refreshRootFsImages()
            }
        }
    }

    private fun installGraphicsTranslation(rootFs: LinuxRootFsInfo) {
        val capsule = selectedCapsule ?: run {
            statusMessage = "Open a Capsule before installing graphics translation."
            return
        }
        if (isImporting) return
        if (rootFs.architecture != LinuxRootFsArchitecture.ARM64) {
            statusMessage = "DXVK and VKD3D-Proton installation currently requires an ARM64 RootFS."
            return
        }
        if (!box64Installed || !wineInstalled) {
            statusMessage = "Build Box64 and verify Wine 11 WoW64 before installing DXVK and VKD3D-Proton."
            return
        }

        lifecycleScope.launch {
            isImporting = true
            graphicsTranslationInstalled = false
            statusMessage = "Checking the Capsule's Wine prefix and Vulkan availability..."
            var prepared: PreparedRootFsShell? = null
            try {
                val setup = withContext(Dispatchers.IO) {
                    val rootFsDirectory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        LinuxRootFsArchitecture.ARM64
                    ) ?: throw IOException("The ARM64 Linux RootFS is no longer installed.")
                    val prefix = WinePrefixManager(rootFsDirectory).inspect(capsule.id)
                        ?: throw IOException("Create and verify this Capsule's Wine prefix before installing graphics translation.")
                    val driveC = capsuleStorage.getDriveRoot(capsule.id, 'C')
                        ?: throw IOException("The Capsule C: drive is missing.")
                    val driveD = capsuleStorage.getDriveRoot(capsule.id, 'D')
                        ?: throw IOException("The Capsule D: drive is missing.")
                    val shell = createRootFsShellPlan(
                        rootFsDirectory = rootFsDirectory,
                        shellScript = GraphicsTranslationPlan.createInstallScript(
                            guestPrefixPath = "/opt/vyrox/prefixes/" + capsule.id,
                            rootFsDirectory = rootFsDirectory
                        ),
                        extraMounts = listOf(
                            ProotBindMount(driveC, prefix.guestDriveCPath),
                            ProotBindMount(driveD, prefix.guestDriveDPath)
                        ),
                        fakeRoot = false
                    )
                    Triple(prefix, driveC, shell)
                }
                prepared = setup.third
                val execution = runManagedRuntimeProcess(
                    plan = setup.third.plan,
                    runId = "graphics-translation-" + System.currentTimeMillis(),
                    maxLogBytes = 4L * 1024L * 1024L,
                    timeoutMillis = 45L * 60L * 1000L,
                    label = "Install DXVK " + GraphicsTranslationPlan.DXVK_VERSION +
                        " and VKD3D-Proton " + GraphicsTranslationPlan.VKD3D_PROTON_VERSION
                )
                val output = withContext(Dispatchers.IO) {
                    runCatching { execution.result.logFile.readText() }
                        .getOrDefault("Graphics translation installation log could not be read.")
                        .takeLast(16_000)
                }
                val verified = withContext(Dispatchers.IO) {
                    GraphicsTranslationPlan.isInstalled(setup.first.directory, setup.second)
                }
                graphicsTranslationInstalled = !execution.stoppedByUser &&
                    !execution.result.timedOut &&
                    execution.result.exitCode == 0 &&
                    output.contains("VYROX_GRAPHICS_TRANSLATION_READY") &&
                    verified
                statusMessage = when {
                    execution.stoppedByUser ->
                        "Graphics translation installation was stopped. It is not marked as installed.\n" + output
                    execution.result.timedOut ->
                        "Graphics translation installation timed out. It is not marked as installed.\n" + output
                    graphicsTranslationInstalled ->
                        "DXVK " + GraphicsTranslationPlan.DXVK_VERSION +
                            " and VKD3D-Proton " + GraphicsTranslationPlan.VKD3D_PROTON_VERSION +
                            " DLLs were installed and verified in this Capsule's Wine prefix. Actual Direct3D game rendering still requires a real device workload test.\n" +
                            output
                    else ->
                        "DXVK/VKD3D installation did not pass the exit-code, completion-marker and DLL verification checks.\n" +
                            output
                }
            } catch (exception: Exception) {
                graphicsTranslationInstalled = false
                statusMessage = "Graphics translation installation failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                withContext(Dispatchers.IO) { prepared?.resolverFile?.delete() }
                isImporting = false
                refreshRootFsImages()
            }
        }
    }

    private fun createRootFsShellPlan(
        rootFsDirectory: File,
        shellScript: String,
        extraMounts: List<ProotBindMount>,
        fakeRoot: Boolean
    ): PreparedRootFsShell {
        val nativeDirectory = File(applicationInfo.nativeLibraryDir)
        val proot = ProotExecutableLocator.locate(
            nativeLibraryDirectory = nativeDirectory,
            supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
        ) ?: throw IOException("The Android PRoot executable or static loader is not packaged in this APK.")
        val resolver = createGuestResolverConfiguration()
        try {
            val allowedRoots = listOf(
                filesDir,
                cacheDir,
                nativeDirectory,
                File("/dev"),
                File("/proc")
            )
            val mounts = mutableListOf(
                ProotBindMount(File("/dev/null"), "/dev/null"),
                ProotBindMount(File("/dev/zero"), "/dev/zero"),
                ProotBindMount(File("/dev/urandom"), "/dev/urandom"),
                ProotBindMount(File("/dev/random"), "/dev/random"),
                ProotBindMount(File("/proc"), "/proc"),
                ProotBindMount(resolver, "/etc/resolv.conf")
            )
            mounts.addAll(extraMounts)
            val plan = ProotCommandPlanner.build(
                ProotLaunchRequest(
                    prootExecutable = proot,
                    nativeLibraryRoot = nativeDirectory,
                    rootFsDirectory = rootFsDirectory,
                    rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                    guestCommand = "/bin/sh",
                    guestWorkingDirectory = "/",
                    arguments = listOf("-c", shellScript),
                    fakeRoot = fakeRoot,
                    bindMounts = mounts,
                    allowedHostRoots = allowedRoots
                )
            )
            return PreparedRootFsShell(plan, resolver)
        } catch (exception: Exception) {
            resolver.delete()
            throw exception
        }
    }

    private fun refreshWindowsPrograms(capsuleId: String) {
        lifecycleScope.launch {
            val discovered = withContext(Dispatchers.IO) {
                windowsProgramManager.discover(capsuleId)
            }
            if (selectedCapsule?.id == capsuleId) {
                windowsPrograms.clear()
                windowsPrograms.addAll(discovered)
            }
        }
    }

    private fun runWindowsEntry(entry: CapsuleFileEntry) {
        if (entry.isDirectory) return
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val kind = when (extension) {
            "exe" -> WindowsInstallerKind.EXE
            "msi" -> WindowsInstallerKind.MSI
            else -> {
                statusMessage = "Choose a verified EXE or MSI file inside the Capsule."
                return
            }
        }
        val capsule = selectedCapsule ?: return
        val prefix = winePrefixInfo ?: run {
            statusMessage = "Create and verify this Capsule's Wine prefix before installing or running Windows programs."
            return
        }
        val guestDrive = if (selectedDrive == 'C') prefix.guestDriveCPath else prefix.guestDriveDPath
        runWindowsTarget(
            capsule = capsule,
            guestTargetPath = guestDrive + "/" + entry.relativePath,
            kind = kind,
            displayName = entry.name
        )
    }

    private fun launchWindowsProgram(program: InstalledWindowsProgram) {
        val capsule = selectedCapsule ?: return
        val prefix = winePrefixInfo ?: run {
            statusMessage = "Create and verify this Capsule's Wine prefix first."
            return
        }
        runWindowsTarget(
            capsule = capsule,
            guestTargetPath = prefix.guestDriveCPath + "/" + program.relativePath,
            kind = WindowsInstallerKind.EXE,
            displayName = program.name
        )
    }

    private fun runWindowsTarget(
        capsule: CapsuleInfo,
        guestTargetPath: String,
        kind: WindowsInstallerKind,
        displayName: String
    ) {
        if (isImporting || windowsDesktopVisible) return
        val prefix = winePrefixInfo ?: run {
            statusMessage = "This Capsule has no verified Wine prefix."
            return
        }
        if (!box64Installed || !wineInstalled) {
            statusMessage = "Build Box64 and install WineHQ stable before running Windows software."
            return
        }

        lifecycleScope.launch {
            isImporting = true
            windowsDesktopVisible = true
            windowsDesktopFrame = null
            windowsDesktopStatus = "Starting the isolated Windows desktop..."
            statusMessage = "Starting " + displayName + " inside Capsule " + capsule.name + "..."
            var prepared: PreparedRootFsShell? = null
            val audioBridge = PcmAudioBridge()
            val vncClient = VncRfbClient()
            activeAudioBridge = audioBridge
            activeVncClient = vncClient
            try {
                val rootFs = withContext(Dispatchers.IO) {
                    rootFsManager.getRootFsDirectory(
                        capsule.id,
                        LinuxRootFsArchitecture.ARM64
                    ) ?: throw IOException("The selected ARM64 Linux RootFS is no longer installed.")
                }
                val hostDriveC = withContext(Dispatchers.IO) {
                    capsuleStorage.getDriveRoot(capsule.id, 'C')
                        ?: throw IOException("The Capsule C: drive is missing.")
                }
                val hostDriveD = withContext(Dispatchers.IO) {
                    capsuleStorage.getDriveRoot(capsule.id, 'D')
                        ?: throw IOException("The Capsule D: drive is missing.")
                }
                val script = withContext(Dispatchers.IO) {
                    WineRuntimePlan.createInteractiveRunScript(
                        guestPrefixPath = "/opt/vyrox/prefixes/" + capsule.id,
                        guestTargetPath = guestTargetPath,
                        kind = kind,
                        rootFsDirectory = rootFs
                    )
                }
                val shell = withContext(Dispatchers.IO) {
                    createRootFsShellPlan(
                        rootFsDirectory = rootFs,
                        shellScript = script,
                        extraMounts = listOf(
                            ProotBindMount(hostDriveC, prefix.guestDriveCPath),
                            ProotBindMount(hostDriveD, prefix.guestDriveDPath)
                        ),
                        fakeRoot = false
                    )
                }
                prepared = shell
                runCatching { audioBridge.start() }
                    .onFailure {
                        windowsDesktopStatus = "Windows desktop starting. Android audio bridge is unavailable."
                    }

                val execution = runManagedRuntimeProcess(
                    plan = shell.plan,
                    runId = "windows-" + System.currentTimeMillis(),
                    maxLogBytes = 4L * 1024L * 1024L,
                    timeoutMillis = 4L * 60L * 60L * 1000L,
                    label = "Windows: " + displayName,
                    onStarted = {
                        vncClient.connectAsync(
                            onFrame = { frame ->
                                runOnUiThread {
                                    if (windowsDesktopVisible) windowsDesktopFrame = frame
                                }
                            },
                            onStatus = { message ->
                                runOnUiThread { windowsDesktopStatus = message }
                            },
                            onError = { message ->
                                runOnUiThread {
                                    windowsDesktopStatus = message
                                    statusMessage = message
                                }
                            }
                        )
                    }
                )
                val output = withContext(Dispatchers.IO) {
                    runCatching { execution.result.logFile.readText() }
                        .getOrDefault("Windows process log could not be read.")
                        .takeLast(16_000)
                }
                statusMessage = when {
                    execution.stoppedByUser -> "Windows session stopped by the user.\n" + output
                    execution.result.timedOut -> "Windows session reached its time limit.\n" + output
                    execution.result.exitCode == 0 -> displayName + " closed. Refreshing installed applications...\n" + output
                    else -> displayName + " failed with exit code " +
                        (execution.result.exitCode?.toString() ?: "unknown") + ".\n" + output
                }

                // Extract embedded Windows icons and then scan the Capsule C: drive.
                val iconShell = withContext(Dispatchers.IO) {
                    createRootFsShellPlan(
                        rootFsDirectory = rootFs,
                        shellScript = WineRuntimePlan.createIconExtractionScript(capsule.id),
                        extraMounts = listOf(
                            ProotBindMount(hostDriveC, prefix.guestDriveCPath),
                            ProotBindMount(hostDriveD, prefix.guestDriveDPath)
                        ),
                        fakeRoot = true
                    )
                }
                try {
                    runManagedRuntimeProcess(
                        plan = iconShell.plan,
                        runId = "windows-icons-" + System.currentTimeMillis(),
                        maxLogBytes = 1024L * 1024L,
                        timeoutMillis = 3L * 60L * 1000L,
                        label = "Discover Windows applications and icons"
                    )
                } finally {
                    withContext(Dispatchers.IO) { iconShell.resolverFile.delete() }
                }
                refreshWindowsPrograms(capsule.id)
            } catch (exception: Exception) {
                statusMessage = "Windows execution failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
                windowsDesktopStatus = statusMessage.orEmpty()
            } finally {
                vncClient.close()
                audioBridge.close()
                activeVncClient = null
                activeAudioBridge = null
                windowsDesktopVisible = false
                windowsDesktopFrame = null
                withContext(Dispatchers.IO) { prepared?.resolverFile?.delete() }
                isImporting = false
                refreshCapsuleDirectory()
                refreshWindowsPrograms(capsule.id)
            }
        }
    }

    private fun closeWindowsDesktop() {
        windowsDesktopStatus = "Closing Windows desktop..."
        stopRuntimeProcess()
    }

    private fun sendWindowsPointer(buttonMask: Int, x: Int, y: Int) {
        activeVncClient?.sendPointer(buttonMask, x, y)
    }

    private fun sendWindowsKeySym(keySym: Int, down: Boolean) {
        activeVncClient?.sendKeySym(keySym, down)
    }

    private fun sendWindowsText(value: String) {
        activeVncClient?.sendText(value)
    }

    private fun buildBox64(rootFs: LinuxRootFsInfo) {
        val capsule = selectedCapsule ?: run {
            statusMessage = "Open a Capsule before building Box64."
            return
        }
        if (isImporting) return
        if (rootFs.architecture != LinuxRootFsArchitecture.ARM64) {
            statusMessage = "Box64 requires an ARM64 Linux RootFS."
            return
        }

        lifecycleScope.launch {
            isImporting = true
            box64Installed = false
            statusMessage = "Building pinned Box64 0.4.4 inside this Capsule's ARM64 Linux RootFS."
            var prepared: Triple<ProotCommandPlan, File, File>? = null
            try {
                prepared = withContext(Dispatchers.IO) {
                    val nativeDirectory = File(applicationInfo.nativeLibraryDir)
                    val proot = ProotExecutableLocator.locate(
                        nativeLibraryDirectory = nativeDirectory,
                        supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
                    ) ?: throw IOException("The Android PRoot executable or static loader is not packaged in this APK.")
                    val rootFsDirectory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        LinuxRootFsArchitecture.ARM64
                    ) ?: throw IOException("The selected ARM64 RootFS is no longer installed in this Capsule.")

                    var resolver: File? = null
                    try {
                        resolver = createGuestResolverConfiguration()
                        val approvedRoots = listOf(
                            filesDir,
                            cacheDir,
                            nativeDirectory,
                            File("/dev"),
                            File("/proc")
                        )
                        val plan = ProotCommandPlanner.build(
                            ProotLaunchRequest(
                                prootExecutable = proot,
                                nativeLibraryRoot = nativeDirectory,
                                rootFsDirectory = rootFsDirectory,
                                rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                                guestCommand = "/bin/sh",
                                guestWorkingDirectory = "/",
                                arguments = listOf("-c", Box64BuildPlan.createShellScript()),
                                fakeRoot = true,
                                bindMounts = listOf(
                                    ProotBindMount(File("/dev/null"), "/dev/null"),
                                    ProotBindMount(File("/dev/zero"), "/dev/zero"),
                                    ProotBindMount(File("/dev/urandom"), "/dev/urandom"),
                                    ProotBindMount(File("/dev/random"), "/dev/random"),
                                    ProotBindMount(File("/proc"), "/proc"),
                                    ProotBindMount(resolver, "/etc/resolv.conf")
                                ),
                                allowedHostRoots = approvedRoots
                            )
                        )
                        Triple(plan, rootFsDirectory, resolver)
                    } catch (exception: Exception) {
                        resolver?.delete()
                        throw exception
                    }
                }

                val work = prepared ?: throw IOException("Box64 build request was not prepared.")
                val execution = runManagedRuntimeProcess(
                    plan = work.first,
                    runId = "box64-build-" + System.currentTimeMillis(),
                    maxLogBytes = 8L * 1024L * 1024L,
                    timeoutMillis = 60L * 60L * 1000L,
                    label = "Build Box64 0.4.4"
                )
                val output = withContext(Dispatchers.IO) {
                    runCatching { execution.result.logFile.readText() }
                        .getOrDefault("Runtime build log could not be read.")
                        .takeLast(12_000)
                }
                val verified = withContext(Dispatchers.IO) {
                    Box64BuildPlan.isInstalled(work.second)
                }
                box64Installed = !execution.stoppedByUser &&
                    execution.result.exitCode == 0 &&
                    !execution.result.timedOut &&
                    verified &&
                    output.contains("VYROX_BOX64_READY")
                statusMessage = when {
                    execution.stoppedByUser ->
                        "Box64 build was stopped by you. It is not marked as installed.\n" + output
                    box64Installed ->
                        "Box64 0.4.4 was built and passed a minimal x86-64 Linux syscall smoke test. Real application workloads and Windows EXE/MSI execution remain unverified.\n" + output
                    execution.result.timedOut ->
                        "Box64 build timed out and was stopped. It is not marked as installed.\n" + output
                    else ->
                        "Box64 build did not pass its exit-code, version-marker and installed-ELF checks.\n" + output
                }
            } catch (exception: Exception) {
                box64Installed = false
                statusMessage = "Box64 build failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                withContext(Dispatchers.IO) { prepared?.third?.delete() }
                isImporting = false
                refreshRootFsImages()
            }
        }
    }

    private suspend fun runManagedRuntimeProcess(
        plan: ProotCommandPlan,
        runId: String,
        maxLogBytes: Long,
        timeoutMillis: Long,
        label: String,
        onStarted: (() -> Unit)? = null
    ): RuntimeProcessExecution {
        val nativeDirectory = File(applicationInfo.nativeLibraryDir)
        val supervisor = RuntimeProcessSupervisor(
            logDirectory = File(cacheDir, "runtime-logs"),
            allowedHostRoots = listOf(filesDir, cacheDir, nativeDirectory)
        )
        // Starting PRoot is a short host operation, but make it non-cancellable
        // so a process handle is always returned to this method and can be
        // terminated from finally if the Activity is cancelled immediately.
        val process = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            supervisor.start(plan, runId, maxLogBytes)
        }

        var completedNormally = false
        try {
            activeRuntimeProcess = process
            activeRuntimeLabel = label
            isStoppingRuntimeProcess = false
            runtimeStopRequested = false
            onStarted?.invoke()

            val result = withContext(Dispatchers.IO) {
                process.await(timeoutMillis)
            }
            completedNormally = true
            return RuntimeProcessExecution(result, runtimeStopRequested)
        } finally {
            if (!completedNormally && process.isRunning) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    process.terminate()
                }
            }
            if (activeRuntimeProcess === process) {
                activeRuntimeProcess = null
                activeRuntimeLabel = null
                isStoppingRuntimeProcess = false
            }
        }
    }

    private fun stopRuntimeProcess() {
        val process = activeRuntimeProcess ?: return
        if (isStoppingRuntimeProcess || !process.isRunning) return

        runtimeStopRequested = true
        isStoppingRuntimeProcess = true
        statusMessage = "Stopping " + (activeRuntimeLabel ?: "the runtime process") + "..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    process.terminate()
                }
            } catch (exception: Exception) {
                statusMessage = "Could not stop the runtime process: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                isStoppingRuntimeProcess = false
            }
        }
    }

    private fun createGuestResolverConfiguration(): File {
        val connectivity = getSystemService(ConnectivityManager::class.java)
            ?: throw IOException("Android network state service is unavailable.")
        val network = connectivity.activeNetwork
            ?: throw IOException("Connect the phone to a network before building Box64.")
        val linkProperties = connectivity.getLinkProperties(network)
            ?: throw IOException("Android has no active network DNS configuration.")
        val servers = linkProperties.dnsServers.mapNotNull { address ->
            val host = address.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            when (address) {
                is Inet4Address, is Inet6Address -> host
                else -> null
            }
        }.distinct()
        if (servers.isEmpty()) {
            throw IOException("Android did not report any usable DNS servers for the active network.")
        }

        val file = File(cacheDir, "vyrox-box64-resolv-" + UUID.randomUUID() + ".conf")
        if (!file.createNewFile()) throw IOException("Could not create the temporary DNS configuration.")
        try {
            file.writeText(
                servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" } +
                    "options timeout:3 attempts:2\n",
                Charsets.US_ASCII
            )
            if (!file.setReadOnly()) {
                throw IOException("Could not make the temporary DNS configuration read-only.")
            }
            return file
        } catch (exception: Exception) {
            file.delete()
            throw exception
        }
    }

    private fun testRootFs(rootFs: LinuxRootFsInfo) {
        val capsule = selectedCapsule ?: run {
            statusMessage = "Open a Capsule before testing its Linux RootFS."
            return
        }
        if (isImporting) return
        if (rootFs.architecture != LinuxRootFsArchitecture.ARM64) {
            statusMessage = "The current Android PRoot host supports only an ARM64 Linux RootFS."
            return
        }

        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Starting a real PRoot shell probe..."
            try {
                val plan = withContext(Dispatchers.IO) {
                    val nativeDirectory = File(applicationInfo.nativeLibraryDir)
                    val proot = ProotExecutableLocator.locate(
                        nativeLibraryDirectory = nativeDirectory,
                        supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
                    ) ?: throw IOException("The Android PRoot executable or static loader is not packaged in this APK.")
                    val rootFsDirectory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        rootFs.architecture
                    ) ?: throw IOException("The selected Linux RootFS is no longer installed in this Capsule.")

                    ProotCommandPlanner.build(
                        ProotLaunchRequest(
                            prootExecutable = proot,
                            nativeLibraryRoot = nativeDirectory,
                            rootFsDirectory = rootFsDirectory,
                            rootFsArchitecture = rootFs.architecture,
                            guestCommand = "/bin/sh",
                            guestWorkingDirectory = "/",
                            arguments = listOf("-c", "printf VYROX_ROOTFS_OK; uname -m; id"),
                            allowedHostRoots = listOf(filesDir, cacheDir, nativeDirectory)
                        )
                    )
                }
                val execution = runManagedRuntimeProcess(
                    plan = plan,
                    runId = "rootfs-probe-" + System.currentTimeMillis(),
                    maxLogBytes = 256L * 1024L,
                    timeoutMillis = 30_000L,
                    label = "Linux RootFS shell probe"
                )
                val output = withContext(Dispatchers.IO) {
                    runCatching { execution.result.logFile.readText() }
                        .getOrDefault("Runtime log could not be read.")
                        .takeLast(8_000)
                }
                statusMessage = if (execution.stoppedByUser) {
                    "PRoot shell probe was stopped by you.\n" + output
                } else {
                    "PRoot shell exit: " + execution.result.exitCode +
                        " • timeout: " + execution.result.timedOut +
                        " • duration: " + execution.result.durationMillis + " ms\n" + output
                }
            } catch (exception: Exception) {
                statusMessage = "PRoot shell test failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                isImporting = false
            }
        }
    }

    private fun extractZipEntry(entry: CapsuleFileEntry) {
        val capsule = selectedCapsule ?: return
        if (isImporting) return
        val drive = selectedDrive

        lifecycleScope.launch {
            isImporting = true
            statusMessage = "Extracting ZIP into the selected Capsule..."
            try {
                val extracted = withContext(Dispatchers.IO) {
                    capsuleStorage.extractZipArchive(capsule.id, drive, entry.relativePath)
                }
                statusMessage = "Extracted to " + drive + ": " + extracted.relativePath
                refreshCapsuleDirectory()
            } catch (exception: Exception) {
                statusMessage = exception.message ?: "Could not safely extract this ZIP archive."
            } finally {
                isImporting = false
            }
        }
    }

    private data class RuntimeProcessExecution(
        val result: RuntimeProcessResult,
        val stoppedByUser: Boolean
    )

    private data class RootFsReadiness(
        val images: List<LinuxRootFsInfo>,
        val box64Installed: Boolean,
        val wineInstalled: Boolean,
        val winePrefixInfo: WinePrefixInfo?,
        val graphicsTranslationInstalled: Boolean
    )

    private data class PreparedRootFsShell(
        val plan: ProotCommandPlan,
        val resolverFile: File
    )

    private data class PreparedWinePrefix(
        val rootFsDirectory: File,
        val manager: WinePrefixManager,
        val ticket: WinePrefixCreationTicket,
        val shell: PreparedRootFsShell
    )

    private data class CapsuleImportTarget(
        val capsuleId: String,
        val driveLetter: Char,
        val parentRelativePath: String
    )

    private fun queryDisplayName(uri: Uri): String {
        val nameFromProvider = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameColumn >= 0) {
                cursor.getString(nameColumn)
            } else {
                null
            }
        }

        return nameFromProvider
            ?.takeIf { it.isNotBlank() }
            ?: (uri.lastPathSegment ?: "installer.exe")
    }
    private companion object {
        const val STATE_DESKTOP_MODE = "vyrox.desktop_mode"
    }
}
