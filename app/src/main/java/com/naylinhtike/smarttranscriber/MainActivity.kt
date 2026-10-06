package com.naylinhtike.smarttranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: TranscribeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SmartTranscriberTheme {
                val context = LocalContext.current
                val snackbarHostState = remember { SnackbarHostState() }
                var showSettingsDialog by remember { mutableStateOf(false) }

                // Text export launcher for saving .txt directly to user selected storage folder
                var textToExport by remember { mutableStateOf<String?>(null) }
                val exportTextLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/plain")
                ) { uri: Uri? ->
                    uri?.let { destUri ->
                        textToExport?.let { content ->
                            viewModel.exportTranscriptToUri(destUri, content)
                        }
                    }
                    textToExport = null
                }

                LaunchedEffect(Unit) {
                    viewModel.snackBarMessages.collectLatest { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                val audioPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let { viewModel.importAudioUri(it) }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TranscriberTopBar(
                            onOpenSettings = { showSettingsDialog = true }
                        )
                    },
                    floatingActionButton = {
                        ExtendedFloatingActionButton(
                            onClick = { audioPicker.launch("audio/*") },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(16.dp),
                            icon = { Icon(Icons.Default.Add, contentDescription = "Add Audio") },
                            text = { Text("အသံဖိုင် / တရားတော် ထည့်ရန်", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        TranscriberMainContent(
                            viewModel = viewModel,
                            onPickAudio = { audioPicker.launch("audio/*") },
                            onOpenReaderApp = { job ->
                                TranscriptFileManager.openInExternalReader(
                                    context = context,
                                    fileName = job.fileName,
                                    transcriptText = job.transcript
                                )
                            },
                            onSaveTextFile = { job ->
                                textToExport = job.transcript
                                val defaultName = job.fileName.substringBeforeLast('.').ifBlank { "transcript" } + ".txt"
                                exportTextLauncher.launch(defaultName)
                            }
                        )

                        val selectedJob by viewModel.selectedJob.collectAsState()
                        if (selectedJob != null) {
                            TranscriptReaderSheet(
                                job = selectedJob!!,
                                notionEnabled = viewModel.notionEnabled.collectAsState().value,
                                onDismiss = { viewModel.selectJob(null) },
                                onSyncNotion = { viewModel.syncJobToNotion(it) },
                                onOpenReaderApp = {
                                    TranscriptFileManager.openInExternalReader(
                                        context = context,
                                        fileName = selectedJob!!.fileName,
                                        transcriptText = selectedJob!!.transcript
                                    )
                                },
                                onSaveTextFile = {
                                    textToExport = selectedJob!!.transcript
                                    val defaultName = selectedJob!!.fileName.substringBeforeLast('.').ifBlank { "transcript" } + ".txt"
                                    exportTextLauncher.launch(defaultName)
                                }
                            )
                        }

                        if (showSettingsDialog) {
                            SettingsDialog(
                                viewModel = viewModel,
                                onDismiss = { showSettingsDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriberTopBar(
    onOpenSettings: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SmartTranscriber",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "PRO AI",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "တရားတော်နှင့် အသံဖိုင်များ စာသားပြောင်းစနစ်",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun TranscriberMainContent(
    viewModel: TranscribeViewModel,
    onPickAudio: () -> Unit,
    onOpenReaderApp: (TranscribeJob) -> Unit,
    onSaveTextFile: (TranscribeJob) -> Unit
) {
    val allJobs by viewModel.allJobs.collectAsState()
    val jobs by viewModel.filteredJobs.collectAsState()
    val filter by viewModel.currentFilter.collectAsState()
    val notionEnabled by viewModel.notionEnabled.collectAsState()

    val activeCount = remember(allJobs) { allJobs.count { it.isActive || it.state == TranscribeJob.STATE_PAUSED } }
    val completedCount = remember(allJobs) { allJobs.count { it.state == TranscribeJob.STATE_COMPLETED } }

    Column(modifier = Modifier.fillMaxSize()) {
        FilterTabRow(
            currentFilter = filter,
            totalCount = allJobs.size,
            activeCount = activeCount,
            completedCount = completedCount,
            onSelect = { viewModel.setFilter(it) }
        )

        if (jobs.isEmpty()) {
            EmptyJobsView(onPickAudio = onPickAudio)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 86.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(jobs, key = { it.id }) { job ->
                    TranscribeJobCard(
                        job = job,
                        notionEnabled = notionEnabled,
                        onPause = { viewModel.pauseJob(job.id) },
                        onResume = { viewModel.resumeJob(job.id) },
                        onDelete = { viewModel.deleteJob(job.id) },
                        onOpenTranscript = { viewModel.selectJob(job) },
                        onSyncNotion = { viewModel.syncJobToNotion(job.id) },
                        onOpenReaderApp = { onOpenReaderApp(job) },
                        onSaveTextFile = { onSaveTextFile(job) }
                    )
                }
            }
        }
    }
}

@Composable
fun FilterTabRow(
    currentFilter: JobFilter,
    totalCount: Int,
    activeCount: Int,
    completedCount: Int,
    onSelect: (JobFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentFilter == JobFilter.ALL,
            onClick = { onSelect(JobFilter.ALL) },
            label = { Text("အားလုံး ($totalCount)", fontSize = 13.sp) },
            shape = RoundedCornerShape(12.dp)
        )
        FilterChip(
            selected = currentFilter == JobFilter.ACTIVE,
            onClick = { onSelect(JobFilter.ACTIVE) },
            label = { Text("လုပ်ဆောင်ဆဲ ($activeCount)", fontSize = 13.sp) },
            shape = RoundedCornerShape(12.dp)
        )
        FilterChip(
            selected = currentFilter == JobFilter.COMPLETED,
            onClick = { onSelect(JobFilter.COMPLETED) },
            label = { Text("ပြီးစီး ($completedCount)", fontSize = 13.sp) },
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
fun EmptyJobsView(onPickAudio: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "အသံဖိုင် မရှိသေးပါ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "တရားတော်များ၊ အသံသွင်းဖိုင်များ (MP3, M4A, WAV, AAC) ကို ထည့်သွင်းပြီး အချိန်အကန့်အသတ်မရှိ စာသားပြောင်းနိုင်ပါသည်",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onPickAudio,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("အသံဖိုင် ရွေးချယ်မည်", fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun TranscribeJobCard(
    job: TranscribeJob,
    notionEnabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpenTranscript: () -> Unit,
    onSyncNotion: () -> Unit,
    onOpenReaderApp: () -> Unit,
    onSaveTextFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (job.transcript.isNotBlank() || job.state == TranscribeJob.STATE_COMPLETED) {
                    onOpenTranscript()
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconBg = when (job.state) {
                    TranscribeJob.STATE_COMPLETED -> Emerald500.copy(alpha = 0.15f)
                    TranscribeJob.STATE_PROCESSING -> Cyan500.copy(alpha = 0.15f)
                    TranscribeJob.STATE_ERROR -> Rose500.copy(alpha = 0.15f)
                    else -> Indigo500.copy(alpha = 0.15f)
                }
                val iconTint = when (job.state) {
                    TranscribeJob.STATE_COMPLETED -> Emerald500
                    TranscribeJob.STATE_PROCESSING -> Cyan500
                    TranscribeJob.STATE_ERROR -> Rose500
                    else -> Indigo500
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (job.state == TranscribeJob.STATE_COMPLETED) Icons.Default.CheckCircle else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${job.formattedSize()} • ${job.formattedDuration()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• မြန်မာ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                StatusBadge(state = job.state)
            }

            // Progress Bar & Stats
            if (job.state in setOf(TranscribeJob.STATE_PROCESSING, TranscribeJob.STATE_PREPARING, TranscribeJob.STATE_PAUSED)) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { job.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (job.state == TranscribeJob.STATE_PAUSED) Amber500 else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(5.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val progressLabel = when (job.state) {
                        TranscribeJob.STATE_PREPARING -> "အသံဖိုင် ခွဲခြမ်းနေပါသည်..."
                        TranscribeJob.STATE_PAUSED -> "ခေတ္တရပ်ထားသည် (Paused)"
                        else -> "အပိုင်း ${job.completedChunks}/${job.totalChunks} (${(job.progressPercent * 100).toInt()}%)"
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(job.progressPercent * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Error display
            if (job.state == TranscribeJob.STATE_ERROR && job.error.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "အမှား: ${job.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(4.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Secondary actions: Open in Reader, Save text, Notion
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (job.transcript.isNotBlank() || job.state == TranscribeJob.STATE_COMPLETED) {
                        // Open in Reader App
                        FilledTonalIconButton(
                            onClick = onOpenReaderApp,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.ChromeReaderMode,
                                contentDescription = "Open in Reader App",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Save as .txt
                        FilledTonalIconButton(
                            onClick = onSaveTextFile,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.SaveAlt,
                                contentDescription = "Save .txt",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Notion Sync (if enabled)
                        if (notionEnabled) {
                            FilledTonalIconButton(
                                onClick = onSyncNotion,
                                modifier = Modifier.size(34.dp)
                            ) {
                                when (job.notionSyncState) {
                                    TranscribeJob.NOTION_SYNCED -> Icon(Icons.Default.CheckCircle, contentDescription = "Synced", tint = Emerald500, modifier = Modifier.size(17.dp))
                                    TranscribeJob.NOTION_SYNCING -> CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                    else -> Icon(Icons.Default.CloudUpload, contentDescription = "Sync to Notion", tint = Indigo500, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }

                // Primary actions: Read / Pause / Resume / Delete
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (job.isActive) {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Amber500)
                        }
                    } else if (job.state == TranscribeJob.STATE_PAUSED || job.state == TranscribeJob.STATE_ERROR) {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Emerald500)
                        }
                    }

                    if (job.transcript.isNotBlank() || job.state == TranscribeJob.STATE_COMPLETED) {
                        Button(
                            onClick = onOpenTranscript,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ဖတ်မည်", fontSize = 13.sp)
                        }
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(state: String) {
    val (label, bg, fg) = when (state) {
        TranscribeJob.STATE_COMPLETED -> Triple("ပြီးစီး", Emerald500.copy(alpha = 0.15f), Emerald500)
        TranscribeJob.STATE_PROCESSING -> Triple("ပြောင်းဆဲ", Cyan500.copy(alpha = 0.15f), Cyan500)
        TranscribeJob.STATE_PREPARING -> Triple("ပြင်ဆင်ဆဲ", Indigo500.copy(alpha = 0.15f), Indigo500)
        TranscribeJob.STATE_PAUSED -> Triple("ရပ်ထားသည်", Amber500.copy(alpha = 0.15f), Amber500)
        TranscribeJob.STATE_ERROR -> Triple("အမှား", Rose500.copy(alpha = 0.15f), Rose500)
        else -> Triple("တန်းစီဆဲ", Slate600.copy(alpha = 0.15f), Slate300)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptReaderSheet(
    job: TranscribeJob,
    notionEnabled: Boolean,
    onDismiss: () -> Unit,
    onSyncNotion: (String) -> Unit,
    onOpenReaderApp: () -> Unit,
    onSaveTextFile: () -> Unit
) {
    val context = LocalContext.current
    var fontSizeSp by remember { mutableStateOf(16) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "စာလုံးရေ ${job.transcript.length} • စကားလုံး ${job.transcript.split(Regex("\\s+")).filter { it.isNotBlank() }.size} လုံး",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Font size controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 4.dp)
                ) {
                    IconButton(
                        onClick = { if (fontSizeSp > 12) fontSizeSp -= 2 },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("A-", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "${fontSizeSp}sp",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { if (fontSizeSp < 26) fontSizeSp += 2 },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("A+", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Notion Page link if synced
            if (notionEnabled && job.notionPageUrl.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Indigo500.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(job.notionPageUrl))
                            context.startActivity(intent)
                        }
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = Indigo500, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Notion Page ဖွင့်ရန်",
                            style = MaterialTheme.typography.bodySmall,
                            color = Indigo500,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider()

            // Scrollable Text View with selectable text
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = job.transcript.ifBlank { "[စာသား မရှိသေးပါ]" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp + 8).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Primary Action Row: Open with Phone Reader & Save .txt
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenReaderApp,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ChromeReaderMode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("စာဖတ် App ဖြင့် ဖွင့်ပါ", fontSize = 13.sp)
                }

                Button(
                    onClick = onSaveTextFile,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("စာသားဖိုင် သိမ်းရန်", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Secondary Action Row: Copy, Share, Notion
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", job.transcript))
                        Toast.makeText(context, "စာသား ကူးယူပြီးပါပြီ (Copied)", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ကူးယူရန်", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, job.fileName)
                            putExtra(Intent.EXTRA_TEXT, job.transcript)
                        }
                        context.startActivity(Intent.createChooser(intent, "စာသား မျှဝေရန်"))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("မျှဝေရန်", fontSize = 12.sp)
                }

                if (notionEnabled) {
                    OutlinedButton(
                        onClick = { onSyncNotion(job.id) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (job.notionSyncState == TranscribeJob.NOTION_SYNCED) "Notion (ပြီး)" else "Notion ပို့", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    viewModel: TranscribeViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val rawGeminiKeys by viewModel.rawGeminiKeys.collectAsState()
    val notionKey by viewModel.notionApiKey.collectAsState()
    val notionDbId by viewModel.notionDatabaseId.collectAsState()
    val notionEnabledState by viewModel.notionEnabled.collectAsState()
    val notionAutoSyncState by viewModel.notionAutoSync.collectAsState()
    val defaultLang by viewModel.defaultLanguage.collectAsState()
    val customUpdateUrl by viewModel.customUpdateUrl.collectAsState()
    val updateUiState by viewModel.updateState.collectAsState()

    var keysInput by remember(rawGeminiKeys) { mutableStateOf(rawGeminiKeys) }
    var notionKeyInput by remember(notionKey) { mutableStateOf(notionKey) }
    var notionDbInput by remember(notionDbId) { mutableStateOf(notionDbId) }
    var notionEnabled by remember(notionEnabledState) { mutableStateOf(notionEnabledState) }
    var notionAutoSync by remember(notionAutoSyncState) { mutableStateOf(notionAutoSyncState) }
    var selectedLang by remember(defaultLang) { mutableStateOf(defaultLang) }
    var updateUrlInput by remember(customUpdateUrl) { mutableStateOf(customUpdateUrl) }

    val currentAppVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ချိန်ညှိချက်များ (Settings)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Section 1: Gemini AI
                    SettingsSection(title = "၁။ Gemini AI Key (တရားတော်များ သီးသန့်)") {
                        OutlinedTextField(
                            value = keysInput,
                            onValueChange = { keysInput = it },
                            label = { Text("Gemini API Keys") },
                            placeholder = { Text("AI Studio Key ၁ ခု (သို့) ၂ ခု ကော်မာ (,) ခြားထည့်ပါ") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "💡 အကြံပြုချက်: အချိန် ၂-၃ နာရီကြာ တရားတော်များကို အကန့်အသတ် (Limit) မရှိ လျင်မြန်စွာ ပြောင်းနိုင်ရန် Google AI Studio မှ အခမဲ့ API Key တစ်ခုထက်မက ကော်မာ (,) ခြား၍ ထည့်နိုင်ပါသည်",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }

                    // Section 2: Notion Toggle
                    SettingsSection(title = "၂။ Notion ချိတ်ဆက်မှု") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Notion သို့ ပို့မည်",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Switch(
                                checked = notionEnabled,
                                onCheckedChange = { notionEnabled = it }
                            )
                        }

                        AnimatedVisibility(visible = notionEnabled) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "အသံဖိုင်ပြီးတိုင်း အလိုအလျောက် ပို့မည်",
                                        fontSize = 13.sp
                                    )
                                    Switch(
                                        checked = notionAutoSync,
                                        onCheckedChange = { notionAutoSync = it }
                                    )
                                }

                                OutlinedTextField(
                                    value = notionKeyInput,
                                    onValueChange = { notionKeyInput = it },
                                    label = { Text("Notion API Key") },
                                    placeholder = { Text("secret_...") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = notionDbInput,
                                    onValueChange = { notionDbInput = it },
                                    label = { Text("Notion Database ID") },
                                    placeholder = { Text("32-character ID") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // Section 3: In-App Updates
                    SettingsSection(title = "၃။ App Update (တိုက်ရိုက် အဆင့်မြှင့်တင်ခြင်း)") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "လက်ရှိ ဗားရှင်း",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "v$currentAppVersion",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Button(
                                onClick = {
                                    viewModel.checkAppUpdate(currentAppVersion, updateUrlInput)
                                },
                                enabled = updateUiState !is UpdateUiState.Checking && updateUiState !is UpdateUiState.Downloading,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("စစ်ဆေးမည်", fontSize = 13.sp)
                            }
                        }

                        // Update Status Box
                        Spacer(modifier = Modifier.height(8.dp))
                        when (val state = updateUiState) {
                            is UpdateUiState.Checking -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("ဗားရှင်းအသစ် ရှိမရှိ စစ်ဆေးနေပါသည်...", fontSize = 12.5.sp)
                                    }
                                }
                            }
                            is UpdateUiState.AlreadyUpToDate -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Emerald500.copy(alpha = 0.12f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald500, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("သင့် App သည် အသစ်ဆုံး ဗားရှင်း ဖြစ်ပါသည်", color = Emerald500, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    }
                                }
                            }
                            is UpdateUiState.UpdateAvailable -> {
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "ဗားရှင်းအသစ် ရရှိနိုင်ပါသည်: v${state.release.version}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (state.release.formattedSize().isNotBlank()) {
                                                Text(
                                                    text = state.release.formattedSize(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = state.release.releaseNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = { viewModel.downloadAndInstallUpdate(state.release) },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("တိုက်ရိုက် ဒေါင်းလုဒ်ဆွဲပြီး Install လုပ်မည်")
                                        }
                                    }
                                }
                            }
                            is UpdateUiState.Downloading -> {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "ဒေါင်းလုဒ်ဆွဲနေသည်... ${(state.progressPercent * 100).toInt()}%",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { state.progressPercent },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                            is UpdateUiState.ReadyToInstall -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Emerald500.copy(alpha = 0.15f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("ဒေါင်းလုဒ် အောင်မြင်စွာ ပြီးပါပြီ!", fontWeight = FontWeight.Bold, color = Emerald500, fontSize = 13.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = { viewModel.installDownloadedApk(state.apkFile) },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("ယခုပဲ App ထည့်သွင်းမည် (Install Now)")
                                        }
                                    }
                                }
                            }
                            is UpdateUiState.Error -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "အမှား: ${state.message}",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Footer Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("ပိတ်မည်")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.saveSettings(
                                geminiKeys = keysInput,
                                notionKey = notionKeyInput,
                                notionDbId = notionDbInput,
                                notionEnabled = notionEnabled,
                                notionAutoSync = notionAutoSync,
                                language = selectedLang,
                                customUpdateUrl = updateUrlInput
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("သိမ်းဆည်းမည်")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
