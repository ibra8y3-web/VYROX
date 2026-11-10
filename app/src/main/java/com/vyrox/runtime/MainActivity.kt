package com.vyrox.runtime

import android.net.Uri
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
import com.vyrox.runtime.core.files.ImportedInstaller
import com.vyrox.runtime.core.files.InstallerStorage
import com.vyrox.runtime.core.engine.ProotExecutableLocator
import com.vyrox.runtime.core.host.HostCapabilities
import com.vyrox.runtime.core.host.HostCapabilitiesProbe
import com.vyrox.engine.LinuxRootFsInfo
import com.vyrox.engine.LinuxRootFsManager
import com.vyrox.runtime.ui.VyroxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var desktopModeEnabled by mutableStateOf(true)
    private lateinit var installerStorage: InstallerStorage
    private lateinit var capsuleStorage: CapsuleStorage
    private lateinit var hostCapabilities: HostCapabilities
    private lateinit var rootFsManager: LinuxRootFsManager
    private var prootBinaryAvailable by mutableStateOf(false)
    private val installers = mutableStateListOf<ImportedInstaller>()
    private val capsules = mutableStateListOf<CapsuleInfo>()
    private val capsuleEntries = mutableStateListOf<CapsuleFileEntry>()
    private val capsuleSearchResults = mutableStateListOf<CapsuleFileEntry>()
    private val rootFsImages = mutableStateListOf<LinuxRootFsInfo>()

    private var selectedCapsule by mutableStateOf<CapsuleInfo?>(null)
    private var selectedDrive by mutableStateOf('C')
    private var selectedPath by mutableStateOf("")
    private var pendingCapsuleImport: CapsuleImportTarget? = null
    private var pendingRootFsCapsuleId: String? = null

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
                selectedCapsule = selectedCapsule,
                selectedDrive = selectedDrive,
                currentPath = selectedPath,
                capsuleEntries = capsuleEntries.toList(),
                capsuleSearchResults = capsuleSearchResults.toList(),
                rootFsImages = rootFsImages.toList(),
                hostCapabilities = hostCapabilities,
                prootBinaryAvailable = prootBinaryAvailable,
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
                onExtractZipEntry = ::extractZipEntry
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

    private fun openCapsule(capsule: CapsuleInfo) {
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
                val installed = withContext(Dispatchers.IO) {
                    rootFsManager.listInstalled(capsule.id)
                }
                if (selectedCapsule?.id == capsule.id) {
                    rootFsImages.clear()
                    rootFsImages.addAll(installed)
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
