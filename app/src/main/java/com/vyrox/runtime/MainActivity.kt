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
import com.vyrox.runtime.core.files.ImportedInstaller
import com.vyrox.runtime.core.files.InstallerStorage
import com.vyrox.runtime.ui.VyroxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : ComponentActivity() {
    private lateinit var installerStorage: InstallerStorage
    private val installers = mutableStateListOf<ImportedInstaller>()

    private var isImporting by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)

    private val openInstaller = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importInstaller(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installerStorage = InstallerStorage(applicationContext)
        installers.addAll(installerStorage.listImported())

        setContent {
            VyroxApp(
                installers = installers.toList(),
                isImporting = isImporting,
                statusMessage = statusMessage,
                onImport = {
                    if (!isImporting) openInstaller.launch(arrayOf("*/*"))
                },
                onDelete = ::deleteInstaller
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
