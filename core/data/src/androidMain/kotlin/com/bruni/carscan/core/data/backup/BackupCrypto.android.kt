package com.bruni.carscan.core.data.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val GCM_TAG_BITS = 128

actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

/**
 * `PBKDF2WithHmacSHA256` has been in the platform since API 26, which is this app's minSdk — so
 * there is no fallback branch here on purpose. It rejects an empty password outright, which the
 * caller has already ruled out; see `BackupService`.
 */
actual fun deriveBackupKey(password: String, salt: ByteArray, iterations: Int): ByteArray =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
        .encoded

actual fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plaintext: ByteArray,
): ByteArray = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

actual fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    ciphertext: ByteArray,
): ByteArray? = try {
    gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
} catch (_: AEADBadTagException) {
    // The wrong password, or a file somebody edited. Both are "we cannot read this", and
    // neither is an error worth a stack trace.
    null
} catch (_: javax.crypto.IllegalBlockSizeException) {
    // A truncated final frame is shorter than the tag. Same answer.
    null
}

private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
    }
