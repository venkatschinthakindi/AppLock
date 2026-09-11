package applock.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed encrypted preference store.
 *
 * Plaintext secrets are never stored in normal preferences and are never logged.
 * A missing value returns null. A malformed/corrupt value or Keystore failure throws
 * [SecureStorageException] so callers cannot mistake a security failure for an
 * unconfigured credential.
 */
class SecureStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("secure_values", Context.MODE_PRIVATE)
    private val alias = "AppLockSecretKeyV1"

    class SecureStorageException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    private fun key(): SecretKey {
        return try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKey()
        } catch (e: Exception) {
            throw SecureStorageException("Unable to access the AppLock Keystore key", e)
        }
    }

    fun write(name: String, value: String) {
        require(name.isNotBlank()) { "Storage key must not be blank" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ciphertext = Base64.encodeToString(
                cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
                Base64.NO_WRAP
            )
            if (!prefs.edit().putString(name, "$iv:$ciphertext").commit()) {
                throw SecureStorageException("Unable to persist encrypted value")
            }
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: Exception) {
            throw SecureStorageException("Unable to encrypt value", e)
        }
    }

    fun read(name: String): String? {
        val packed = prefs.getString(name, null) ?: return null
        val parts = packed.split(':', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw SecureStorageException("Encrypted value is malformed")
        }

        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            if (iv.size != 12) {
                throw SecureStorageException("Encrypted value has an invalid IV")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, iv)
            )
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: AEADBadTagException) {
            throw SecureStorageException("Encrypted value failed authentication", e)
        } catch (e: BadPaddingException) {
            throw SecureStorageException("Encrypted value could not be decrypted", e)
        } catch (e: Exception) {
            throw SecureStorageException("Unable to decrypt value", e)
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }
}
