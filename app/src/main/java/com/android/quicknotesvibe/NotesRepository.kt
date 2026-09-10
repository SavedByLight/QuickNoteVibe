package com.android.quicknotesvibe

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object NotesRepository {
    private const val FILE = "notes_cache.json"

    fun load(ctx: Context): MutableList<Note> {
        val raw = ctx.getSharedPreferences("store", 0)
            .getString(FILE, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val id = o.getString("id")
            Note(
                id = id,
                filename = o.optString("filename", "").ifBlank { id },
                title = o.getString("title"),
                body = o.getString("body"),
                sha = o.optString("sha", null).takeIf { s -> !s.isNullOrEmpty() },
                synced = o.optBoolean("synced")
            )
        }.toMutableList()
    }

    fun persist(ctx: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("filename", n.filename)
                put("title", n.title)
                put("body", n.body)
                put("sha", n.sha ?: "")
                put("synced", n.synced)
            })
        }
        ctx.getSharedPreferences("store", 0).edit()
            .putString(FILE, arr.toString()).apply()
    }

    /**
     * Save locally, then push to GitHub.
     * [previousFilename] is the basename previously stored on GitHub (for renames).
     */
    fun saveAndSync(
        scope: CoroutineScope,
        ctx: Context,
        note: Note,
        notes: MutableList<Note>,
        previousFilename: String? = null,
        onDone: () -> Unit
    ) {
        // Ensure a usable filename before persisting
        if (note.filename.isBlank()) {
            note.filename = Note.sanitizeFilename(note.title.ifBlank { note.id })
        } else {
            note.filename = Note.sanitizeFilename(note.filename)
        }

        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note else notes.add(note)
        persist(ctx, notes)
        onDone()

        val oldName = previousFilename
        scope.launch(Dispatchers.IO) {
            val ok = try {
                GitHubApi.saveNote(note, oldName)
            } catch (e: Exception) {
                android.util.Log.e("NotesRepository", "save failed", e)
                false
            }
            note.synced = ok
            persist(ctx, notes)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    Toast.makeText(ctx, "Saved locally — sync failed, will retry", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Synced to GitHub ✓", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Pull all notes from the GitHub `notes/` folder and merge into the local cache.
     * - Remote notes not present locally are added.
     * - Local notes that already exist remotely get title/body/sha/filename updated.
     * - Purely local (never synced) notes are left untouched.
     */
    fun pullFromGitHub(
        scope: CoroutineScope,
        ctx: Context,
        notes: MutableList<Note>,
        onFinished: (added: Int, updated: Int, error: String?) -> Unit
    ) {
        if (!Prefs.isConfigured()) {
            onFinished(0, 0, "Configure GitHub in settings first")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val files = GitHubApi.listNoteFiles()
                var added = 0
                var updated = 0

                for (file in files) {
                    val remote = GitHubApi.downloadNote(file) ?: continue
                    // Match by filename first, then by id (legacy UUID-named files)
                    val idx = notes.indexOfFirst {
                        it.filename == remote.filename || it.id == remote.id ||
                            it.effectiveFilename() == remote.filename
                    }
                    if (idx < 0) {
                        notes.add(remote)
                        added++
                    } else {
                        val local = notes[idx]
                        if (local.title != remote.title ||
                            local.body != remote.body ||
                            local.sha != remote.sha ||
                            local.filename != remote.filename
                        ) {
                            local.title = remote.title
                            local.body = remote.body
                            local.sha = remote.sha
                            local.filename = remote.filename
                            local.synced = true
                            updated++
                        } else {
                            local.synced = true
                        }
                    }
                }

                persist(ctx, notes)
                withContext(Dispatchers.Main) {
                    onFinished(added, updated, null)
                }
            } catch (e: Exception) {
                android.util.Log.e("NotesRepository", "pull failed", e)
                withContext(Dispatchers.Main) {
                    onFinished(0, 0, e.message ?: "Download failed")
                }
            }
        }
    }
}
