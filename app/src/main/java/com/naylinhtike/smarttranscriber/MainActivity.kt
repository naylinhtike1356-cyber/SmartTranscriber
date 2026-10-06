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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: TranscribeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SmartTranscriberTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                var showSettingsDialog by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    viewModel.snackBarMessages.collectLatest { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TranscriberTopBar(
                            onOpenSettings = { showSettingsDialog = true }
                        )
                    },
                    floatingActionButton = {
                        val audioPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetContent()
                        ) { uri: Uri? ->
                            uri?.let { viewModel.importAudioUri(it) }
                        }
                        FloatingActionButton(
                            onClick = { audioPicker.launch("audio/*") },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Audio")
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        TranscriberMainContent(viewModel = viewModel)

                        val selectedJob by viewModel.selectedJob.collectAsState()
                        if (selectedJob != null) {
                            TranscriptReaderSheet(
                                job = selectedJob!!,
                                onDismiss = { viewModel.selectJob(null) },
                                onSyncNotion = { viewModel.syncJobToNotion(it) }
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
fun TranscriberTopBar(onOpenSettings: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "SmartTranscriber",
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                )
                Text(
                    text = "အသံဖိုင်မှ စာသားပြောင်းစနစ်",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
fun TranscriberMainContent(viewModel: TranscribeViewModel) {
    val jobs by viewModel.filteredJobs.collectAsState()
    val filter by viewModel.currentFilter.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter tabs
        FilterTabRow(currentFilter = filter, onSelect = { viewModel.setFilter(it) })

        if (jobs.isEmpty()) {
            EmptyJobsView()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(jobs, key = { it.id }) { job ->
                    TranscribeJobCard(
                        job = job,
                        onPause = { viewModel.pauseJob(job.id) },
                        onResume = { viewModel.resumeJob(job.id) },
                        onDelete = { viewModel.deleteJob(job.id) },
                        onOpenTranscript = { viewModel.selectJob(job) },
                        onSyncNotion = { viewModel.syncJobToNotion(job.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun FilterTabRow(currentFilter: JobFilter, onSelect: (JobFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentFilter == JobFilter.ALL,
            onClick = { onSelect(JobFilter.ALL) },
            label = { Text("အားလုံး (All)") }
        )
        FilterChip(
            selected = currentFilter == JobFilter.ACTIVE,
            onClick = { onSelect(JobFilter.ACTIVE) },
            label = { Text("လုပ်ဆောင်ဆဲ (Active)") }
        )
        FilterChip(
            selected = currentFilter == JobFilter.COMPLETED,
            onClick = { onSelect(JobFilter.COMPLETED) },
            label = { Text("ပြီးစီး (Done)") }
        )
    }
}

@Composable
fun EmptyJobsView() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Audiotrack,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "အသံဖိုင် မရှိသေးပါ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "အောက်ခြေရှိ + ခလုတ်ကို နှိပ်ပြီး MP3, M4A, WAV စသည့် အသံဖိုင်များ ထည့်သွင်းနိုင်ပါသည်",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun TranscribeJobCard(
    job: TranscribeJob,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpenTranscript: () -> Unit,
    onSyncNotion: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (job.transcript.isNotBlank() || job.state == TranscribeJob.STATE_COMPLETED) {
                    onOpenTranscript()
                }
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Icon, Title & Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                    Text(
                        text = "${job.formattedSize()} • ${job.formattedDuration()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusBadge(state = job.state)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress Bar & Stats (Download manager style)
            if (job.state in setOf(TranscribeJob.STATE_PROCESSING, TranscribeJob.STATE_PREPARING, TranscribeJob.STATE_PAUSED)) {
                LinearProgressIndicator(
                    progress = { job.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (job.state == TranscribeJob.STATE_PAUSED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val progressLabel = when (job.state) {
                        TranscribeJob.STATE_PREPARING -> "အသံဖိုင် ခွဲခြမ်းနေသည်..."
                        TranscribeJob.STATE_PAUSED -> "ခေတ္တရပ်နားထားပါသည်"
                        else -> "အပိုင်း ${job.completedChunks}/${job.totalChunks} (${(job.progressPercent * 100).toInt()}%)"
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = job.language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Error display
            if (job.state == TranscribeJob.STATE_ERROR && job.error.isNotBlank()) {
                Text(
                    text = "အမှား: ${job.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pause / Resume
                if (job.isActive) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Amber500)
                    }
                } else if (job.state == TranscribeJob.STATE_PAUSED || job.state == TranscribeJob.STATE_ERROR) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Emerald500)
                    }
                }

                // Transcript view
                if (job.transcript.isNotBlank() || job.state == TranscribeJob.STATE_COMPLETED) {
                    IconButton(onClick = onOpenTranscript) {
                        Icon(Icons.Default.Description, contentDescription = "View Transcript", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Notion Sync
                    IconButton(onClick = onSyncNotion) {
                        when (job.notionSyncState) {
                            TranscribeJob.NOTION_SYNCED -> Icon(Icons.Default.CheckCircle, contentDescription = "Synced", tint = Emerald500)
                            TranscribeJob.NOTION_SYNCING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else -> Icon(Icons.Default.CloudUpload, contentDescription = "Sync to Notion", tint = Indigo500)
                        }
                    }
                }

                // Delete
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                }
            }
        }
    }
}

@Composable
fun StatusBadge(state: String) {
    val (label, bg, fg) = when (state) {
        TranscribeJob.STATE_COMPLETED -> Triple("ပြီးစီး", Emerald500.copy(alpha = 0.15f), Emerald500)
        TranscribeJob.STATE_PROCESSING -> Triple("လုပ်ဆောင်ဆဲ", Cyan500.copy(alpha = 0.15f), Cyan500)
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
    onDismiss: () -> Unit,
    onSyncNotion: (String) -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp)
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
                    Text(
                        text = "${job.transcript.length} လုံး • ${job.transcript.split(Regex("\\s+")).size} စကားလုံး",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Notion button
                Button(
                    onClick = { onSyncNotion(job.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo500)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (job.notionSyncState == TranscribeJob.NOTION_SYNCED) "Notion (ပြီး)" else "Notion သို့ ပို့")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Notion Page link if synced
            if (job.notionPageUrl.isNotBlank()) {
                Text(
                    text = "Notion Link: ${job.notionPageUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(job.notionPageUrl))
                            context.startActivity(intent)
                        }
                        .padding(bottom = 8.dp)
                )
            }

            HorizontalDivider()

            // Scrollable Text View
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = job.transcript.ifBlank { "[စာသား မရှိသေးပါ]" },
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 26.sp
                )
            }

            HorizontalDivider()

            // Footer actions: Copy & Share
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", job.transcript))
                        Toast.makeText(context, "စာသား ကူးယူပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ကူးယူရန် (Copy)")
                }

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, job.fileName)
                            putExtra(Intent.EXTRA_TEXT, job.transcript)
                        }
                        context.startActivity(Intent.createChooser(intent, "စာသား မျှဝေရန်"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("မျှဝေရန် (Share)")
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
    val rawGeminiKeys by viewModel.rawGeminiKeys.collectAsState()
    val notionKey by viewModel.notionApiKey.collectAsState()
    val notionDbId by viewModel.notionDatabaseId.collectAsState()
    val defaultLang by viewModel.defaultLanguage.collectAsState()

    var keysInput by remember(rawGeminiKeys) { mutableStateOf(rawGeminiKeys) }
    var notionKeyInput by remember(notionKey) { mutableStateOf(notionKey) }
    var notionDbInput by remember(notionDbId) { mutableStateOf(notionDbId) }
    var selectedLang by remember(defaultLang) { mutableStateOf(defaultLang) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ချိန်ညှိချက်များ (Settings)", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = keysInput,
                    onValueChange = { keysInput = it },
                    label = { Text("Gemini API Keys") },
                    placeholder = { Text("Key တစ်ခုထက်မက ထည့်လိုပါက ကော်မာ (,) ခြားပါ") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

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

                Text("ဘာသာစကား (Language):", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        },
        confirmButton = {
            Button(onClick = {
                viewModel.saveSettings(keysInput, notionKeyInput, notionDbInput, selectedLang)
                onDismiss()
            }) {
                Text("သိမ်းဆည်းမည် (Save)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ပိတ်မည် (Cancel)")
            }
        }
    )
}
