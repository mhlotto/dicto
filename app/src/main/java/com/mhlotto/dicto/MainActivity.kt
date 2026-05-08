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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.mhlotto.dicto.analysis.ExtractedEntity
import com.mhlotto.dicto.analysis.TextAnalysisResult
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
                onDictationCommandTriggerChanged = viewModel::updateDictationCommandTrigger,
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
                onAnalyze = viewModel::analyzeEditNote,
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
    onDictationCommandTriggerChanged: (String) -> Unit,
    onImportWhisperModel: () -> Unit,
    onShare: (String, String, String) -> Unit,
) {
    val selectedProject = state.projects.firstOrNull { it.id == state.selectedProjectId }
    val projectName = selectedProject?.name.orEmpty()
    val scroll = rememberScrollState()
    var showSettings by remember { mutableStateOf(false) }

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
                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 156.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            SettingsMenuToggle(
                engineLabel = engineSettings.selectedEngineLabel,
                showSettings = showSettings,
                onToggle = { showSettings = !showSettings },
            )
            if (showSettings) {
                DictationSettingsPanel(
                    engineSettings = engineSettings,
                    dictationCommandTrigger = state.dictationCommandTrigger,
                    onEngineChoiceSelected = onEngineChoiceSelected,
                    onDictationCommandTriggerChanged = onDictationCommandTriggerChanged,
                    onImportWhisperModel = onImportWhisperModel,
                )
            }
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
            StatusPanel(
                hasAudioPermission = hasAudioPermission,
                speechMode = state.speechModeLabel,
                status = state.statusMessage,
            )
            TranscriptCard(title = "Live partial", body = state.livePartial, accent = Color(0xFF8BC6A8))
            TranscriptCard(title = "Committed transcript", body = state.committedTranscript, accent = Color(0xFFD9A86C))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onSaveDraft, enabled = state.rollingDraft.isNotBlank()) {
                    Text("Save to $projectName")
                }
                Button(onClick = onSaveAndEdit, enabled = state.rollingDraft.isNotBlank()) {
                    Text("Save & edit")
                }
                Button(
                    onClick = onClearDraft,
                    enabled = state.rollingDraft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB94D3A),
                        contentColor = Color(0xFFF5EFE6),
                    ),
                ) {
                    Text("Discard")
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
        FloatingDictationButton(
            dictationState = dictationState,
            onStartStop = onStartStop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SettingsMenuToggle(
    engineLabel: String,
    showSettings: Boolean,
    onToggle: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A241B))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Settings", color = Color(0xFFF4C95D), fontWeight = FontWeight.Black)
                Text("Engine: $engineLabel", color = Color(0xFFCABCA4), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onToggle) {
                Text(if (showSettings) "Hide" else "Open")
            }
        }
    }
}

@Composable
private fun FloatingDictationButton(
    dictationState: DictationState,
    onStartStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xF21D1811)),
                ),
            )
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Button(
            onClick = onStartStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
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
    }
}

@Composable
private fun DictationSettingsPanel(
    engineSettings: DictationEngineSettingsState,
    dictationCommandTrigger: String,
    onEngineChoiceSelected: (DictationEngineChoice) -> Unit,
    onDictationCommandTriggerChanged: (String) -> Unit,
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
                EngineChoiceButton(
                    label = "ML Kit GenAI",
                    selected = engineSettings.choice == DictationEngineChoice.MlKitGenAi,
                    onClick = { onEngineChoiceSelected(DictationEngineChoice.MlKitGenAi) },
                )
            }
            when (engineSettings.choice) {
                DictationEngineChoice.Auto -> AutoEngineDetails(engineSettings)
                DictationEngineChoice.SpeechRecognizer -> SpeechRecognizerEngineDetails()
                DictationEngineChoice.Whisper -> WhisperEngineDetails(
                    engineSettings = engineSettings,
                    onImportWhisperModel = onImportWhisperModel,
                )
                DictationEngineChoice.Vosk -> VoskEngineDetails(engineSettings)
                DictationEngineChoice.MlKitGenAi -> MlKitGenAiEngineDetails(engineSettings)
            }
            DictationCommandDetails(
                triggerPhrase = dictationCommandTrigger,
                onTriggerPhraseChanged = onDictationCommandTriggerChanged,
            )
        }
    }
}

@Composable
private fun AutoEngineDetails(engineSettings: DictationEngineSettingsState) {
    val selected = when {
        engineSettings.whisperNativeAvailable && engineSettings.whisperModelExists -> "Whisper local"
        engineSettings.voskModelExists -> "Vosk local"
        else -> "SpeechRecognizer"
    }
    Text("Auto selected engine: $selected", color = Color(0xFFEFE1C8))
}

@Composable
private fun SpeechRecognizerEngineDetails() {
    Text("Uses Android SpeechRecognizer with on-device recognition when available.", color = Color(0xFFEFE1C8))
}

@Composable
private fun WhisperEngineDetails(
    engineSettings: DictationEngineSettingsState,
    onImportWhisperModel: () -> Unit,
) {
    Text(
        "Whisper native: ${if (engineSettings.whisperNativeAvailable) "available" else "not available"}",
        color = Color(0xFFEFE1C8),
    )
    Text(
        "Whisper model: ${if (engineSettings.whisperModelExists) "found" else "missing"}",
        color = Color(0xFFEFE1C8),
    )
    Text(engineSettings.modelPath, color = Color(0xFFCABCA4), style = MaterialTheme.typography.bodySmall)
    Button(onClick = onImportWhisperModel) {
        Text("Import Whisper .bin")
    }
}

@Composable
private fun VoskEngineDetails(engineSettings: DictationEngineSettingsState) {
    Text(
        "Vosk bundled model: ${if (engineSettings.voskBundledModelExists) "available" else "missing"}",
        color = Color(0xFFEFE1C8),
    )
    Text(
        "Vosk copied model: ${if (engineSettings.voskModelExists) "found" else "missing"}",
        color = Color(0xFFEFE1C8),
    )
    Text(engineSettings.voskModelPath, color = Color(0xFFCABCA4), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MlKitGenAiEngineDetails(engineSettings: DictationEngineSettingsState) {
    Text("Experimental ML Kit GenAI Speech Recognition alpha engine.", color = Color(0xFFEFE1C8))
    Text(
        "API compatibility: ${if (engineSettings.mlKitGenAiApiCompatible) "compatible" else "not compatible"}",
        color = Color(0xFFEFE1C8),
    )
    Text(
        engineSettings.mlKitGenAiAvailabilityReason ?: "Availability checked when the engine starts",
        color = Color(0xFFCABCA4),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Auto mode will not select this alpha engine.",
        color = Color(0xFFCABCA4),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DictationCommandDetails(
    triggerPhrase: String,
    onTriggerPhraseChanged: (String) -> Unit,
) {
    Text("Dictation command trigger", color = Color(0xFFF4C95D), fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = triggerPhrase,
        onValueChange = onTriggerPhraseChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Replacement trigger") },
        singleLine = true,
    )
    Text(
        "Say \"$triggerPhrase new line\" or \"$triggerPhrase period\" while dictating.",
        color = Color(0xFFCABCA4),
        style = MaterialTheme.typography.bodySmall,
    )
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
    onAnalyze: () -> Unit,
    onShare: (String, String, String) -> Unit,
) {
    val selectedProject = state.projects.firstOrNull { it.id == state.editProjectId }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Manual edit",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = Color(0xFFF4C95D),
        )
        ProjectPills(
            projects = state.projects,
            selectedProjectId = state.editProjectId,
            onProjectSelected = onProjectSelected,
        )
        val editFieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color(0xFFF5EFE6),
            unfocusedTextColor = Color(0xFFF5EFE6),
            focusedContainerColor = Color(0xFF151515),
            unfocusedContainerColor = Color(0xFF151515),
            focusedLabelColor = Color(0xFFF4C95D),
            unfocusedLabelColor = Color(0xFFCABCA4),
            focusedBorderColor = Color(0xFFF4C95D),
            unfocusedBorderColor = Color(0xFF6C5A3F),
            cursorColor = Color(0xFFF4C95D),
        )
        OutlinedTextField(
            value = state.editTitle,
            onValueChange = onTitleChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            singleLine = true,
            colors = editFieldColors,
        )
        OutlinedTextField(
            value = state.editBody,
            onValueChange = onBodyChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            label = { Text("Body") },
            colors = editFieldColors,
        )
        TextAnalysisPanel(
            isAnalyzing = state.isAnalyzing,
            result = state.analysisResults,
            error = state.analysisError,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onAnalyze,
                enabled = !state.isAnalyzing && state.editBody.isNotBlank(),
            ) {
                Text(if (state.isAnalyzing) "Analyzing..." else "Analyze text")
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

@Composable
private fun TextAnalysisPanel(
    isAnalyzing: Boolean,
    result: TextAnalysisResult?,
    error: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151515)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Text analysis", color = Color(0xFFF4C95D), fontWeight = FontWeight.Black)
            when {
                isAnalyzing -> Text("Downloading model or analyzing text...", color = Color(0xFFEFE1C8))
                error != null -> Text(error, color = Color(0xFFB94D3A))
                result == null -> Text("Run analysis to extract entities from this saved note.", color = Color(0xFFCABCA4))
                result.entities.isEmpty() -> Text("No entities found.", color = Color(0xFFEFE1C8))
                else -> {
                    result.entities
                        .groupBy { it.displayGroup() }
                        .toSortedMap()
                        .forEach { (group, entities) ->
                            Text(group, color = Color(0xFFD9A86C), fontWeight = FontWeight.Bold)
                            entities.forEach { entity ->
                                ExtractedEntityRow(entity)
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ExtractedEntityRow(entity: ExtractedEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(entity.text, color = Color(0xFFF5EFE6), fontWeight = FontWeight.SemiBold)
        Text(entity.type, color = Color(0xFFCABCA4), style = MaterialTheme.typography.bodySmall)
        if (entity.metadata.isNotEmpty()) {
            Text(
                entity.metadata.entries.joinToString(" | ") { "${it.key}: ${it.value}" },
                color = Color(0xFF8BC6A8),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun ExtractedEntity.displayGroup(): String {
    return when (type.lowercase()) {
        "date time" -> "Dates and times"
        "address" -> "Addresses"
        "phone" -> "Phone numbers"
        "email" -> "Emails"
        "url" -> "URLs"
        "money" -> "Money"
        else -> "Other"
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
