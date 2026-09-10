package com.android.quicknotesvibe

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private lateinit var notes: MutableList<Note>
    private val scope = kotlinx.coroutines.MainScope()
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.btnSync).setOnClickListener {
            pullNotes()
        }

        findViewById<android.view.View>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }

        notes = NotesRepository.load(this)
        adapter = NotesAdapter()
        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        notes = NotesRepository.load(this)
        adapter.notifyDataSetChanged()
    }

    private fun pullNotes() {
        if (!Prefs.isConfigured()) {
            Toast.makeText(this, "Set up GitHub in settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        Toast.makeText(this, "Downloading notes…", Toast.LENGTH_SHORT).show()
        NotesRepository.pullFromGitHub(scope, this, notes) { added, updated, error ->
            if (error != null) {
                Toast.makeText(this, "Download failed: $error", Toast.LENGTH_LONG).show()
            } else {
                notes = NotesRepository.load(this)
                adapter.notifyDataSetChanged()
                val msg = when {
                    added == 0 && updated == 0 -> "Already up to date"
                    else -> "Downloaded: $added new, $updated updated"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renameNote(note: Note) {
        val input = EditText(this).apply {
            setText(note.title)
            setSelection(text.length)
            hint = "Note title"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename note")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle == note.title) return@setPositiveButton
                note.title = newTitle
                note.synced = false
                NotesRepository.saveAndSync(scope, this, note, notes) {
                    adapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class NotesAdapter : RecyclerView.Adapter<NotesAdapter.VH>() {
        inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(android.R.id.text1)
            val sub: TextView = v.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(
                LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_2, parent, false)
            )

        override fun getItemCount() = notes.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val n = notes[pos]
            h.title.text = n.title.ifBlank { "(untitled)" }
            h.sub.text = if (n.synced) "Synced ✓" else "Pending sync…"
            h.itemView.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, NoteEditorActivity::class.java)
                        .putExtra("note_id", n.id)
                )
            }
            h.itemView.setOnLongClickListener {
                renameNote(n)
                true
            }
        }
    }
}
