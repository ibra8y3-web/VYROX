package com.vyrox.runtime

import android.net.Uri
import android.os.Bundle
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
import com.vyrox.runtime.core.host.HostCapabilities
import com.vyrox.runtime.core.host.HostCapabilitiesProbe
import com.vyrox.runtime.ui.VyroxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    private lateinit var installerStorage: InstallerStorage
    private lateinit var capsuleStorage: CapsuleStorage
    private lateinit var hostCapabilities: HostCapabilities
    private val installers = mutableStateListOf<ImportedInstaller>()
    private val capsules = mutableStateListOf<CapsuleInfo>()
    private val capsuleEntries = mutableStateListOf<CapsuleFileEntry>()

    private var selectedCapsule by mutableStateOf<CapsuleInfo?>(null)
    private var selectedDrive by mutableStateOf('C')
    private var selectedPath by mutableStateOf("")
    private var pendingCapsuleImport: CapsuleImportTarget? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installerStorage = InstallerStorage(applicationContext)
        capsuleStorage = CapsuleStorage(File(filesDir, "capsules"))
        hostCapabilities = HostCapabilitiesProbe.read(applicationContext)
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
                hostCapabilities = hostCapabilities,
                isImporting = isImporting,
                statusMessage = statusMessage,
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
                onDeleteCapsuleEntry = ::deleteCapsuleEntry
            )
        }
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
        refreshCapsuleDirectory()
    }

    private fun closeCapsuleExplorer() {
        selectedCapsule = null
        selectedPath = ""
        capsuleEntries.clear()
    }

    private fun selectCapsuleDrive(driveLetter: Char) {
        if (driveLetter != 'C' && driveLetter != 'D') return
        selectedDrive = driveLetter
        selectedPath = ""
        refreshCapsuleDirectory()
    }

    private fun navigateCapsuleDirectory(relativePath: String) {
        selectedPath = relativePath
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
}
