package org.tasks.backup

import android.content.Context
import android.net.Uri
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val sshDir = File(context.filesDir, SSH_DIR)
    private val keyFile = File(sshDir, KEY_FILENAME)

    val keyPath: File
        get() = keyFile

    val hasKey: Boolean
        get() = keyFile.exists() && keyFile.length() > 0

    val keyFileName: String?
        get() = if (hasKey) keyFile.name else null

    /**
     * Import an SSH private key from a SAF content URI.
     * Copies the key file to app internal storage and validates it.
     *
     * @return true if import succeeded
     */
    fun importKey(uri: Uri): Boolean {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Timber.e("Failed to open input stream for URI: $uri")
                return false
            }

            // Ensure directory exists
            if (!sshDir.exists()) {
                sshDir.mkdirs()
            }

            // Copy key to internal storage
            inputStream.use { input ->
                FileOutputStream(keyFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Validate the key
            return validateKey()
        } catch (e: Exception) {
            Timber.e(e, "Failed to import SSH key")
            // Clean up on failure
            keyFile.delete()
            return false
        }
    }

    /**
     * Validate that the imported key file is a valid SSH private key
     * by attempting to load it with JSch.
     */
    fun validateKey(): Boolean {
        if (!keyFile.exists()) return false
        return try {
            val jsch = JSch()
            KeyPair.load(jsch, keyFile.absolutePath)
            true
        } catch (e: Exception) {
            Timber.e(e, "SSH key validation failed")
            false
        }
    }

    /**
     * Delete the imported SSH key from internal storage.
     */
    fun deleteKey() {
        if (keyFile.exists()) {
            keyFile.delete()
        }
    }

    /**
     * Get key fingerprint for display purposes.
     */
    fun getKeyFingerprint(): String? {
        if (!hasKey) return null
        return try {
            val jsch = JSch()
            val kpair = KeyPair.load(jsch, keyFile.absolutePath)
            val fingerprint = kpair.fingerPrint
            kpair.dispose()
            fingerprint
        } catch (e: Exception) {
            Timber.e(e, "Failed to get key fingerprint")
            null
        }
    }

    /**
     * Get key type string (e.g., "RSA", "ED25519") for display.
     */
    fun getKeyType(): String? {
        if (!hasKey) return null
        return try {
            val jsch = JSch()
            val kpair = KeyPair.load(jsch, keyFile.absolutePath)
            val typeName = when (kpair.keyType) {
                KeyPair.RSA -> "RSA"
                KeyPair.DSA -> "DSA"
                KeyPair.ECDSA -> "ECDSA"
                KeyPair.ED25519 -> "ED25519"
                KeyPair.UNKNOWN -> "Unknown"
                else -> "Unknown"
            }
            kpair.dispose()
            typeName
        } catch (e: Exception) {
            Timber.e(e, "Failed to get key type")
            null
        }
    }

    companion object {
        private const val SSH_DIR = "ssh"
        private const val KEY_FILENAME = "private_key"
    }
}
