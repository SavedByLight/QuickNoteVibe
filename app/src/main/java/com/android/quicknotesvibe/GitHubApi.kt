package com.android.quicknotesvibe

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GitHubApi {
    private const val API = "https://api.github.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /** Fetch a single note file and return its sha (or null if it doesn't exist yet). */
    suspend fun getSha(path: String): String? {
        val req = baseReq("$API/repos/${Prefs.owner()}/${Prefs.repo()}/contents/$path")
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null // 404 => new file
            val json = resp.body!!.string()
            return gson.fromJson(json, GhContent::class.java)?.sha
        }
    }

    /**
     * List every `.md` file under the repo's `notes/` folder.
     * Returns empty list if the folder does not exist (404) or on network error.
     */
    suspend fun listNoteFiles(): List<GhContent> {
        val req = baseReq("$API/repos/${Prefs.owner()}/${Prefs.repo()}/contents/notes")
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return emptyList()
            if (!resp.isSuccessful) {
                android.util.Log.e("GitHubApi", "list notes failed ${resp.code}")
                return emptyList()
            }
            val json = resp.body!!.string()
            val type = object : TypeToken<List<GhContent>>() {}.type
            val items: List<GhContent> = gson.fromJson(json, type) ?: emptyList()
            return items.filter { it.type == "file" && (it.name?.endsWith(".md") == true) }
        }
    }

    /**
     * Download one note file and parse it into a [Note].
     * Expected markdown format: first line `# Title`, then blank line, then body.
     * File name is expected to be `{uuid}.md`.
     */
    suspend fun downloadNote(file: GhContent): Note? {
        val path = file.path ?: return null
        val req = baseReq("$API/repos/${Prefs.owner()}/${Prefs.repo()}/contents/$path")
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val meta = gson.fromJson(resp.body!!.string(), GhContent::class.java) ?: return null
            val b64 = meta.content?.replace("\n", "") ?: return null
            val text = String(Base64.decode(b64, Base64.DEFAULT))

            val lines = text.lines()
            val title = lines.firstOrNull()
                ?.removePrefix("#")
                ?.trim()
                .orEmpty()
            val body = when {
                lines.size <= 1 -> ""
                lines.getOrNull(1)?.isBlank() == true -> lines.drop(2).joinToString("\n")
                else -> lines.drop(1).joinToString("\n")
            }

            val id = file.name?.removeSuffix(".md")
                ?: java.util.UUID.randomUUID().toString()

            return Note(
                id = id,
                title = title,
                body = body,
                sha = meta.sha,
                synced = true
            )
        }
    }

    /** Create or update note.md in the repo. Returns true on success. */
    suspend fun saveNote(note: Note): Boolean {
        val path = "notes/${note.id}.md"
        val existingSha = getSha(path)

        val payload = GhPutRequest(
            message = "note: ${note.title}",
            content = Base64.encodeToString(
                "# ${note.title}\n\n${note.body}".toByteArray(), Base64.NO_WRAP
            ),
            sha = existingSha
        )
        val body = gson.toJson(payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = baseReq("$API/repos/${Prefs.owner()}/${Prefs.repo()}/contents/$path")
            .newBuilder().put(body).build()

        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                gson.fromJson(resp.body!!.string(), GhPutResponse::class.java)
                    ?.content?.sha?.let { note.sha = it }
                return true
            }
            android.util.Log.e("GitHubApi", "PUT failed ${resp.code}: ${resp.message}")
            return false
        }
    }

    suspend fun deleteNote(note: Note): Boolean {
        val sha = note.sha ?: getSha("notes/${note.id}.md") ?: return true
        val payload = """{"message":"note deleted","sha":"$sha"}"""
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "$API/repos/${Prefs.owner()}/${Prefs.repo()}/contents/notes/${note.id}.md"
        client.newCall(baseReq(url).newBuilder().delete(body).build()).execute().use {
            return it.isSuccessful || it.code == 404
        }
    }

    private fun baseReq(url: String): Request =
        Request.Builder().url(url)
            .header("Authorization", "Bearer ${Prefs.token()}")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GitNotes-Android")
            .build()
}
