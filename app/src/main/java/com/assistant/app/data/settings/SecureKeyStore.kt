package com.assistant.app.data.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Storage for API keys, one slot per provider id. An interface for one reason:
 * EncryptedSharedPreferences needs the hardware AndroidKeyStore, which does not
 * exist under Robolectric, so tests supply an in-memory implementation.
 */
interface SecureKeyStore {

    fun apiKey(id: Long): String?

    fun setApiKey(id: Long, value: String?)

    /** The pre-1.2 single-provider key, read once for seeding. */
    fun legacyApiKey(): String?

    fun deleteLegacyApiKey()
}

/**
 * [SecureKeyStore] backed by [EncryptedSharedPreferences] with an
 * AndroidKeyStore master key (AES256-GCM). Keys never appear in DataStore, in
 * backups, or in source; a device restore cannot decrypt them, which is why
 * the app disables backup.
 *
 * Corruption recovery: an unreadable prefs file is deleted and creation
 * retried once; if that also fails the store degrades to a permanent empty
 * state — [apiKey] returns null and [setApiKey] is a safe no-op — so the user
 * can re-enter keys instead of the app crashing on every access.
 *
 * The prefs file is built lazily: the first access generates the Keystore
 * master key, which must not happen during app startup.
 */
class EncryptedSecureKeyStore(context: Context) : SecureKeyStore {

    private val appContext = context.applicationContext

    /** Null when the storage is permanently unavailable. */
    private val preferences: SharedPreferences? by lazy {
        createWithRecovery(::createEncrypted)
    }

    override fun apiKey(id: Long): String? = preferences?.getString(keyFor(id), null)

    /**
     * Writes are committed synchronously. Losing an API key to a process death
     * between the write and the async disk flush would leave the user with a
     * provider that cannot authenticate and no way to tell why.
     */
    @SuppressLint("ApplySharedPref")
    override fun setApiKey(id: Long, value: String?) {
        val prefs = preferences ?: return
        prefs.edit().apply {
            if (value == null) remove(keyFor(id)) else putString(keyFor(id), value)
        }.commit()
    }

    override fun legacyApiKey(): String? = preferences?.getString(KEY_API_KEY, null)

    @SuppressLint("ApplySharedPref")
    override fun deleteLegacyApiKey() {
        preferences?.edit()?.remove(KEY_API_KEY)?.commit()
    }

    private fun keyFor(id: Long): String = "${KEY_API_KEY}_$id"

    /**
     * Creates the preferences, tolerating corruption: on failure the (corrupt)
     * file is deleted and creation retried once; null means stay disabled.
     * Unknown failures are rethrown — only [GeneralSecurityException] and
     * [IOException] count as corruption.
     */
    internal fun createWithRecovery(create: () -> SharedPreferences): SharedPreferences? =
        try {
            create()
        } catch (e: Exception) {
            if (e is GeneralSecurityException || e is IOException) {
                resetAndRetry(e, create)
            } else {
                throw e
            }
        }

    private fun resetAndRetry(first: Exception, create: () -> SharedPreferences): SharedPreferences? {
        // Log only the exception class name; its text can echo storage paths.
        Log.w(TAG, "Encrypted preferences unreadable (${first.javaClass.simpleName}); resetting")
        prefsFile().delete()
        return try {
            create()
        } catch (e: Exception) {
            if (e is GeneralSecurityException || e is IOException) {
                Log.w(TAG, "Encrypted preferences unavailable (${e.javaClass.simpleName}); disabled until next launch")
                null
            } else {
                throw e
            }
        }
    }

    private fun createEncrypted(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** On-disk location of the preferences file, for corruption recovery. */
    internal fun prefsFile(): File =        File(appContext.applicationInfo.dataDir, "shared_prefs/$PREFS_FILE.xml")

    private companion object {
        const val TAG = "EncryptedKeyStore"
        const val PREFS_FILE = "provider_secure_prefs"
        const val KEY_API_KEY = "api_key"
    }
}
