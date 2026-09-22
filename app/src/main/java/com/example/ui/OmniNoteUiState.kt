package com.example.ui

import com.example.data.ai.ChatMessage
import com.example.data.local.NoteEntity

data class OmniNoteUiState(
    val notes: List<NoteEntity> = emptyList(),
    val filteredNotes: List<NoteEntity> = emptyList(),
    val selectedNote: NoteEntity? = null,
    val searchQuery: String = "",
    val isGridView: Boolean = true,
    val isDarkMode: Boolean = false,
    val isImportingPdf: Boolean = false,
    val pdfImportMessage: String = "",
    val activeTab: Int = 0, // 0 = Note Editor/Document, 1 = AI Chat
    val chatMessages: List<ChatMessage> = emptyList(),
    val isAiThinking: Boolean = false,
    val chatInputText: String = "",
    val showApiKeyDialog: Boolean = false,
    val currentApiKey: String = "",
    val snackbarMessage: String? = null
)
