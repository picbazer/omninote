package com.example
 
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.OmniNoteViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NoteDetailScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: OmniNoteViewModel by viewModels {
        OmniNoteViewModel.provideFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current

            MyApplicationTheme(darkTheme = uiState.isDarkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (uiState.selectedNote != null) {
                        BackHandler {
                            viewModel.closeNoteDetail()
                        }
                        NoteDetailScreen(
                            note = uiState.selectedNote!!,
                            uiState = uiState,
                            onBack = { viewModel.closeNoteDetail() },
                            onUpdateNote = { title, content ->
                                viewModel.updateCurrentNote(title, content)
                            },
                            onDeleteNote = { viewModel.deleteCurrentNote() },
                            onTabSelected = { viewModel.setActiveTab(it) },
                            onChatInputChanged = { viewModel.onChatInputChanged(it) },
                            onSendMessage = { prompt -> viewModel.sendChatMessage(prompt) },
                            onQuickAction = { action -> viewModel.triggerQuickAction(action) },
                            onClearChat = { viewModel.clearChat() }
                        )
                    } else {
                        HomeScreen(
                            uiState = uiState,
                            onSelectNote = { viewModel.selectNote(it) },
                            onCreateNewNote = { viewModel.createNewNote() },
                            onImportPdf = { uri -> viewModel.importPdf(context, uri) },
                            onDeleteNote = { viewModel.deleteNote(it) },
                            onSearchChanged = { viewModel.onSearchQueryChanged(it) },
                            onToggleView = { viewModel.toggleLayoutView() },
                            onToggleTheme = { viewModel.toggleTheme() },
                            onOpenApiKeyDialog = { viewModel.openApiKeyDialog() },
                            onSaveApiKey = { viewModel.saveApiKey(it) },
                            onDismissApiKeyDialog = { viewModel.dismissApiKeyDialog() },
                            onPopulateSample = { viewModel.populateSampleNotes() },
                            onDismissSnackbar = { viewModel.dismissSnackbar() }
                        )
                    }
                }
            }
        }
    }
}

// Keep Greeting for test backward compatibility
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
