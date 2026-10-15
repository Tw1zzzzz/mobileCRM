package com.pulse.core.platform

/** Files under the app's private data folder; paths are relative and use '/'. */
interface FileSystem {
    fun readText(path: String): String?
    /** Atomic: written to a temp file first, then moved into place. */
    fun writeText(path: String, text: String)
    fun delete(path: String)
    fun deleteRecursively(path: String)
    fun exists(path: String): Boolean
    /** Names of the files directly inside `directory`. */
    fun list(directory: String): List<String>
    fun move(from: String, to: String)
}

/** Secure key–value storage (Keychain on iOS, Android Keystore-backed storage on Android). */
interface SecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** PBKDF2-HMAC-SHA256 (same parameters as iOS CommonCrypto). */
expect fun pbkdf2Sha256(password: String, salt: ByteArray, rounds: Int, length: Int): ByteArray

expect fun secureRandomBytes(count: Int): ByteArray
