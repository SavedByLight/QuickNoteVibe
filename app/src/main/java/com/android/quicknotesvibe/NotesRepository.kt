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
            Note(
                o.getString("id"),
                o.getString("title"),
                o.getString("body"),
                o.optString("sha", null).takeIf { s -> !s.isNullOrEmpty() },
                o.optBoolean("synced")
            )
        }.toMutableList()
    }

    fun persist(ctx: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("title", n.title)
                put("body", n.body)
                put("sha", n.sha ?: "")
                put("synced", n.synced)
            })
        }
        ctx.getSharedPreferences("store", 0).edit()
            .putString(FILE, arr.toString()).apply()
    }

    /** Save locally, then push to GitHub on a background thread. */
    fun saveAndSync(
        scope: CoroutineScope,
        ctx: Context,
        note: Note,
        notes: MutableList<Note>,
        onDone: () -> Unit
    ) {
        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note else notes.add(note)
        persist(ctx, notes)
        onDone()

        scope.launch(Dispatchers.IO) {
            val ok = try {
                GitHubApi.saveNote(note)
            } catch (e: Exception) {
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
     * - Local notes that already exist remotely get title/body/sha updated from GitHub
     *   (remote wins so you can restore after reinstall).
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
                    val idx = notes.indexOfFirst { it.id == remote.id }
                    if (idx < 0) {
                        notes.add(remote)
                        added++
                    } else {
                        val local = notes[idx]
                        if (local.title != remote.title ||
                            local.body != remote.body ||
                            local.sha != remote.sha
                        ) {
                            local.title = remote.title
                            local.body = remote.body
                            local.sha = remote.sha
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
