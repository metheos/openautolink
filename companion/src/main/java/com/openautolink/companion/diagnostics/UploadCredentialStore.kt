package com.openautolink.companion.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.openautolink.companion.CompanionPrefs

/** Migration decision kept pure so credential movement is unit-testable. */
enum class TokenMigration {
    COPY_AND_REMOVE_LEGACY,
    REMOVE_LEGACY_ONLY,
    NONE;

    companion object {
        fun plan(secretToken: String, legacyToken: String): TokenMigration = when {
            secretToken.isBlank() && legacyToken.isNotBlank() -> COPY_AND_REMOVE_LEGACY
            legacyToken.isNotBlank() -> REMOVE_LEGACY_ONLY
            else -> NONE
        }
    }
}

/** Stores the upload invitation token outside the backed-up settings file. */
object UploadCredentialStore {
    const val FILE_NAME = "OalUploadCredentials"
    private const val TOKEN_KEY = "upload_token"

    @SuppressLint("ApplySharedPref") // Security migration must be durable before legacy removal.
    @Synchronized
    private fun migrate(mainPrefs: SharedPreferences, secretPrefs: SharedPreferences) {
        val secret = secretPrefs.getString(TOKEN_KEY, "").orEmpty()
        val legacy = mainPrefs.getString(CompanionPrefs.LOG_UPLOAD_TOKEN, "").orEmpty()
        when (TokenMigration.plan(secret, legacy)) {
            TokenMigration.COPY_AND_REMOVE_LEGACY -> {
                val copied = secretPrefs.edit().putString(TOKEN_KEY, legacy).commit()
                if (copied) {
                    mainPrefs.edit().remove(CompanionPrefs.LOG_UPLOAD_TOKEN).commit()
                }
            }
            TokenMigration.REMOVE_LEGACY_ONLY ->
                mainPrefs.edit().remove(CompanionPrefs.LOG_UPLOAD_TOKEN).commit()
            TokenMigration.NONE -> Unit
        }
    }

    fun read(context: Context, mainPrefs: SharedPreferences): String {
        val secretPrefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        migrate(mainPrefs, secretPrefs)
        return secretPrefs.getString(TOKEN_KEY, "").orEmpty()
    }

    @SuppressLint("ApplySharedPref") // UI must not claim a token that was not durably stored.
    fun write(context: Context, mainPrefs: SharedPreferences, token: String): Boolean {
        val secretPrefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val written = secretPrefs.edit().putString(TOKEN_KEY, token.trim()).commit()
        if (written) {
            mainPrefs.edit().remove(CompanionPrefs.LOG_UPLOAD_TOKEN).commit()
        }
        return written
    }
}
