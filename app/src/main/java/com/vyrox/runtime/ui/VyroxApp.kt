package com.vyrox.runtime.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyrox.runtime.core.capsule.CapsuleFileEntry
import com.vyrox.runtime.core.capsule.CapsuleInfo
import com.vyrox.runtime.core.capsule.CapsuleSnapshotInfo
import com.vyrox.runtime.core.capsule.InstalledWindowsProgram
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

private val VyroxArabicText = mapOf(
    "Each Capsule has separate app-private files and virtual C:/D: folder layouts. This is logical file separation, not a virtual machine or a hardened security boundary." to "لكل بيئة ملفات خاصة ومجلدات C:/D: منفصلة منطقيًا. هذا فصل للملفات وليس جهازًا افتراضيًا أو حاجزًا أمنيًا معزولًا.",
    "Imported EXE and MSI files are setup files only. They are not displayed as installed apps until Wine installation is connected and verified." to "ملفات EXE وMSI المستوردة هي ملفات تثبيت فقط، ولن تظهر كتطبيقات مثبتة قبل ربط Wine والتحقق من نجاح التثبيت.",
    "A managed Android workspace designed for future Windows compatibility. Imported Windows files are stored privately and are not executed yet." to "مساحة عمل على أندرويد مجهزة لتوافق Windows. تُحفظ ملفات Windows المستوردة بشكل خاص ولا يتم تشغيلها حاليًا.",
    "Importing stores an EXE/MSI in private app storage. It does not install or run Windows software until a compatible runtime is integrated." to "الاستيراد يحفظ ملف EXE أو MSI داخل مساحة التطبيق الخاصة. لا يعني ذلك تثبيت برنامج Windows أو تشغيله قبل دمج محرك متوافق.",
    "The file explorer reads and changes the app-private C: and D: workspace folders. Open a Capsule to browse its files." to "يعرض مستكشف الملفات مجلدات C: وD: الخاصة بالبيئة ويتيح إدارتها. افتح بيئة لتصفح ملفاتها.",
    "Windows execution remains disabled until Wine and its per-environment prefix are verified." to "سيظل تشغيل Windows معطلًا حتى يتم التحقق من Wine وبيئة التشغيل الخاصة بكل مساحة.",
    "Windows programs will appear here after a real Wine installation succeeds." to "ستظهر برامج Windows هنا بعد نجاح تثبيت Wine فعليًا.",
    "A clean desktop for your Android-powered PC environment." to "سطح مكتب منظم لبيئة الكمبيوتر على أندرويد.",
    "Copy / move destination path (blank = drive root)" to "مسار النسخ أو النقل (فارغ = جذر القرص)",
    "No snapshots have been created for this Capsule." to "لم يتم إنشاء لقطات لهذه البيئة.",
    "Create, open, clone and back up environments" to "إنشاء البيئات وفتحها ونسخها احتياطيًا",
    "Manage each environment's C: and D: files" to "إدارة ملفات C: وD: لكل بيئة",
    "Import and inspect EXE, MSI and ZIP files" to "استيراد وفحص ملفات EXE وMSI وZIP",
    "Manage RootFS, test PRoot and build Box64" to "إدارة RootFS واختبار PRoot وبناء Box64",
    "GPU / DirectX translation: not integrated" to "ترجمة الرسوميات وDirectX: لم يتم دمجها بعد",
    "Windows runtime environment for Android" to "بيئة تشغيل Windows على أندرويد",
    "Your PC. Your Phone. Your Environment." to "كمبيوترك وبيئتك في هاتفك.",
    "A desktop workspace inside Android" to "سطح مكتب متكامل داخل أندرويد",
    "No Capsule workspaces created yet." to "لم يتم إنشاء أي بيئة بعد.",
    "Wine compatibility: not integrated" to "توافق Wine: لم يتم دمجه بعد",
    "CPU translation: not integrated" to "ترجمة المعالج: لم يتم دمجها بعد",
    "No Windows apps installed yet" to "لا توجد تطبيقات Windows مثبتة حتى الآن",
    "Create your first environment" to "أنشئ أول بيئة لك",
    "Import Linux RootFS archive" to "استيراد ملف نظام لينكس",
    "Switch to desktop workspace" to "العودة إلى سطح المكتب",
    "Active runtime operation" to "عملية تشغيل نشطة",
    "VYROX Installer Library" to "مكتبة التثبيت في VYROX",
    "No imported installers." to "لا توجد ملفات تثبيت مستوردة.",
    "Installed applications" to "التطبيقات المثبتة",
    "Run" to "تشغيل",
    "VYROX Windows Desktop" to "سطح مكتب Windows في VYROX",
    "Close desktop" to "إغلاق سطح المكتب",
    "Tap here to type" to "اضغط هنا للكتابة",
    "Select a Capsule first" to "اختر بيئة أولًا",
    "Import EXE / MSI / ZIP" to "استيراد EXE أو MSI أو ZIP",
    "Import Capsule backup" to "استيراد نسخة احتياطية لبيئة",
    "This folder is empty." to "هذا المجلد فارغ.",
    "Open Capsule Manager" to "فتح مدير البيئات",
    "Runtime engine stack" to "مكونات محرك التشغيل",
    "Workspace and files" to "مساحة العمل والملفات",
    "Current environment" to "البيئة الحالية",
    "Importing RootFS..." to "جارٍ استيراد نظام لينكس...",
    "Check Wine packages" to "فحص حزم Wine",
    "Runtime and device" to "المحرك والجهاز",
    "Switch orientation" to "تغيير اتجاه الشاشة",
    "Capsule workspaces" to "مساحات البيئات",
    "Desktop shortcuts" to "اختصارات سطح المكتب",
    "Import setup file" to "استيراد ملف تثبيت",
    "Build Box64 0.4.4" to "بناء Box64 0.4.4",
    "Search this drive" to "البحث في هذا القرص",
    "Open environment" to "فتح البيئة",
    "New Capsule name" to "اسم البيئة الجديدة",
    "Device readiness" to "جاهزية الجهاز",
    "Manage snapshots" to "إدارة اللقطات",
    "Foundation build" to "البناء الأساسي",
    "Supported ABIs: " to "معماريات الجهاز: ",
    "Linux and Box64" to "لينكس وBox64",
    "Runtime engines" to "محركات التشغيل",
    "New folder name" to "اسم المجلد الجديد",
    "Imported files" to "ملفات مستوردة",
    "Your workspace" to "مساحة عملك",
    "Create Capsule" to "إنشاء بيئة",
    "Rename Capsule" to "إعادة تسمية البيئة",
    "Search results" to "نتائج البحث",
    "File Explorer" to "مستكشف الملفات",
    "Create folder" to "إنشاء مجلد",
    "Rebuild Box64" to "إعادة بناء Box64",
    "Environments" to "البيئات",
    "Not selected" to "غير محددة",
    "Importing..." to "جارٍ الاستيراد...",
    "Linux RootFS" to "نظام ملفات لينكس",
    "Snapshots — " to "اللقطات — ",
    "Capsule name" to "اسم البيئة",
    "Import file" to "استيراد ملف",
    "Extract ZIP" to "فك ضغط ZIP",
    "Test shell" to "اختبار الطرفية",
    "Stopping…" to "جارٍ الإيقاف…",
    "C: System" to "C: النظام",
    "Settings" to "الإعدادات",
    "Language" to "اللغة",
    "New name" to "الاسم الجديد",
    "Snapshot" to "لقطة",
    "Desktop" to "سطح المكتب",
    "Restore" to "استعادة",
    "D: Data" to "D: البيانات",
    "Delete" to "حذف",
    "Cancel" to "إلغاء",
    "Remove" to "إزالة",
    "Search" to "بحث",
    "Locate" to "تحديد الموقع",
    "Rename" to "إعادة تسمية",
    "Backup" to "نسخ احتياطي",
    "Close" to "إغلاق",
    "Clone" to "استنساخ",
    "Save" to "حفظ",
    "Stop" to "إيقاف",
    "Open" to "فتح",
    "Copy" to "نسخ",
    "Move" to "نقل",
    "Up" to "للأعلى"
)

private val LocalVyroxLanguage = androidx.compose.runtime.compositionLocalOf { "en" }

@Composable
internal fun vxText(value: String): String =
    if (LocalVyroxLanguage.current == "ar") VyroxArabicText[value] ?: value else value

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
    wineInstalled: Boolean,
    winePrefixInfo: WinePrefixInfo?,
    installedWindowsPrograms: List<InstalledWindowsProgram>,
    windowsDesktopVisible: Boolean,
    windowsDesktopFrame: Bitmap?,
    windowsDesktopStatus: String,
    isImporting: Boolean,
    activeRuntimeLabel: String?,
    isStoppingRuntimeProcess: Boolean,
    onStopRuntimeProcess: () -> Unit,
    statusMessage: String?,
    isDesktopMode: Boolean,
    language: String,
    onLanguageChange: (String) -> Unit,
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
    onCheckWinePackages: (LinuxRootFsInfo) -> Unit,
    onInstallWine: (LinuxRootFsInfo) -> Unit,
    onCreateWinePrefix: (LinuxRootFsInfo) -> Unit,
    onRunWindowsEntry: (CapsuleFileEntry) -> Unit,
    onLaunchWindowsProgram: (InstalledWindowsProgram) -> Unit,
    onCloseWindowsDesktop: () -> Unit,
    onVncPointer: (Int, Int, Int) -> Unit,
    onVncKeySym: (Int, Boolean) -> Unit,
    onVncText: (String) -> Unit,
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

    CompositionLocalProvider(
        LocalVyroxLanguage provides language,
        LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
    MaterialTheme(colorScheme = VyroxColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (windowsDesktopVisible) {
                WindowsDesktopViewer(
                    frame = windowsDesktopFrame,
                    status = windowsDesktopStatus,
                    onClose = onCloseWindowsDesktop,
                    onPointer = onVncPointer,
                    onKeySym = onVncKeySym,
                    onText = onVncText
                )
            } else if (isDesktopMode) {
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
                    wineInstalled = wineInstalled,
                    winePrefixInfo = winePrefixInfo,
                    installedWindowsPrograms = installedWindowsPrograms,
                    onRunWindowsEntry = onRunWindowsEntry,
                    onLaunchWindowsProgram = onLaunchWindowsProgram,
                    isImporting = isImporting,
                    activeRuntimeLabel = activeRuntimeLabel,
                    isStoppingRuntimeProcess = isStoppingRuntimeProcess,
                    onStopRuntimeProcess = onStopRuntimeProcess,
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
                    onCheckWinePackages = onCheckWinePackages,
                    onInstallWine = onInstallWine,
                    onCreateWinePrefix = onCreateWinePrefix,
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
                    onToggleDesktopMode = onToggleDesktopMode,
                    language = language,
                    onLanguageChange = onLanguageChange
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
                        text = vxText("VYROX"),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = vxText("Windows runtime environment for Android"),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                    OutlinedButton(onClick = onToggleDesktopMode) {
                        Text(vxText("Switch to desktop workspace"))
                    }
                }


                activeRuntimeLabel?.let { label ->
                    ActiveRuntimeOperationCard(
                        label = label,
                        isStopping = isStoppingRuntimeProcess,
                        onStop = onStopRuntimeProcess
                    )
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
                        Text(vxText("Capsule workspaces"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Create separate private storage workspaces. These folders are not virtual machines or hardened security sandboxes.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                        )
                        OutlinedTextField(
                            value = capsuleName,
                            onValueChange = { capsuleName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(vxText("Capsule name")) }
                        )
                        Button(
                            onClick = {
                                onCreateCapsule(capsuleName)
                                capsuleName = ""
                            },
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
                        onCheckWinePackages = onCheckWinePackages,
                        onInstallWine = onInstallWine,
                        onCreateWinePrefix = onCreateWinePrefix,
                        box64Installed = box64Installed,
                        wineInstalled = wineInstalled,
                        winePrefixInfo = winePrefixInfo,
                        installedWindowsPrograms = installedWindowsPrograms,
                        onRunWindowsEntry = onRunWindowsEntry,
                        onLaunchWindowsProgram = onLaunchWindowsProgram,
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
                        Text(vxText("Foundation build"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Import EXE/MSI installers into a Capsule drive, then run them with its verified Wine prefix.",
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
                                Text(vxText("  Importing..."))
                            } else {
                                Text(vxText("Import EXE / MSI / ZIP"))
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
                        text = vxText("Imported installers"),
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
                            text = vxText("No installers imported. Choose an EXE or MSI file to add it to this device's private VYROX storage."),
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
                        Text(vxText("Runtime engines"), fontWeight = FontWeight.Bold)
                        Text(vxText("Wine compatibility: not integrated"))
                        Text(vxText("CPU translation: not integrated"))
                        Text(vxText("GPU / DirectX translation: not integrated"))
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


    val snapshotCapsule = snapshotManagerCapsule
    if (snapshotCapsule != null) {
        val capsule = snapshotCapsule
        AlertDialog(
            onDismissRequest = onCloseCapsuleSnapshotManager,
            title = { Text(vxText("Snapshots — ") + capsule.name) },
            text = {
                if (capsuleSnapshots.isEmpty()) {
                    Text(vxText("No snapshots have been created for this Capsule."))
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
                                                onRestoreCapsuleSnapshot(capsule, snapshot)
                                            },
                                            enabled = !isImporting
                                        ) { Text(vxText("Restore")) }
                                        OutlinedButton(
                                            onClick = {
                                                onDeleteCapsuleSnapshot(capsule, snapshot)
                                            },
                                            enabled = capsule != null && !isImporting
                                        ) { Text(vxText("Delete")) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = onCloseCapsuleSnapshotManager) { Text(vxText("Close")) }
            }
        )
    }

    if (pendingRenameCapsule != null) {
        val capsule = pendingRenameCapsule
        AlertDialog(
            onDismissRequest = { pendingRenameCapsule = null },
            title = { Text(vxText("Rename Capsule")) },
            text = {
                OutlinedTextField(
                    value = capsuleRenameValue,
                    onValueChange = { capsuleRenameValue = it },
                    singleLine = true,
                    label = { Text(vxText("New Capsule name")) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        capsule?.let { onRenameCapsule(it, capsuleRenameValue) }
                        pendingRenameCapsule = null
                    },
                    enabled = capsuleRenameValue.trim().length >= 2
                ) { Text(vxText("Save")) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingRenameCapsule = null }) { Text(vxText("Cancel")) }
            }
        )
    }

    if (pendingRenameEntry != null) {
        val entry = pendingRenameEntry
        AlertDialog(
            onDismissRequest = { pendingRenameEntry = null },
            title = { Text(vxText("Rename ${entry?.name}")) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text(vxText("New name")) }
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
                    Text(vxText("Save"))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingRenameEntry = null }) {
                    Text(vxText("Cancel"))
                }
            }
        )
    }

    if (pendingDeleteEntry != null) {
        val entry = pendingDeleteEntry
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text(vxText("Remove ${entry?.name}?")) },
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
                    Text(vxText("Delete"))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDeleteEntry = null }) {
                    Text(vxText("Cancel"))
                }
            }
        )
    }
    }
}


@Composable
internal fun ActiveRuntimeOperationCard(
    label: String,
    isStopping: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(vxText("Active runtime operation"), fontWeight = FontWeight.SemiBold)
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = onStop,
                enabled = !isStopping
            ) {
                Text(if (isStopping) vxText("Stopping…") else vxText("Stop"))
            }
        }
    }
}

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
            Text(vxText("Runtime engine stack"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
            Text(vxText("Device readiness"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(vxText("Supported ABIs: ") + profile.supportedAbis.joinToString().ifBlank { "Unknown" })
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
    onCheckWinePackages: (LinuxRootFsInfo) -> Unit,
    onInstallWine: (LinuxRootFsInfo) -> Unit,
    onCreateWinePrefix: (LinuxRootFsInfo) -> Unit,
    box64Installed: Boolean,
    wineInstalled: Boolean,
    winePrefixInfo: WinePrefixInfo?,
    installedWindowsPrograms: List<InstalledWindowsProgram>,
    onRunWindowsEntry: (CapsuleFileEntry) -> Unit,
    onLaunchWindowsProgram: (InstalledWindowsProgram) -> Unit,
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
                OutlinedButton(onClick = onClose) { Text(vxText("Close")) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedDrive == 'C') {
                    Button(onClick = { onSelectDrive('C') }) { Text(vxText("C: System")) }
                } else {
                    OutlinedButton(onClick = { onSelectDrive('C') }) { Text(vxText("C: System")) }
                }
                if (selectedDrive == 'D') {
                    Button(onClick = { onSelectDrive('D') }) { Text(vxText("D: Data")) }
                } else {
                    OutlinedButton(onClick = { onSelectDrive('D') }) { Text(vxText("D: Data")) }
                }
            }

            val drivePath = if (currentPath.isBlank()) {
                "${selectedDrive}:\\"
            } else {
                "${selectedDrive}:\\${currentPath.replace('/', '\\')}"
            }
            Text(drivePath, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(
                "These folders are app-private storage. Once a Wine prefix is verified, Wine uses the same folders as C: and D:.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = folderName,
                onValueChange = onFolderNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(vxText("New folder name")) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreateFolder, enabled = folderName.trim().isNotEmpty()) {
                    Text(vxText("Create folder"))
                }
                OutlinedButton(onClick = onImportFile, enabled = !isImporting) {
                    Text(if (isImporting) vxText("Importing...") else vxText("Import file"))
                }
                if (currentPath.isNotBlank()) {
                    OutlinedButton(
                        onClick = { onNavigate(currentPath.substringBeforeLast('/', "")) }
                    ) {
                        Text(vxText("Up"))
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
                    Text(vxText("Linux RootFS"), fontWeight = FontWeight.SemiBold)
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
                        Text(if (isImporting) vxText("Importing RootFS...") else vxText("Import Linux RootFS archive"))
                    }
                    if (rootFsImages.isEmpty()) {
                        Text(
                            "No Linux RootFS installed in this Capsule.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        rootFsImages.forEach { rootFs ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
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
                                }
                                if (rootFs.architecture == LinuxRootFsArchitecture.ARM64) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { onCheckWinePackages(rootFs) },
                                            enabled = !isImporting
                                        ) {
                                            Text(vxText("Check Wine packages"))
                                        }
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { onBuildBox64(rootFs) },
                                            enabled = !isImporting
                                        ) {
                                            Text(if (box64Installed) vxText("Rebuild Box64") else vxText("Build Box64 0.4.4"))
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { onInstallWine(rootFs) },
                                            enabled = !isImporting && box64Installed && !wineInstalled
                                        ) {
                                            Text(if (wineInstalled) vxText("Wine 11 installed") else vxText("Install Wine 11 WoW64"))
                                        }
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { onCreateWinePrefix(rootFs) },
                                            enabled = !isImporting && box64Installed && wineInstalled && winePrefixInfo == null
                                        ) {
                                            Text(vxText("Create Windows prefix"))
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { onTestRootFs(rootFs) },
                                            enabled = !isImporting
                                        ) {
                                            Text(vxText("Test shell"))
                                        }
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            onClick = { onRemoveRootFs(rootFs) },
                                            enabled = !isImporting
                                        ) {
                                            Text(vxText("Remove"))
                                        }
                                    }
                                } else {
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onRemoveRootFs(rootFs) },
                                        enabled = !isImporting
                                    ) {
                                        Text(vxText("Remove"))
                                    }
                                }
                            }
                        }

                    }
                }
            }

            InstalledWindowsAppsPanel(
                programs = installedWindowsPrograms,
                onLaunch = onLaunchWindowsProgram
            )

            OutlinedTextField(
                value = destinationPath,
                onValueChange = onDestinationPathChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(vxText("Copy / move destination path (blank = drive root)")) }
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
                    label = { Text(vxText("Search this drive")) }
                )
                Button(onClick = onSearch, enabled = searchQuery.trim().isNotEmpty()) {
                    Text(vxText("Search"))
                }
            }

            if (searchResults.isNotEmpty()) {
                Text(vxText("Search results"), fontWeight = FontWeight.SemiBold)
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
                                Text(vxText("Locate"))
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
                            onExtractZip = { onExtractZipEntry(entry) },
                            onRunWindowsEntry = { onRunWindowsEntry(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun InstalledWindowsAppsPanel(
    programs: List<InstalledWindowsProgram>,
    onLaunch: (InstalledWindowsProgram) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(vxText("Installed applications"), fontWeight = FontWeight.SemiBold)
            if (programs.isEmpty()) {
                Text(
                    vxText("No Windows apps installed yet"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    programs.forEach { program ->
                        val iconBitmap = remember(program.iconFile?.absolutePath) {
                            program.iconFile
                                ?.takeIf { it.isFile && it.length() in 1..(8L * 1024L * 1024L) }
                                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        }
                        Card(
                            modifier = Modifier.width(112.dp).clickable { onLaunch(program) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF202940)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                if (iconBitmap != null) {
                                    Image(
                                        bitmap = iconBitmap.asImageBitmap(),
                                        contentDescription = program.name,
                                        modifier = Modifier.size(48.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(48.dp)
                                            .background(Color(0xFF405D91), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            program.name.take(1).uppercase(Locale.ROOT),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp
                                        )
                                    }
                                }
                                Text(
                                    program.name,
                                    maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    vxText("Open"),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp
                                )
                            }
                        }
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
    onExtractZip: () -> Unit,
    onRunWindowsEntry: () -> Unit
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
                OutlinedButton(onClick = onOpen) { Text(vxText("Open")) }
            }
            OutlinedButton(onClick = onRename) { Text(vxText("Rename")) }
            OutlinedButton(onClick = onCopy) { Text(vxText("Copy")) }
            OutlinedButton(onClick = onMove) { Text(vxText("Move")) }
            if (!entry.isDirectory && entry.name.endsWith(".zip", ignoreCase = true)) {
                OutlinedButton(onClick = onExtractZip) { Text(vxText("Extract ZIP")) }
            }
            if (
                !entry.isDirectory &&
                entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("exe", "msi")
            ) {
                Button(onClick = onRunWindowsEntry) { Text(vxText("Run")) }
            }
            OutlinedButton(onClick = onDelete) { Text(vxText("Remove")) }
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
                OutlinedButton(onClick = { onOpen(capsule) }) { Text(vxText("Open")) }
                OutlinedButton(onClick = { onDelete(capsule) }) { Text(vxText("Delete")) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { onClone(capsule) }, modifier = Modifier.weight(1f)) { Text(vxText("Clone")) }
                OutlinedButton(onClick = { onRename(capsule) }, modifier = Modifier.weight(1f)) { Text(vxText("Rename")) }
                OutlinedButton(onClick = { onBackup(capsule) }, modifier = Modifier.weight(1f)) { Text(vxText("Backup")) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { onSnapshot(capsule) }, modifier = Modifier.weight(1f)) { Text(vxText("Snapshot")) }
                OutlinedButton(onClick = { onManageSnapshots(capsule) }, modifier = Modifier.weight(1f)) { Text(vxText("Manage snapshots")) }
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
                Text(vxText("Remove"))
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
