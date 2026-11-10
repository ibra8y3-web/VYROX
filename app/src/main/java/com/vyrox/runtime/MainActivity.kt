package com.vyrox.runtime

import android.net.Uri
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
import com.vyrox.engine.ProotCommandPlanner
import com.vyrox.engine.ProotBindMount
import com.vyrox.engine.ProotLaunchRequest
import com.vyrox.engine.RuntimeProcessSupervisor
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
    private lateinit var installerStorage: InstallerStorage
    private lateinit var capsuleStorage: CapsuleStorage
    private lateinit var hostCapabilities: HostCapabilities
    private lateinit var rootFsManager: LinuxRootFsManager
    private var prootBinaryAvailable by mutableStateOf(false)
    private var box64Installed by mutableStateOf(false)
    private var winePrefixInfo by mutableStateOf<WinePrefixInfo?>(null)
    private val installers = mutableStateListOf<ImportedInstaller>()
    private val capsules = mutableStateListOf<CapsuleInfo>()
    private val capsuleSnapshots = mutableStateListOf<CapsuleSnapshotInfo>()
    private val capsuleEntries = mutableStateListOf<CapsuleFileEntry>()
    private val capsuleSearchResults = mutableStateListOf<CapsuleFileEntry>()
    private val rootFsImages = mutableStateListOf<LinuxRootFsInfo>()

    private var selectedCapsule by mutableStateOf<CapsuleInfo?>(null)
    private var snapshotManagerCapsule by mutableStateOf<CapsuleInfo?>(null)
    private var selectedDrive by mutableStateOf('C')
    private var selectedPath by mutableStateOf("")
    private var pendingCapsuleImport: CapsuleImportTarget? = null
    private var pendingRootFsCapsuleId: String? = null
    private var pendingCapsuleBackupId: String? = null

    private var isImporting by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)

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
        requestedOrientation = if (desktopModeEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        installerStorage = InstallerStorage(applicationContext)
        capsuleStorage = CapsuleStorage(File(filesDir, "capsules"))
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
                winePrefixInfo = winePrefixInfo,
                isImporting = isImporting,
                statusMessage = statusMessage,
                isDesktopMode = desktopModeEnabled,
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
        winePrefixInfo = null
        selectedCapsule = capsule
        selectedDrive = 'C'
        selectedPath = ""
        capsuleEntries.clear()
        capsuleSearchResults.clear()
        rootFsImages.clear()
        refreshCapsuleDirectory()
        refreshRootFsImages()
    }

    private fun closeCapsuleExplorer() {
        box64Installed = false
        winePrefixInfo = null
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
                    val prefixInfo = arm64RootFs
                        ?.let { info ->
                            rootFsManager.getRootFsDirectory(capsule.id, info.architecture)
                                ?.let { WinePrefixManager(it).inspect(capsule.id) }
                        }
                    Triple(installed, hasBox64, prefixInfo)
                }
                if (selectedCapsule?.id == capsule.id) {
                    rootFsImages.clear()
                    rootFsImages.addAll(rootFsState.first)
                    box64Installed = rootFsState.second
                    winePrefixInfo = rootFsState.third
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
            statusMessage = "Checking existing Wine amd64 package candidates. This is read-only; no packages will be installed."
            try {
                val result = withContext(Dispatchers.IO) {
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
                    val plan = ProotCommandPlanner.build(
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
                    val supervisor = RuntimeProcessSupervisor(
                        logDirectory = File(cacheDir, "runtime-logs"),
                        allowedHostRoots = allowedRoots
                    )
                    val process = supervisor.start(
                        plan = plan,
                        runId = "wine-preflight-" + System.currentTimeMillis(),
                        maxLogBytes = 256L * 1024L
                    ).await(60_000L)
                    val output = runCatching { process.logFile.readText() }
                        .getOrDefault("")
                        .takeLast(12_000)
                    if (process.timedOut) {
                        throw IOException("Wine package preflight timed out.\n" + output)
                    }
                    if (process.exitCode != 0) {
                        throw IOException("Wine package preflight shell exited with code " +
                            process.exitCode + ".\n" + output)
                    }
                    WinePackagePreflight.parse(output)
                }
                statusMessage = result.summary()
            } catch (exception: Exception) {
                statusMessage = "Wine package preflight failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                isImporting = false
            }
        }
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
            statusMessage = "Building pinned Box64 0.4.4 inside this Capsule's ARM64 Linux RootFS. Build dependencies will be installed inside that RootFS."
            try {
                val buildResult = withContext(Dispatchers.IO) {
                    val nativeDirectory = File(applicationInfo.nativeLibraryDir)
                    val proot = ProotExecutableLocator.locate(
                        nativeLibraryDirectory = nativeDirectory,
                        supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
                    ) ?: throw IOException("The Android PRoot executable or static loader is not packaged in this APK.")
                    val rootFsDirectory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        LinuxRootFsArchitecture.ARM64
                    ) ?: throw IOException("The selected ARM64 RootFS is no longer installed in this Capsule.")

                    val resolverConfig = createGuestResolverConfiguration()
                    try {
                        val systemBinds = listOf(
                            ProotBindMount(File("/dev/null"), "/dev/null"),
                            ProotBindMount(File("/dev/zero"), "/dev/zero"),
                            ProotBindMount(File("/dev/urandom"), "/dev/urandom"),
                            ProotBindMount(File("/dev/random"), "/dev/random"),
                            ProotBindMount(File("/proc"), "/proc"),
                            ProotBindMount(resolverConfig, "/etc/resolv.conf")
                        )
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
                                bindMounts = systemBinds,
                                allowedHostRoots = approvedRoots
                            )
                        )
                        val supervisor = RuntimeProcessSupervisor(
                            logDirectory = File(cacheDir, "runtime-logs"),
                            allowedHostRoots = listOf(filesDir, cacheDir, nativeDirectory)
                        )
                        val result = supervisor.start(
                            plan = plan,
                            runId = "box64-build-" + System.currentTimeMillis(),
                            maxLogBytes = 8L * 1024L * 1024L
                        ).await(60L * 60L * 1000L)
                        val output = runCatching { result.logFile.readText() }
                            .getOrDefault("Runtime build log could not be read.")
                            .takeLast(12_000)
                        Triple(result, output, Box64BuildPlan.isInstalled(rootFsDirectory))
                    } finally {
                        resolverConfig.delete()
                    }
                }

                val result = buildResult.first
                val output = buildResult.second
                val verified = buildResult.third
                box64Installed = result.exitCode == 0 &&
                    !result.timedOut &&
                    verified &&
                    output.contains("VYROX_BOX64_READY")
                statusMessage = if (box64Installed) {
                    "Box64 0.4.4 was built inside this Capsule and passed a minimal x86-64 Linux syscall smoke test. Real application workloads and Windows EXE/MSI execution remain unverified.\n" + output
                } else {
                    "Box64 build did not pass its exit-code, version-marker and installed-ELF checks.\n" + output
                }
            } catch (exception: Exception) {
                box64Installed = false
                statusMessage = "Box64 build failed: " +
                    (exception.message ?: exception.javaClass.simpleName)
            } finally {
                isImporting = false
                refreshRootFsImages()
            }
        }
    }

    /**
     * Supplies only DNS server addresses exposed by Android's active network.
     * The file lives in app-private cache and is mounted read-only by PRoot's
     * path redirection for this Box64 build process only.
     */
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
                val result = withContext(Dispatchers.IO) {
                    val nativeDirectory = File(applicationInfo.nativeLibraryDir)
                    val proot = ProotExecutableLocator.locate(
                        nativeLibraryDirectory = nativeDirectory,
                        supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
                    ) ?: throw IOException("The Android PRoot executable or static loader is not packaged in this APK.")

                    val rootFsDirectory = rootFsManager.getRootFsDirectory(
                        capsule.id,
                        rootFs.architecture
                    ) ?: throw IOException("The selected Linux RootFS is no longer installed in this Capsule.")

                    val plan = ProotCommandPlanner.build(
                        ProotLaunchRequest(
                            prootExecutable = proot,
                            nativeLibraryRoot = nativeDirectory,
                            rootFsDirectory = rootFsDirectory,
                            rootFsArchitecture = rootFs.architecture,
                            guestCommand = "/bin/sh",
                            guestWorkingDirectory = "/",
                            arguments = listOf(
                                "-c",
                                "printf VYROX_ROOTFS_OK; uname -m; id"
                            ),
                            allowedHostRoots = listOf(
                                filesDir,
                                cacheDir,
                                nativeDirectory
                            )
                        )
                    )
                    val supervisor = RuntimeProcessSupervisor(
                        logDirectory = File(cacheDir, "runtime-logs"),
                        allowedHostRoots = listOf(filesDir, cacheDir, nativeDirectory)
                    )
                    supervisor.start(
                        plan = plan,
                        runId = "rootfs-probe-" + System.currentTimeMillis(),
                        maxLogBytes = 256L * 1024L
                    ).await(30_000L)
                }

                val output = runCatching { result.logFile.readText() }
                    .getOrDefault("Runtime log could not be read.")
                    .takeLast(8_000)
                statusMessage = "PRoot shell exit: " + result.exitCode +
                    " • timeout: " + result.timedOut +
                    " • duration: " + result.durationMillis + " ms\n" + output
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
