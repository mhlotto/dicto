package com.mhlotto.dicto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mhlotto.dicto.data.NoteEntity
import com.mhlotto.dicto.data.ProjectEntity
import com.mhlotto.dicto.speech.DictationEngineChoice
import com.mhlotto.dicto.speech.DictationEngineSettingsState
import com.mhlotto.dicto.speech.DictationState
import com.mhlotto.dicto.ui.AppScreen
import com.mhlotto.dicto.ui.AppUiState
import com.mhlotto.dicto.ui.AppViewModel
import com.mhlotto.dicto.ui.AppViewModelFactory
import com.mhlotto.dicto.ui.draftJson
import com.mhlotto.dicto.ui.draftMarkdown
import com.mhlotto.dicto.ui.noteJson
import com.mhlotto.dicto.ui.noteMarkdown

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModelFactory(application as DictoApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DictoTheme {
                DictoApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun DictoApp(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsState()
    val dictationState by viewModel.dictationState.collectAsState()
    val engineSettings by viewModel.engineSettingsState.collectAsState()
    val context = LocalContext.current
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.startDictation()
        }
    }
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val name = displayNameForUri(context, uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                viewModel.importWhisperModel(name, bytes)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1D1811)) {
        when (state.screen) {
            AppScreen.Dictate -> DictationScreen(
                state = state,
                dictationState = dictationState,
                engineSettings = engineSettings,
                hasAudioPermission = hasAudioPermission,
                onStartStop = {
                    if (dictationState.isRecording) {
                        viewModel.stopDictation()
                    } else if (hasAudioPermission) {
                        viewModel.startDictation()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onProjectSelected = viewModel::selectProject,
                onNewProjectName = viewModel::updateNewProjectName,
                onCreateProject = viewModel::createProject,
                onTitleChanged = viewModel::updateDraftTitle,
                onSaveDraft = viewModel::saveDraft,
                onSaveAndEdit = viewModel::saveDraftAndEdit,
                onClearDraft = viewModel::clearDraft,
                onEditNote = viewModel::openEdit,
                onEngineChoiceSelected = viewModel::setEngineChoice,
                onImportWhisperModel = {
                    modelPickerLauncher.launch(
                        arrayOf(
                            "application/octet-stream",
                            "application/x-binary",
                            "*/*",
                        ),
                    )
                },
                onShare = { title, mime, text -> shareText(context, title, mime, text) },
            )

            AppScreen.Edit -> EditScreen(
                state = state,
                onBack = viewModel::closeEdit,
                onTitleChanged = viewModel::updateEditTitle,
                onBodyChanged = viewModel::updateEditBody,
                onProjectSelected = viewModel::updateEditProject,
                onSave = viewModel::saveEdit,
                onShare = { title, mime, text -> shareText(context, title, mime, text) },
            )
        }
    }
}

@Composable
private fun DictationScreen(
    state: AppUiState,
    dictationState: DictationState,
    engineSettings: DictationEngineSettingsState,
    hasAudioPermission: Boolean,
    onStartStop: () -> Unit,
    onProjectSelected: (Long) -> Unit,
    onNewProjectName: (String) -> Unit,
    onCreateProject: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onSaveAndEdit: () -> Unit,
    onClearDraft: () -> Unit,
    onEditNote: (Long) -> Unit,
    onEngineChoiceSelected: (DictationEngineChoice) -> Unit,
    onImportWhisperModel: () -> Unit,
    onShare: (String, String, String) -> Unit,
) {
    val selectedProject = state.projects.firstOrNull { it.id == state.selectedProjectId }
    val projectName = selectedProject?.name.orEmpty()
    val scroll = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2D2417), Color(0xFF1D1811), Color(0xFF14110D)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            ProjectBar(
                projects = state.projects,
                selectedProjectId = state.selectedProjectId,
                newProjectName = state.newProjectName,
                onProjectSelected = onProjectSelected,
                onNewProjectName = onNewProjectName,
                onCreateProject = onCreateProject,
            )
            OutlinedTextField(
                value = state.draftTitle,
                onValueChange = onTitleChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note title for $projectName") },
                singleLine = true,
            )
            Button(
                onClick = onStartStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (dictationState.isRecording) Color(0xFFB94D3A) else Color(0xFFF4C95D),
                    contentColor = Color(0xFF1D1811),
                ),
            ) {
                Text(
                    text = if (dictationState.isRecording) "Stop dictation" else "Start dictation",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                )
            }
            StatusPanel(
                hasAudioPermission = hasAudioPermission,
                speechMode = state.speechModeLabel,
                status = state.statusMessage,
            )
            DictationEngineDebugPanel(
                engineSettings = engineSettings,
                onEngineChoiceSelected = onEngineChoiceSelected,
                onImportWhisperModel = onImportWhisperModel,
            )
            TranscriptCard(title = "Rolling draft", body = state.rollingDraft, accent = Color(0xFFF4C95D))
            TranscriptCard(title = "Live partial", body = state.livePartial, accent = Color(0xFF8BC6A8))
            TranscriptCard(title = "Committed transcript", body = state.committedTranscript, accent = Color(0xFFD9A86C))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSaveDraft, enabled = state.rollingDraft.isNotBlank()) {
                    Text("Save to $projectName")
                }
                Button(onClick = onSaveAndEdit, enabled = state.rollingDraft.isNotBlank()) {
                    Text("Save & edit")
                }
                TextButton(onClick = onClearDraft, enabled = state.rollingDraft.isNotBlank()) {
                    Text("Clear draft")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        onShare(
                            "Share markdown",
                            "text/markdown",
                            draftMarkdown(state.draftTitle, state.rollingDraft, projectName),
                        )
                    },
                    enabled = state.rollingDraft.isNotBlank(),
                ) {
                    Text("Share markdown")
                }
                TextButton(
                    onClick = {
                        onShare(
                            "Share JSON",
                            "application/json",
                            draftJson(
                                state.draftTitle,
                                state.rollingDraft,
                                state.selectedProjectId ?: 0,
                                projectName,
                            ),
                        )
                    },
                    enabled = state.rollingDraft.isNotBlank(),
                ) {
                    Text("Share JSON")
                }
            }
            NotesList(
                notes = state.notes,
                projects = state.projects,
                onEditNote = onEditNote,
                onShare = onShare,
            )
        }
    }
}

@Composable
private fun DictationEngineDebugPanel(
    engineSettings: DictationEngineSettingsState,
    onEngineChoiceSelected: (DictationEngineChoice) -> Unit,
    onImportWhisperModel: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A241B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dictation engine", color = Color(0xFFF4C95D), fontWeight = FontWeight.Black)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EngineChoiceButton(
                    label = "Auto",
                    selected = engineSettings.choice == DictationEngineChoice.Auto,
                    onClick = { onEngineChoiceSelected(DictationEngineChoice.Auto) },
                )
                EngineChoiceButton(
                    label = "SpeechRecognizer",
                    selected = engineSettings.choice == DictationEngineChoice.SpeechRecognizer,
                    onClick = { onEngineChoiceSelected(DictationEngineChoice.SpeechRecognizer) },
                )
                EngineChoiceButton(
                    label = "Whisper local",
                    selected = engineSettings.choice == DictationEngineChoice.Whisper,
                    onClick = { onEngineChoiceSelected(DictationEngineChoice.Whisper) },
                )
                EngineChoiceButton(
                    label = "Vosk local",
                    selected = engineSettings.choice == DictationEngineChoice.Vosk,
                    onClick = { onEngineChoiceSelected(DictationEngineChoice.Vosk) },
                )
            }
            Text(
                "Whisper native: ${if (engineSettings.whisperNativeAvailable) "available" else "not available"}",
                color = Color(0xFFEFE1C8),
            )
            Text(
                "Whisper model: ${if (engineSettings.whisperModelExists) "found" else "missing"}",
                color = Color(0xFFEFE1C8),
            )
            Text(engineSettings.modelPath, color = Color(0xFFCABCA4), style = MaterialTheme.typography.bodySmall)
            Text(
                "Vosk bundled model: ${if (engineSettings.voskBundledModelExists) "available" else "missing"}",
                color = Color(0xFFEFE1C8),
            )
            Text(
                "Vosk copied model: ${if (engineSettings.voskModelExists) "found" else "missing"}",
                color = Color(0xFFEFE1C8),
            )
            Text(engineSettings.voskModelPath, color = Color(0xFFCABCA4), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onImportWhisperModel) {
                Text("Import Whisper .bin")
            }
        }
    }
}

@Composable
private fun EngineChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFFF4C95D) else Color(0xFF463B2A),
            contentColor = if (selected) Color(0xFF1D1811) else Color(0xFFF5EFE6),
        ),
    ) {
        Text(label)
    }
}

@Composable
private fun EditScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onBodyChanged: (String) -> Unit,
    onProjectSelected: (Long) -> Unit,
    onSave: () -> Unit,
    onShare: (String, String, String) -> Unit,
) {
    val selectedProject = state.projects.firstOrNull { it.id == state.editProjectId }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5EFE6))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onSave) { Text("Save") }
        }
        Text(
            text = "Manual edit",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = Color(0xFF1D1811),
        )
        ProjectPills(
            projects = state.projects,
            selectedProjectId = state.editProjectId,
            onProjectSelected = onProjectSelected,
        )
        OutlinedTextField(
            value = state.editTitle,
            onValueChange = onTitleChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.editBody,
            onValueChange = onBodyChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            label = { Text("Body") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = {
                    onShare(
                        "Share markdown",
                        "text/markdown",
                        draftMarkdown(state.editTitle, state.editBody, selectedProject?.name.orEmpty()),
                    )
                },
            ) {
                Text("Share markdown")
            }
            TextButton(
                onClick = {
                    onShare(
                        "Share JSON",
                        "application/json",
                        draftJson(
                            state.editTitle,
                            state.editBody,
                            state.editProjectId ?: 0,
                            selectedProject?.name.orEmpty(),
                        ),
                    )
                },
            ) {
                Text("Share JSON")
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Dicto",
                color = Color(0xFFF4C95D),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Fast memory dictation",
                color = Color(0xFFEFE1C8),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFF4C95D)),
            contentAlignment = Alignment.Center,
        ) {
            Text("D", color = Color(0xFF2D2417), fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ProjectBar(
    projects: List<ProjectEntity>,
    selectedProjectId: Long?,
    newProjectName: String,
    onProjectSelected: (Long) -> Unit,
    onNewProjectName: (String) -> Unit,
    onCreateProject: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Projects", color = Color(0xFFEFE1C8), fontWeight = FontWeight.Bold)
        ProjectPills(projects, selectedProjectId, onProjectSelected)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newProjectName,
                onValueChange = onNewProjectName,
                modifier = Modifier.weight(1f),
                label = { Text("New project") },
                singleLine = true,
            )
            Button(onClick = onCreateProject, enabled = newProjectName.isNotBlank()) {
                Text("Add")
            }
        }
    }
}

@Composable
private fun ProjectPills(
    projects: List<ProjectEntity>,
    selectedProjectId: Long?,
    onProjectSelected: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        projects.forEach { project ->
            val selected = project.id == selectedProjectId
            Button(
                onClick = { onProjectSelected(project.id) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color(0xFFF4C95D) else Color(0xFF463B2A),
                    contentColor = if (selected) Color(0xFF1D1811) else Color(0xFFF5EFE6),
                ),
            ) {
                Text(project.name)
            }
        }
    }
}

@Composable
private fun StatusPanel(hasAudioPermission: Boolean, speechMode: String, status: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A241B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (hasAudioPermission) "Microphone permission granted" else "Microphone permission required",
                color = Color(0xFFEFE1C8),
            )
            Text(text = speechMode, color = Color(0xFFD9A86C))
            if (status != null) Text(text = status, color = Color(0xFF8BC6A8))
        }
    }
}

@Composable
private fun TranscriptCard(title: String, body: String, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A241B)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontWeight = FontWeight.Black)
            Text(
                text = body.ifBlank { "No text yet." },
                color = Color(0xFFF5EFE6),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun NotesList(
    notes: List<NoteEntity>,
    projects: List<ProjectEntity>,
    onEditNote: (Long) -> Unit,
    onShare: (String, String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Saved notes", color = Color(0xFFEFE1C8), fontWeight = FontWeight.Bold)
        if (notes.isEmpty()) {
            Text("No notes in this project yet.", color = Color(0xFFCABCA4))
        }
        notes.forEach { note ->
            val projectName = projects.firstOrNull { it.id == note.projectId }?.name.orEmpty()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5EFE6)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(note.title, color = Color(0xFF1D1811), fontWeight = FontWeight.Black)
                    Text(
                        note.body.take(180),
                        color = Color(0xFF463B2A),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onEditNote(note.id) }) { Text("Edit") }
                        TextButton(
                            onClick = {
                                onShare("Share markdown", "text/markdown", noteMarkdown(note, projectName))
                            },
                        ) {
                            Text("Markdown")
                        }
                        TextButton(
                            onClick = {
                                onShare("Share JSON", "application/json", noteJson(note, projectName))
                            },
                        ) {
                            Text("JSON")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF4C95D),
            secondary = Color(0xFF8BC6A8),
            background = Color(0xFF1D1811),
            surface = Color(0xFF2A241B),
            onPrimary = Color(0xFF1D1811),
            onSurface = Color(0xFFF5EFE6),
        ),
        typography = MaterialTheme.typography.copy(
            displayMedium = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Serif),
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
        ),
        content = content,
    )
}

private fun shareText(context: Context, title: String, mimeType: String, text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, title))
}

private fun displayNameForUri(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
}
