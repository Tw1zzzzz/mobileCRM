package com.pulse.core.platform

import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

actual fun pbkdf2Sha256(password: String, salt: ByteArray, rounds: Int, length: Int): ByteArray {
    // PBEKeySpec takes chars and encodes them as UTF-8 for HmacSHA256 — same bytes as iOS `password.utf8`.
    val spec = PBEKeySpec(password.toCharArray(), salt, rounds, length * 8)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

actual fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

/** java.io-based file system rooted at the app's private folder (Android `filesDir`, a temp dir in tests). */
class JvmFileSystem(private val root: File) : FileSystem {
    private fun file(path: String) = File(root, path)

    override fun readText(path: String): String? = file(path).takeIf { it.isFile }?.readText()

    override fun writeText(path: String, text: String) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot write $path" }
        }
    }

    override fun delete(path: String) {
        file(path).delete()
    }

    override fun deleteRecursively(path: String) {
        file(path).deleteRecursively()
    }

    override fun exists(path: String): Boolean = file(path).exists()

    override fun list(directory: String): List<String> = file(directory).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    override fun move(from: String, to: String) {
        val target = file(to)
        target.parentFile?.mkdirs()
        file(from).renameTo(target)
    }
}
