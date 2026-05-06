package com.mhlotto.dicto.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mhlotto.dicto.DictoApplication
import com.mhlotto.dicto.data.DictoRepository
import com.mhlotto.dicto.data.NoteEntity
import com.mhlotto.dicto.data.ProjectEntity
import com.mhlotto.dicto.speech.DictationEngineChoice
import com.mhlotto.dicto.speech.DictationEngineFactory
import com.mhlotto.dicto.speech.DictationEngineSettings
import com.mhlotto.dicto.speech.DictationEngineSettingsState
import com.mhlotto.dicto.speech.DictationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val selectedProjectId: Long? = null,
    val newProjectName: String = "",
    val draftTitle: String = "",
    val committedTranscript: String = "",
    val livePartial: String = "",
    val rollingDraft: String = "",
    val isRecording: Boolean = false,
    val speechModeLabel: String = "Recognizer not started",
    val statusMessage: String? = null,
    val screen: AppScreen = AppScreen.Dictate,
    val editNoteId: Long? = null,
    val editTitle: String = "",
    val editBody: String = "",
    val editProjectId: Long? = null,
    val dictationCommandTrigger: String = DictationCommandFormatter.DEFAULT_TRIGGER_PHRASE,
)

sealed interface AppScreen {
    data object Dictate : AppScreen
    data object Edit : AppScreen
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val repository: DictoRepository,
    private val dictationEngineFactory: DictationEngineFactory,
    private val dictationEngineSettings: DictationEngineSettings,
    private val dictationCommandSettings: DictationCommandSettings,
) : ViewModel() {
    private var dictationEngine = dictationEngineFactory.create()
    private val selectedProjectId = MutableStateFlow<Long?>(null)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private val _dictationState = MutableStateFlow(dictationEngine.state.value)
    val dictationState: StateFlow<DictationState> = _dictationState.asStateFlow()
    val engineSettingsState: StateFlow<DictationEngineSettingsState> = dictationEngineSettings.state
    val commandSettingsState: StateFlow<DictationCommandSettingsState> = dictationCommandSettings.state

    private val notes = selectedProjectId.flatMapLatest { projectId ->
        if (projectId == null) flowOf(emptyList()) else repository.observeNotes(projectId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val draftsByProject = mutableMapOf<Long, DraftBuffer>()
    private var editLoadJob: Job? = null
    private var dictationStateJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureDefaultProject()
        }
        viewModelScope.launch {
            repository.projects.collect { projects ->
                val current = selectedProjectId.value
                val next = current ?: projects.firstOrNull()?.id
                if (next != null && next != current) selectedProjectId.value = next
                _uiState.update {
                    it.copy(
                        projects = projects,
                        selectedProjectId = next,
                        editProjectId = it.editProjectId ?: next,
                    )
                }
            }
        }
        viewModelScope.launch {
            selectedProjectId.collect { id ->
                updateTranscriptState(id)
            }
        }
        viewModelScope.launch {
            notes.collect { noteList ->
                _uiState.update { it.copy(notes = noteList) }
            }
        }
        viewModelScope.launch {
            dictationCommandSettings.state.collect { commandSettings ->
                _uiState.update {
                    it.copy(dictationCommandTrigger = commandSettings.triggerPhrase)
                }
                onDictationState(dictationEngine.state.value)
            }
        }
        bindDictationEngine()
    }

    fun selectProject(projectId: Long) {
        val currentProjectId = selectedProjectId.value
        if (currentProjectId == projectId) return
        viewModelScope.launch {
            val autoSaved = if (currentProjectId != null) {
                saveDraftForProject(currentProjectId, clearAfterSave = true)
            } else {
                false
            }
            selectedProjectId.value = projectId
            val status = if (autoSaved) {
                "Autosaved captured text before switching projects"
            } else {
                "Switched project"
            }
            if (dictationEngine.state.value.isRecording) {
                dictationEngine.start(projectId)
            }
            _uiState.update { it.copy(statusMessage = status) }
        }
    }

    fun updateNewProjectName(value: String) {
        _uiState.update { it.copy(newProjectName = value) }
    }

    fun createProject() {
        val name = uiState.value.newProjectName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            selectedProjectId.value?.let { saveDraftForProject(it, clearAfterSave = true) }
            val id = repository.createProject(name)
            selectedProjectId.value = id
            if (dictationEngine.state.value.isRecording) {
                dictationEngine.start(id)
            }
            _uiState.update { it.copy(newProjectName = "", statusMessage = "Project created") }
        }
    }

    fun updateDraftTitle(value: String) {
        val projectId = selectedProjectId.value ?: return
        draftFor(projectId).title = value
        _uiState.update { it.copy(draftTitle = value) }
    }

    fun startDictation() {
        val projectId = selectedProjectId.value ?: return
        dictationEngine.start(projectId)
    }

    fun stopDictation() {
        dictationEngine.stop()
    }

    fun setEngineChoice(choice: DictationEngineChoice) {
        val wasRecording = dictationEngine.state.value.isRecording
        dictationEngineSettings.setChoice(choice)
        replaceDictationEngine()
        if (wasRecording) startDictation()
    }

    fun refreshEngineSettings() {
        dictationEngineSettings.refresh()
    }

    fun importWhisperModel(displayName: String?, bytes: ByteArray) {
        viewModelScope.launch {
            val path = dictationEngineSettings.importModel(displayName, bytes)
            replaceDictationEngine()
            _uiState.update { it.copy(statusMessage = "Imported Whisper model: $path") }
        }
    }

    fun updateDictationCommandTrigger(value: String) {
        dictationCommandSettings.setTriggerPhrase(value)
    }

    fun clearDraft() {
        val projectId = selectedProjectId.value ?: return
        draftsByProject.remove(projectId)
        if (dictationEngine.state.value.activeProjectId == projectId) {
            val restart = dictationEngine.state.value.isRecording
            dictationEngine.cancel()
            if (restart) dictationEngine.start(projectId)
        }
        updateTranscriptState(projectId)
    }

    fun saveDraft() {
        val state = uiState.value
        val projectId = state.selectedProjectId ?: return
        viewModelScope.launch {
            val wasRecording = dictationEngine.state.value.isRecording &&
                dictationEngine.state.value.activeProjectId == projectId
            val saved = saveDraftForProject(projectId, clearAfterSave = true)
            if (saved && dictationEngine.state.value.activeProjectId == projectId) {
                dictationEngine.cancel()
                if (wasRecording) dictationEngine.start(projectId)
            }
            _uiState.update {
                it.copy(
                    statusMessage = if (saved) {
                        "Saved to ${projectName(projectId)}"
                    } else {
                        "Nothing to save yet"
                    },
                )
            }
            updateTranscriptState(projectId)
        }
    }

    fun saveDraftAndEdit() {
        val projectId = uiState.value.selectedProjectId ?: return
        val body = draftFor(projectId).rollingText().trim()
        if (body.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Nothing to save yet") }
            return
        }
        viewModelScope.launch {
            val noteId = repository.saveNote(
                title = draftFor(projectId).title.trim(),
                body = body,
                projectId = projectId,
            )
            val wasRecording = dictationEngine.state.value.isRecording &&
                dictationEngine.state.value.activeProjectId == projectId
            draftsByProject.remove(projectId)
            if (dictationEngine.state.value.activeProjectId == projectId) {
                dictationEngine.cancel()
                if (wasRecording) dictationEngine.start(projectId)
            }
            updateTranscriptState(projectId)
            _uiState.update { it.copy(statusMessage = "Saved to ${projectName(projectId)}") }
            openEdit(noteId)
        }
    }

    fun openEdit(noteId: Long) {
        editLoadJob?.cancel()
        _uiState.update { it.copy(screen = AppScreen.Edit, editNoteId = noteId) }
        editLoadJob = viewModelScope.launch {
            val note = repository.getNote(noteId) ?: return@launch
            _uiState.update {
                it.copy(
                    editTitle = note.title,
                    editBody = note.body,
                    editProjectId = note.projectId,
                )
            }
        }
    }

    fun closeEdit() {
        _uiState.update { it.copy(screen = AppScreen.Dictate, editNoteId = null) }
    }

    fun updateEditTitle(value: String) {
        _uiState.update { it.copy(editTitle = value) }
    }

    fun updateEditBody(value: String) {
        _uiState.update { it.copy(editBody = value) }
    }

    fun updateEditProject(projectId: Long) {
        _uiState.update { it.copy(editProjectId = projectId) }
    }

    fun saveEdit() {
        val state = uiState.value
        val noteId = state.editNoteId ?: return
        val projectId = state.editProjectId ?: state.selectedProjectId ?: return
        viewModelScope.launch {
            repository.updateNote(noteId, state.editTitle.trim(), state.editBody, projectId)
            selectedProjectId.value = projectId
            _uiState.update { it.copy(statusMessage = "Note saved") }
        }
    }

    private fun onDictationState(state: DictationState) {
        val formattedState = state.withFormattedCommands()
        _dictationState.value = formattedState
        state.activeProjectId?.let { projectId ->
            val draft = draftFor(projectId)
            draft.committedText = formattedState.committedText
            draft.partialText = formattedState.partialText
            if (projectId == selectedProjectId.value) {
                updateTranscriptState(projectId)
            }
        }
        _uiState.update {
            it.copy(
                isRecording = formattedState.isRecording,
                speechModeLabel = formattedState.engineLabel,
                statusMessage = formattedState.error ?: if (formattedState.isRecording) "Listening" else it.statusMessage,
            )
        }
    }

    private fun DictationState.withFormattedCommands(): DictationState {
        return copy(
            committedText = DictationCommandFormatter.format(committedText, commandSettingsState.value.triggerPhrase),
            partialText = DictationCommandFormatter.format(partialText, commandSettingsState.value.triggerPhrase),
        )
    }

    private fun bindDictationEngine() {
        dictationStateJob?.cancel()
        _dictationState.value = dictationEngine.state.value
        dictationStateJob = viewModelScope.launch {
            dictationEngine.state.collect(::onDictationState)
        }
    }

    private fun replaceDictationEngine() {
        dictationEngine.cancel()
        dictationEngineSettings.refresh()
        dictationEngine = dictationEngineFactory.create()
        bindDictationEngine()
    }

    private suspend fun saveDraftForProject(projectId: Long, clearAfterSave: Boolean): Boolean {
        val draft = draftsByProject[projectId] ?: return false
        val body = draft.rollingText().trim()
        if (body.isBlank()) return false
        repository.saveNote(
            title = draft.title.trim(),
            body = body,
            projectId = projectId,
        )
        if (clearAfterSave) draftsByProject.remove(projectId)
        return true
    }

    private fun updateTranscriptState(projectId: Long?) {
        val draft = projectId?.let { draftsByProject[it] }
        _uiState.update {
            it.copy(
                selectedProjectId = projectId,
                draftTitle = draft?.title.orEmpty(),
                committedTranscript = draft?.committedText.orEmpty(),
                livePartial = draft?.partialText.orEmpty(),
                rollingDraft = draft?.rollingText().orEmpty(),
            )
        }
    }

    private fun draftFor(projectId: Long): DraftBuffer {
        return draftsByProject.getOrPut(projectId) { DraftBuffer() }
    }

    private fun projectName(projectId: Long): String {
        return uiState.value.projects.firstOrNull { it.id == projectId }?.name ?: "project"
    }

    private class DraftBuffer {
        var title: String = ""
        var committedText: String = ""
        var partialText: String = ""

        fun rollingText(): String {
            return listOf(committedText, partialText)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
        }
    }

    override fun onCleared() {
        dictationStateJob?.cancel()
        dictationEngine.cancel()
    }
}

class AppViewModelFactory(
    private val application: DictoApplication,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppViewModel(
            repository = application.repository,
            dictationEngineFactory = application.dictationEngineFactory,
            dictationEngineSettings = application.dictationEngineSettings,
            dictationCommandSettings = application.dictationCommandSettings,
        ) as T
    }
}
