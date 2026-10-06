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
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
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

                // Text export launcher for saving .txt directly to device storage
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
                            icon = { Icon(Icons.Default.Add, contentDescription = "Add Audio") },
                            text = { Text("အသံဖိုင်ထည့်ရန်", fontWeight = FontWeight.Bold) }
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
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
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
                                text = "AI",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "အသံဖိုင်မှ စာသားပြောင်းစနစ်",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentFilter == JobFilter.ALL,
            onClick = { onSelect(JobFilter.ALL) },
            label = { Text("အားလုံး ($totalCount)") },
            shape = RoundedCornerShape(20.dp)
        )
        FilterChip(
            selected = currentFilter == JobFilter.ACTIVE,
            onClick = { onSelect(JobFilter.ACTIVE) },
            label = { Text("လုပ်ဆောင်ဆဲ ($activeCount)") },
            shape = RoundedCornerShape(20.dp)
        )
        FilterChip(
            selected = currentFilter == JobFilter.COMPLETED,
            onClick = { onSelect(JobFilter.COMPLETED) },
            label = { Text("ပြီးစီး ($completedCount)") },
            shape = RoundedCornerShape(20.dp)
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
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "အသံဖိုင် မရှိသေးပါ",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "MP3, M4A, WAV, AAC, FLAC အသံဖိုင်များကို ရွေးချယ်ပြီး အသံမှ စာသားအဖြစ် လျင်မြန်စွာ ပြောင်းလဲနိုင်ပါသည်",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onPickAudio,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("အသံဖိုင် ရွေးချယ်မည် (Add Audio)")
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconBg = when (job.state) {
                    TranscribeJob.STATE_COMPLETED -> Emerald500.copy(alpha = 0.2f)
                    TranscribeJob.STATE_PROCESSING -> Cyan500.copy(alpha = 0.2f)
                    TranscribeJob.STATE_ERROR -> Rose500.copy(alpha = 0.2f)
                    else -> Indigo500.copy(alpha = 0.2f)
                }
                val iconTint = when (job.state) {
                    TranscribeJob.STATE_COMPLETED -> Emerald500
                    TranscribeJob.STATE_PROCESSING -> Cyan500
                    TranscribeJob.STATE_ERROR -> Rose500
                    else -> Indigo500
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (job.state == TranscribeJob.STATE_COMPLETED) Icons.Default.CheckCircle else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
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
                            text = "• ${job.language}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                StatusBadge(state = job.state)
            }

            // Progress Bar & Stats
            if (job.state in setOf(TranscribeJob.STATE_PROCESSING, TranscribeJob.STATE_PREPARING, TranscribeJob.STATE_PAUSED)) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { job.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (job.state == TranscribeJob.STATE_PAUSED) Amber500 else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val progressLabel = when (job.state) {
                        TranscribeJob.STATE_PREPARING -> "အသံဖိုင် အပိုင်းခွဲခြမ်းနေပါသည်..."
                        TranscribeJob.STATE_PAUSED -> "ခေတ္တရပ်ထားပါသည် (Paused)"
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
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
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

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(6.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Secondary actions: Open in Reader & Save text
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (job.transcript.isNotBlank() || job.state == TranscribeJob.STATE_COMPLETED) {
                        // Open in Reader App
                        FilledTonalIconButton(
                            onClick = onOpenReaderApp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.ChromeReaderMode,
                                contentDescription = "Open in Reader App",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Save as .txt
                        FilledTonalIconButton(
                            onClick = onSaveTextFile,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.SaveAlt,
                                contentDescription = "Save .txt",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Notion Sync (if enabled)
                        if (notionEnabled) {
                            FilledTonalIconButton(
                                onClick = onSyncNotion,
                                modifier = Modifier.size(36.dp)
                            ) {
                                when (job.notionSyncState) {
                                    TranscribeJob.NOTION_SYNCED -> Icon(Icons.Default.CheckCircle, contentDescription = "Synced", tint = Emerald500, modifier = Modifier.size(18.dp))
                                    TranscribeJob.NOTION_SYNCING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else -> Icon(Icons.Default.CloudUpload, contentDescription = "Sync to Notion", tint = Indigo500, modifier = Modifier.size(18.dp))
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
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ဖတ်ရန်", fontSize = 13.sp)
                        }
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
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
        TranscribeJob.STATE_PROCESSING -> Triple("ပြောင်းနေဆဲ", Cyan500.copy(alpha = 0.15f), Cyan500)
        TranscribeJob.STATE_PREPARING -> Triple("ပြင်ဆင်ဆဲ", Indigo500.copy(alpha = 0.15f), Indigo500)
        TranscribeJob.STATE_PAUSED -> Triple("ရပ်ထားသည်", Amber500.copy(alpha = 0.15f), Amber500)
        TranscribeJob.STATE_ERROR -> Triple("အမှား", Rose500.copy(alpha = 0.15f), Rose500)
        else -> Triple("တန်းစီဆဲ", Slate600.copy(alpha = 0.15f), Slate300)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
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
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp)
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
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "စာလုံးရေ ${job.transcript.length} လုံး • စကားလုံး ${job.transcript.split(Regex("\\s+")).filter { it.isNotBlank() }.size} လုံး",
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
                        Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = Indigo500, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Notion Link ဖြင့် ကြည့်ရန်",
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
                    .padding(vertical = 12.dp)
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
            Spacer(modifier = Modifier.height(10.dp))

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

            Spacer(modifier = Modifier.height(8.dp))

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
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
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
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("မျှဝေရန်", fontSize = 12.sp)
                }

                if (notionEnabled) {
                    OutlinedButton(
                        onClick = { onSyncNotion(job.id) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
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
    var showAdvancedUpdate by remember { mutableStateOf(false) }

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
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
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
                        fontSize = 19.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Section 1: Gemini AI
                    SettingsSection(title = "၁။ Gemini AI ချိန်ညှိချက်") {
                        OutlinedTextField(
                            value = keysInput,
                            onValueChange = { keysInput = it },
                            label = { Text("Gemini API Keys") },
                            placeholder = { Text("Key တစ်ခုထက်မက ထည့်လိုပါက ကော်မာ (,) ခြားပါ") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("ဘာသာစကား (Language):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedLang == "my-MM",
                                onClick = { selectedLang = "my-MM" },
                                label = { Text("မြန်မာ (Myanmar)") }
                            )
                            FilterChip(
                                selected = selectedLang == "en-US",
                                onClick = { selectedLang = "en-US" },
                                label = { Text("English") }
                            )
                        }
                    }

                    // Section 2: Notion Integration (Toggle ON/OFF)
                    SettingsSection(title = "၂။ Notion ချိတ်ဆက်မှု (Notion Sync)") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Notion ချိတ်ဆက်မှု အသုံးပြုမည်",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (notionEnabled) "ဖွင့်ထားပါသည် (Enabled)" else "ပိတ်ထားပါသည် (Disabled)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (notionEnabled) Emerald500 else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = notionEnabled,
                                onCheckedChange = { notionEnabled = it }
                            )
                        }

                        AnimatedVisibility(visible = notionEnabled) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "အလိုအလျောက် Notion သို့ ပို့မည်",
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "စာသားပြောင်းပြီးတိုင်း Notion သို့ တန်းပို့ပါမည်",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
                                    placeholder = { Text("32-character database id") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // Section 3: In-App Updates (Requirement 5)
                    SettingsSection(title = "၃။ App ဗားရှင်း နှင့် အပ်ဒိတ် (App Updates)") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "လက်ရှိ ဗားရှင်း (Current Version)",
                                    fontSize = 13.sp,
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
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("စစ်ဆေးမည် (Check)")
                            }
                        }

                        // Update Status Box
                        Spacer(modifier = Modifier.height(10.dp))
                        when (val state = updateUiState) {
                            is UpdateUiState.Checking -> {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("ဗားရှင်းအသစ် ရှိမရှိ စစ်ဆေးနေပါသည်...", fontSize = 13.sp)
                                    }
                                }
                            }
                            is UpdateUiState.AlreadyUpToDate -> {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Emerald500.copy(alpha = 0.12f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald500, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("သင့် App သည် အသစ်ဆုံး ဗားရှင်း ဖြစ်ပါသည်", color = Emerald500, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                            }
                            is UpdateUiState.UpdateAvailable -> {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "ဗားရှင်းအသစ် ရရှိနိုင်ပါသည်: v${state.release.version}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
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

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = state.release.releaseNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))
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
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { state.progressPercent },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                }
                            }
                            is UpdateUiState.ReadyToInstall -> {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Emerald500.copy(alpha = 0.15f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("ဒေါင်းလုဒ် အောင်မြင်စွာ ပြီးစီးပါပြီ!", fontWeight = FontWeight.Bold, color = Emerald500)
                                        Spacer(modifier = Modifier.height(8.dp))
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
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "အမှား: ${state.message}",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                            else -> {}
                        }

                        // Advanced update URL link
                        Text(
                            text = if (showAdvancedUpdate) "▲ Update လိပ်စာ ချိန်ညှိမှု ဝှက်မည်" else "▼ Update လိပ်စာ စိတ်ကြိုက် သတ်မှတ်မည်",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { showAdvancedUpdate = !showAdvancedUpdate }
                                .padding(vertical = 4.dp)
                        )

                        AnimatedVisibility(visible = showAdvancedUpdate) {
                            OutlinedTextField(
                                value = updateUrlInput,
                                onValueChange = { updateUrlInput = it },
                                label = { Text("Custom Release JSON / API URL") },
                                placeholder = { Text("https://api.github.com/repos/.../releases/latest") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // Footer Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("ပိတ်မည် (Cancel)")
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
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("သိမ်းဆည်းမည် (Save)")
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
