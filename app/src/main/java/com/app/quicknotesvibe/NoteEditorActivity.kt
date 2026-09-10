package com.app.quicknotesvibe

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NoteEditorActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private var note: Note? = null
    private lateinit var notes: MutableList<Note>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        if (!Prefs.isConfigured()) {
            Toast.makeText(this, "Set up GitHub in settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java)); finish(); return
        }

        notes = NotesRepository.load(this)
        val etTitle = findViewById<EditText>(R.id.etTitle)
        val etBody = findViewById<EditText>(R.id.etBody)
        val btnSave = findViewById<View>(R.id.btnSave)
        val btnDelete = findViewById<View>(R.id.btnDelete)

        val existingId = intent.getStringExtra("note_id")
        if (existingId != null) {
            note = notes.firstOrNull { it.id == existingId }
            note?.let { etTitle.setText(it.title); etBody.setText(it.body) }
            btnDelete.visibility = View.VISIBLE
        }

        btnSave.setOnClickListener {
            val n = note ?: Note(title = "", body = "").also { note = it }
            n.title = etTitle.text.toString().trim()
            n.body = etBody.text.toString()
            NotesRepository.saveAndSync(scope, this, n, notes) { finish() }
        }

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete note?")
                .setPositiveButton("Delete") { _, _ ->
                    val n = note
                    if (n != null) {
                        notes.removeAll { it.id == n.id }
                        NotesRepository.persist(this, notes)
                        scope.launch(Dispatchers.IO) {
                            runCatching { GitHubApi.deleteNote(n) }
                        }
                    }
                    finish()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}