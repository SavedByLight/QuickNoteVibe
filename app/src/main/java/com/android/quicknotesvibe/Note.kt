package com.android.quicknotesvibe

import com.google.gson.annotations.SerializedName

data class Note(
    val id: String = java.util.UUID.randomUUID().toString(),
    var filename: String = "",   // GitHub basename under notes/ (no .md) — user-chosen
    var title: String,
    var body: String,
    var sha: String? = null,     // GitHub blob SHA (needed to update/delete)
    var synced: Boolean = false
) {
    /** Path used on GitHub, e.g. notes/my-note.md */
    fun githubPath(): String = "notes/${effectiveFilename()}.md"

    fun effectiveFilename(): String {
        val raw = filename.ifBlank { title }.ifBlank { id }
        return sanitizeFilename(raw)
    }

    companion object {
        /** Keep only safe path characters; collapse runs of dashes. */
        fun sanitizeFilename(raw: String): String {
            val cleaned = raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(100)
            return cleaned.ifBlank { "note" }
        }
    }
}

// --- GitHub API DTOs ---
data class GhPutRequest(
    val message: String,
    val content: String,          // Base64-encoded file contents
    @SerializedName("sha") val sha: String? = null // required when updating
)

data class GhPutResponse(val content: GhContent?)

data class GhContent(
    val name: String? = null,
    val path: String? = null,
    val sha: String? = null,
    val content: String? = null,  // Base64 (present when fetching a single file)
    val encoding: String? = null,
    @SerializedName("download_url") val downloadUrl: String? = null,
    val type: String? = null
)
