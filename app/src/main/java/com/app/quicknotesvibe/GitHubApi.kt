package com.android.quicknotesvibe

import android.util.Base64
import com.google.gson.Gson
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
                // Persist the new sha so future edits update instead of fail
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