package com.android.quicknotesvibe

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private val sp by lazy {
        val ctx = App.context
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx, "gitnotes_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun token() = sp.getString("token", "") ?: ""
    fun owner() = sp.getString("owner", "") ?: ""
    fun repo() = sp.getString("repo", "") ?: ""

    fun save(token: String, owner: String, repo: String) {
        sp.edit()
            .putString("token", token.trim())
            .putString("owner", owner.trim())
            .putString("repo", repo.trim())
            .apply()
    }

    fun isConfigured() = token().isNotEmpty() && owner().isNotEmpty() && repo().isNotEmpty()
}