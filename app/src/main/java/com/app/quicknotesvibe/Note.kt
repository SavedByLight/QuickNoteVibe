package com.android.quicknotesvibe

import com.google.gson.annotations.SerializedName

data class Note(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String,
    var body: String,
    var sha: String? = null,      // GitHub blob SHA (needed to update/delete)
    var synced: Boolean = false
)

// --- GitHub API DTOs ---
data class GhPutRequest(
    val message: String,
    val content: String,          // Base64-encoded file contents
    @SerializedName("sha") val sha: String? = null // required when updating
)

data class GhPutResponse(val content: GhContent?)

data class GhContent(val sha: String?)