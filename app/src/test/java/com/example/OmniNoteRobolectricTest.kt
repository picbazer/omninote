package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.ChatMessage
import com.example.data.ai.GeminiService
import com.example.data.local.AppDatabase
import com.example.data.local.NoteDao
import com.example.data.local.NoteEntity
import com.example.data.repository.NoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniNoteRobolectricTest {

    private lateinit var db: AppDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var repository: NoteRepository
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = db.noteDao()
        repository = NoteRepository(noteDao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testInsertAndRetrieveNote() = runBlocking {
        val note = NoteEntity(
            title = "Test Note",
            content = "This is a test note content about artificial intelligence.",
            timestamp = System.currentTimeMillis()
        )
        val id = repository.insertNote(note)
        assertTrue(id > 0)

        val notes = repository.allNotes.first()
        assertEquals(1, notes.size)
        assertEquals("Test Note", notes[0].title)
        assertEquals("This is a test note content about artificial intelligence.", notes[0].content)
    }

    @Test
    fun testUpdateNote() = runBlocking {
        val note = NoteEntity(title = "Original Title", content = "Original Content")
        val id = repository.insertNote(note)

        val updatedNote = NoteEntity(id = id, title = "Updated Title", content = "Updated Content")
        repository.updateNote(updatedNote)

        val notes = repository.allNotes.first()
        assertEquals(1, notes.size)
        assertEquals("Updated Title", notes[0].title)
        assertEquals("Updated Content", notes[0].content)
    }

    @Test
    fun testDeleteNote() = runBlocking {
        val note = NoteEntity(title = "To Delete", content = "Will be deleted")
        val id = repository.insertNote(note)

        val inserted = repository.allNotes.first()
        assertEquals(1, inserted.size)

        repository.deleteNoteById(id)
        val emptyList = repository.allNotes.first()
        assertEquals(0, emptyList.size)
    }

    @Test
    fun testGeminiServiceGroundedResponse() = runBlocking {
        val geminiService = GeminiService(context)
        val response = geminiService.generateGroundedResponse(
            documentTitle = "Meeting Summary",
            documentContent = "The project deadline is October 15th. Sarah will handle front-end.",
            history = emptyList(),
            latestQuery = "When is the project deadline?"
        )

        assertTrue(response.isSuccess)
        val text = response.getOrNull()
        assertNotNull(text)
        // Verify response contains grounded information from context
        assertTrue(text!!.contains("October") || text.contains("deadline") || text.contains("Meeting"))
    }
}
