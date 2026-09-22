package com.example.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.ChatMessage
import com.example.data.ai.GeminiService
import com.example.data.local.AppDatabase
import com.example.data.local.NoteEntity
import com.example.data.repository.NoteRepository
import com.example.util.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OmniNoteViewModel(
    private val repository: NoteRepository,
    private val geminiService: GeminiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(OmniNoteUiState())
    val uiState: StateFlow<OmniNoteUiState> = _uiState.asStateFlow()

    private var hasAttemptedSeed = false

    init {
        // Collect notes from Room database reactively
        viewModelScope.launch {
            repository.allNotes.collect { notesList ->
                if (notesList.isEmpty() && !hasAttemptedSeed) {
                    hasAttemptedSeed = true
                    populateSampleNotes()
                    return@collect
                }

                _uiState.update { current ->
                    val filtered = filterNotes(notesList, current.searchQuery)
                    current.copy(
                        notes = notesList,
                        filteredNotes = filtered,
                        currentApiKey = geminiService.getApiKey()
                    )
                }

                // If currently editing a note, refresh its state from DB if updated externally
                val currentSelected = _uiState.value.selectedNote
                if (currentSelected != null) {
                    val refreshed = notesList.find { it.id == currentSelected.id }
                    if (refreshed != null) {
                        _uiState.update { it.copy(selectedNote = refreshed) }
                    }
                }
            }
        }
    }

    private fun filterNotes(notes: List<NoteEntity>, query: String): List<NoteEntity> {
        if (query.isBlank()) return notes
        val q = query.trim().lowercase()
        return notes.filter {
            it.title.lowercase().contains(q) || it.content.lowercase().contains(q)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { current ->
            current.copy(
                searchQuery = query,
                filteredNotes = filterNotes(current.notes, query)
            )
        }
    }

    fun toggleLayoutView() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
    }

    fun selectNote(note: NoteEntity) {
        _uiState.update {
            it.copy(
                selectedNote = note,
                activeTab = 0,
                chatMessages = emptyList(),
                chatInputText = ""
            )
        }
    }

    fun closeNoteDetail() {
        _uiState.update {
            it.copy(
                selectedNote = null,
                activeTab = 0,
                chatMessages = emptyList(),
                chatInputText = ""
            )
        }
    }

    fun createNewNote() {
        viewModelScope.launch {
            val newNote = NoteEntity(
                title = "Untitled Note",
                content = "",
                timestamp = System.currentTimeMillis()
            )
            val newId = repository.insertNote(newNote)
            val created = newNote.copy(id = newId)
            _uiState.update {
                it.copy(
                    selectedNote = created,
                    activeTab = 0,
                    chatMessages = emptyList()
                )
            }
        }
    }

    fun updateCurrentNote(title: String, content: String) {
        val current = _uiState.value.selectedNote ?: return
        val updated = current.copy(
            title = title,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        _uiState.update { it.copy(selectedNote = updated) }
        viewModelScope.launch {
            repository.updateNote(updated)
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            repository.deleteNote(note)
            if (_uiState.value.selectedNote?.id == note.id) {
                closeNoteDetail()
            }
            showSnackbar("Note \"${note.title}\" deleted")
        }
    }

    fun deleteCurrentNote() {
        val current = _uiState.value.selectedNote ?: return
        deleteNote(current)
    }

    fun importPdf(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingPdf = true,
                    pdfImportMessage = "Reading and extracting text from PDF..."
                )
            }

            try {
                val result = PdfTextExtractor.extractTextFromUri(context, uri)
                val newNote = NoteEntity(
                    title = result.title,
                    content = result.text,
                    timestamp = System.currentTimeMillis()
                )
                val newId = repository.insertNote(newNote)
                val savedNote = newNote.copy(id = newId)

                _uiState.update {
                    it.copy(
                        isImportingPdf = false,
                        pdfImportMessage = "",
                        selectedNote = savedNote,
                        activeTab = 0,
                        chatMessages = emptyList(),
                        snackbarMessage = "Imported \"${result.title}\" (${result.pageCount} pages, ${result.charCount} chars)"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImportingPdf = false,
                        pdfImportMessage = "",
                        snackbarMessage = "PDF import failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun setActiveTab(tabIndex: Int) {
        _uiState.update { it.copy(activeTab = tabIndex) }
    }

    fun onChatInputChanged(text: String) {
        _uiState.update { it.copy(chatInputText = text) }
    }

    fun sendChatMessage(queryOverride: String? = null) {
        val note = _uiState.value.selectedNote ?: return
        val messageText = (queryOverride ?: _uiState.value.chatInputText).trim()
        if (messageText.isBlank() || _uiState.value.isAiThinking) return

        val userMessage = ChatMessage(
            role = ChatMessage.Role.USER,
            content = messageText
        )

        val updatedHistory = _uiState.value.chatMessages + userMessage
        _uiState.update {
            it.copy(
                chatMessages = updatedHistory,
                chatInputText = "",
                isAiThinking = true
            )
        }

        viewModelScope.launch {
            val responseResult = geminiService.generateGroundedResponse(
                documentTitle = note.title,
                documentContent = note.content,
                history = updatedHistory,
                latestQuery = messageText
            )

            val modelMessage = responseResult.fold(
                onSuccess = { answer ->
                    ChatMessage(
                        role = ChatMessage.Role.MODEL,
                        content = answer
                    )
                },
                onFailure = { error ->
                    ChatMessage(
                        role = ChatMessage.Role.MODEL,
                        content = "Sorry, I couldn't process this request: ${error.message}",
                        isError = true
                    )
                }
            )

            _uiState.update {
                it.copy(
                    chatMessages = it.chatMessages + modelMessage,
                    isAiThinking = false
                )
            }
        }
    }

    fun triggerQuickAction(actionPrompt: String) {
        // Switch to AI chat tab and send prompt
        _uiState.update { it.copy(activeTab = 1) }
        sendChatMessage(actionPrompt)
    }

    fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList()) }
    }

    fun openApiKeyDialog() {
        _uiState.update { it.copy(showApiKeyDialog = true, currentApiKey = geminiService.getApiKey()) }
    }

    fun dismissApiKeyDialog() {
        _uiState.update { it.copy(showApiKeyDialog = false) }
    }

    fun saveApiKey(newKey: String) {
        geminiService.saveCustomApiKey(newKey)
        _uiState.update {
            it.copy(
                showApiKeyDialog = false,
                currentApiKey = geminiService.getApiKey(),
                snackbarMessage = if (newKey.isBlank()) "API Key cleared (Local grounded mode active)" else "Gemini API key saved!"
            )
        }
    }

    fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun populateSampleNotes() {
        viewModelScope.launch {
            val sampleNote = NoteEntity(
                title = "Quantum Computing Fundamentals",
                content = """
                    # Quantum Computing Fundamentals
                    
                    Quantum computing harnesses the unique phenomena of quantum mechanics—namely superposition and entanglement—to perform complex computations exponentially faster than classical computers for specific problem spaces.
                    
                    ## Key Principles:
                    1. **Qubits (Quantum Bits)**: Unlike classical bits which are strictly 0 or 1, qubits can exist in a superposition of both states simultaneously (represented mathematically on the Bloch Sphere).
                    2. **Quantum Entanglement**: When qubits become entangled, the quantum state of one instantaneously determines the state of the other, regardless of physical separation. Einstein famously referred to this as "spooky action at a distance."
                    3. **Quantum Interference**: Quantum algorithms manipulate probabilities through constructive and destructive interference, amplifying correct computational paths while canceling erroneous solutions.
                    
                    ## Notable Algorithms:
                    - **Shor's Algorithm**: Discovered by Peter Shor in 1994, it solves prime factorization in polynomial time, posing a fundamental challenge to traditional RSA cryptographic protocols.
                    - **Grover's Algorithm**: Provides quadratic speedup for searching unstructured databases (O(√N) compared to classical O(N)).
                    
                    ## Hardware Implementations:
                    - Superconducting Transmon Qubits (IBM, Google Sycamore)
                    - Trapped Ion systems (IonQ, Quantinuum)
                    - Photonic Quantum Computing (PsiQuantum, Xanadu)
                    - Neutral Atom arrays (QuEra)
                    
                    ## Key Challenges:
                    - Quantum Decoherence & Thermal Noise
                    - Fault-Tolerant Quantum Error Correction (QEC) requiring thousands of physical qubits per single logical qubit.
                """.trimIndent(),
                timestamp = System.currentTimeMillis()
            )
            repository.insertNote(sampleNote)
            showSnackbar("Sample notebook loaded! Explore or chat with AI.")
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val db = AppDatabase.getDatabase(context)
                    val repo = NoteRepository(db.noteDao())
                    val ai = GeminiService(context)
                    return OmniNoteViewModel(repo, ai) as T
                }
            }
    }
}
