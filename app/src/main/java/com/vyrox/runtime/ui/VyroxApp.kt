package com.vyrox.runtime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.vyrox.runtime.core.capsule.CapsuleInfo
import com.vyrox.runtime.core.files.ImportedInstaller
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
    isImporting: Boolean,
    statusMessage: String?,
    onImport: () -> Unit,
    onDeleteInstaller: (ImportedInstaller) -> Unit,
    onCreateCapsule: (String) -> Unit,
    onDeleteCapsule: (CapsuleInfo) -> Unit
) {
    var capsuleName by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = VyroxColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
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
                        if (capsules.isEmpty()) {
                            Text(
                                "No Capsules created yet.",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                capsules.forEach { capsule ->
                                    CapsuleRow(capsule = capsule, onDelete = onDeleteCapsule)
                                }
                            }
                        }
                    }
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
                            "This version imports EXE and MSI installer files into private app storage. It does not run Windows applications yet.",
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
                                Text("Import EXE / MSI installer")
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


@Composable
private fun CapsuleRow(
    capsule: CapsuleInfo,
    onDelete: (CapsuleInfo) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202940)),
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
                Text(capsule.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "C: system folders • D: data folders • private app storage",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = { onDelete(capsule) }) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun InstallerRow(
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

private fun formatBytes(size: Long): String {
    val megabytes = size / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", megabytes)
    }
}
