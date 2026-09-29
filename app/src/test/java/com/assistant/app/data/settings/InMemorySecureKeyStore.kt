package com.assistant.app.data.settings

/**
 * Test double for [SecureKeyStore]: keeps keys in memory. Used because
 * [EncryptedSecureKeyStore] needs the AndroidKeyStore, which does not exist
 * under Robolectric.
 */
class InMemorySecureKeyStore : SecureKeyStore {

    private val keys = HashMap<Long, String?>()

    override fun apiKey(id: Long): String? = keys[id]

    override fun setApiKey(id: Long, value: String?) {
        if (value == null) keys.remove(id) else keys[id] = value
    }

    private var legacyKey: String? = null

    override fun legacyApiKey(): String? = legacyKey

    override fun deleteLegacyApiKey() {
        legacyKey = null
    }

    /** Seeds the legacy single-provider key, for migration tests. */
    fun setLegacyApiKey(value: String?) {
        legacyKey = value
    }
}
