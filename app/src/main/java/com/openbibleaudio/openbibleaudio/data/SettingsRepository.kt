package com.openbibleaudio.openbibleaudio.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.openbibleaudio.openbibleaudio.playback.ContinuousPlayScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** User settings (spec 0002 §5.2). */
data class Settings(
    val continuousPlayScope: ContinuousPlayScope = ContinuousPlayScope.BOOK,
    val bibleSpeed: Float = 1.0f,
    val seekBackMs: Long = 15_000,
    val seekForwardMs: Long = 30_000,
    val verifyHashesOnImport: Boolean = true,
)

class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            continuousPlayScope = p[SCOPE]?.let { runCatching { ContinuousPlayScope.valueOf(it) }.getOrNull() } ?: ContinuousPlayScope.BOOK,
            bibleSpeed = p[SPEED] ?: 1.0f,
            seekBackMs = p[SEEK_BACK] ?: 15_000,
            seekForwardMs = p[SEEK_FORWARD] ?: 30_000,
            verifyHashesOnImport = p[VERIFY_HASHES] ?: true,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setContinuousPlayScope(scope: ContinuousPlayScope) = store.edit { it[SCOPE] = scope.name }
    suspend fun setBibleSpeed(speed: Float) = store.edit { it[SPEED] = speed }
    suspend fun setSeekBackMs(ms: Long) = store.edit { it[SEEK_BACK] = ms }
    suspend fun setSeekForwardMs(ms: Long) = store.edit { it[SEEK_FORWARD] = ms }
    suspend fun setVerifyHashesOnImport(verify: Boolean) = store.edit { it[VERIFY_HASHES] = verify }

    private companion object {
        val SCOPE = stringPreferencesKey("continuousPlayScope")
        val SPEED = floatPreferencesKey("bibleSpeed")
        val SEEK_BACK = longPreferencesKey("seekBackMs")
        val SEEK_FORWARD = longPreferencesKey("seekForwardMs")
        val VERIFY_HASHES = booleanPreferencesKey("verifyHashesOnImport")
    }
}

/** The chapter being played and where, for playback resumption (AD-2, spec 0002 §5.2). */
data class ResumptionState(val mediaId: String, val positionMs: Long, val updatedAt: Long)

class ResumptionStore(private val store: DataStore<Preferences>) {
    suspend fun get(): ResumptionState? {
        val p = store.data.first()
        val id = p[MEDIA_ID] ?: return null
        return ResumptionState(id, p[POSITION] ?: 0, p[UPDATED_AT] ?: 0)
    }

    suspend fun save(state: ResumptionState) = store.edit {
        it[MEDIA_ID] = state.mediaId
        it[POSITION] = state.positionMs
        it[UPDATED_AT] = state.updatedAt
    }

    suspend fun clear() = store.edit { it.clear() }

    private companion object {
        val MEDIA_ID = stringPreferencesKey("mediaId")
        val POSITION = longPreferencesKey("positionMs")
        val UPDATED_AT = longPreferencesKey("updatedAt")
    }
}
