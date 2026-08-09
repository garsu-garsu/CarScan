package com.bruni.carscan.core.data.backup

/**
 * **Never compiled.** Apple targets are only registered on a macOS host — see the note on
 * `:core:units`'s iOS `NumberFormatter` actual and `:feature:hud`'s `HudDisplayEffect`.
 *
 * These throw rather than return plausible bytes, deliberately. A stub that "worked" would
 * write a backup file nothing can ever decrypt, and the user would not find out until the day
 * they needed it. Failing loudly on the first call is the only honest placeholder for a cipher.
 *
 * The real implementation is CryptoKit: `SymmetricKey` + `AES.GCM.seal/open` for the cipher, and
 * PBKDF2 via `CCKeyDerivationPBKDF` from CommonCrypto (CryptoKit has no password-based KDF).
 * `SecRandomCopyBytes` covers [randomBytes]. It cannot be written blind — none of it can be
 * compiled, let alone run, without a macOS host.
 */
private const val WHY = "iOS backup crypto is not implemented — see BackupCrypto.ios.kt"

actual fun randomBytes(size: Int): ByteArray = TODO(WHY)

actual fun deriveBackupKey(password: String, salt: ByteArray, iterations: Int): ByteArray = TODO(WHY)

actual fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plaintext: ByteArray,
): ByteArray = TODO(WHY)

actual fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    ciphertext: ByteArray,
): ByteArray? = TODO(WHY)
