package com.android.quicknotesvibe

import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch

class NoteEditorActivity : AppCompatActivity() {
    private val scope = kotlinx.coroutines.MainScope()
    private var note: Note? = null
    private lateinit var notes: MutableList<Note>
    /** Basename last known on GitHub — used to delete the old file if the user renames it. */
    private var previousFilename: String? = null

    private lateinit var etBody: EditText
    private var bodyTextSizeSp = 16f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        if (!Prefs.isConfigured()) {
            Toast.makeText(this, "Set up GitHub in settings first", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        notes = NotesRepository.load(this)
        val etTitle = findViewById<EditText>(R.id.etTitle)
        val etFilename = findViewById<EditText>(R.id.etFilename)
        etBody = findViewById(R.id.etBody)
        val btnSave = findViewById<ImageButton>(R.id.btnSave)
        val btnDelete = findViewById<ImageButton>(R.id.btnDelete)

        val existingId = intent.getStringExtra("note_id")
        if (existingId != null) {
            note = notes.firstOrNull { it.id == existingId }
            note?.let {
                etTitle.setText(it.title)
                etBody.setText(it.body)
                etFilename.setText(it.filename.ifBlank { it.effectiveFilename() })
                previousFilename = it.effectiveFilename()
            }
            btnDelete.visibility = android.view.View.VISIBLE
        }

        // Suggest a filename from the title when the filename field is still empty
        etTitle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && etFilename.text.isNullOrBlank()) {
                val suggestion = Note.sanitizeFilename(etTitle.text.toString())
                if (suggestion.isNotBlank() && suggestion != "note") {
                    etFilename.setText(suggestion)
                }
            }
        }

        // --- Formatting toolbar ---
        findViewById<Button>(R.id.btnBold).setOnClickListener {
            wrapSelection("**", "**")
        }
        findViewById<Button>(R.id.btnItalic).setOnClickListener {
            wrapSelection("*", "*")
        }
        findViewById<Button>(R.id.btnUnderline).setOnClickListener {
            // HTML underline — widely supported in Markdown renderers
            wrapSelection("<u>", "</u>")
        }
        findViewById<Button>(R.id.btnCode).setOnClickListener {
            wrapSelection("`", "`")
        }
        findViewById<Button>(R.id.btnCodeBlock).setOnClickListener {
            wrapSelection("\n```\n", "\n```\n")
        }
        findViewById<Button>(R.id.btnH1).setOnClickListener {
            prefixLine("# ")
        }
        findViewById<Button>(R.id.btnH2).setOnClickListener {
            prefixLine("## ")
        }
        findViewById<Button>(R.id.btnH3).setOnClickListener {
            prefixLine("### ")
        }
        findViewById<Button>(R.id.btnSizeUp).setOnClickListener {
            changeBodyTextSize(2f)
        }
        findViewById<Button>(R.id.btnSizeDown).setOnClickListener {
            changeBodyTextSize(-2f)
        }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            var filename = etFilename.text.toString().trim()
            if (filename.isBlank()) {
                filename = Note.sanitizeFilename(title.ifBlank { "note" })
                etFilename.setText(filename)
            } else {
                filename = Note.sanitizeFilename(filename)
                etFilename.setText(filename)
            }

            // Avoid colliding with another local note's filename
            val n = note ?: Note(title = title, body = "").also { note = it }
            val clash = notes.any { it.id != n.id && it.effectiveFilename() == filename }
            if (clash) {
                Toast.makeText(this, "That file name is already used by another note", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            n.title = title
            n.body = etBody.text.toString()
            n.filename = filename

            NotesRepository.saveAndSync(
                scope, this, n, notes,
                previousFilename = previousFilename
            ) {
                previousFilename = n.effectiveFilename()
                finish()
            }
        }

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete note?")
                .setPositiveButton("Delete") { _, _ ->
                    val n = note
                    if (n != null) {
                        notes.removeAll { it.id == n.id }
                        NotesRepository.persist(this, notes)
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { GitHubApi.deleteNote(n) }
                        }
                    }
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Wrap the current selection (or insert markers at the cursor) with [before] / [after].
     * If nothing is selected, places the cursor between the markers.
     */
    private fun wrapSelection(before: String, after: String) {
        val start = etBody.selectionStart.coerceAtLeast(0)
        val end = etBody.selectionEnd.coerceAtLeast(0)
        val text = etBody.text ?: return

        if (start == end) {
            // No selection — insert markers and put cursor in the middle
            text.insert(start, before + after)
            etBody.setSelection(start + before.length)
        } else {
            val selected = text.subSequence(start, end).toString()
            text.replace(start, end, before + selected + after)
            etBody.setSelection(start, start + before.length + selected.length + after.length)
        }
    }

    /**
     * Prefix the current line (or each selected line) with [prefix], e.g. "# " for H1.
     * Strips an existing heading prefix of the same family first so toggling works.
     */
    private fun prefixLine(prefix: String) {
        val text = etBody.text ?: return
        var start = etBody.selectionStart.coerceAtLeast(0)
        var end = etBody.selectionEnd.coerceAtLeast(0)

        // Expand to full lines
        while (start > 0 && text[start - 1] != '\n') start--
        while (end < text.length && text[end] != '\n') end++

        val block = text.subSequence(start, end).toString()
        val lines = block.split('\n')
        val headingRegex = Regex("^#{1,6}\\s+")

        val rewritten = lines.joinToString("\n") { line ->
            val stripped = line.replaceFirst(headingRegex, "")
            if (stripped.isBlank()) stripped else prefix + stripped
        }

        text.replace(start, end, rewritten)
        etBody.setSelection(start, start + rewritten.length)
    }

    /** Change the editor font size (visual only — not stored in the note). */
    private fun changeBodyTextSize(delta: Float) {
        bodyTextSizeSp = (bodyTextSizeSp + delta).coerceIn(10f, 32f)
        etBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodyTextSizeSp)
    }
}
