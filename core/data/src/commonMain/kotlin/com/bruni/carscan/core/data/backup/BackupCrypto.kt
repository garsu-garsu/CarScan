package com.bruni.carscan.core.data.backup

/**
 * The four primitives a backup file needs, bound per platform.
 *
 * There is no multiplatform crypto in the Kotlin stdlib and nothing on this project's classpath
 * provides AES — Ktor ships an HTTP client, not a cipher suite. Rather than add a crypto
 * dependency for four calls, each platform uses the one it already has: `javax.crypto` on
 * Android (both primitives are in the platform API at minSdk 26), CryptoKit on iOS when Apple
 * targets are ever built. This is the same `expect`/`actual` shape as `:feature:hud`'s display
 * control.
 *
 * **No cipher is implemented here.** These are thin adapters over the platform's AES-GCM and
 * PBKDF2; the only thing this project decides is how they are parameterised.
 */

/** Cryptographically secure random bytes — the file's salt. Never a plain PRNG. */
expect fun randomBytes(size: Int): ByteArray

/**
 * PBKDF2-HMAC-SHA256, [iterations] rounds, 256-bit output.
 *
 * A KDF and not a hash of the password: the file is offline and attackable at whatever rate the
 * attacker's hardware allows, so the work factor *is* the protection. Argon2id would resist GPUs
 * better, but every KMP Argon2 is a third-party dependency, and PBKDF2 at 210k rounds is the
 * strongest thing both platforms ship in the box.
 */
expect fun deriveBackupKey(password: String, salt: ByteArray, iterations: Int): ByteArray

/** AES-256-GCM. [aad] is authenticated but not encrypted; the tag is appended to the output. */
expect fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

/**
 * The inverse of [aesGcmSeal], returning **null when authentication fails** rather than throwing.
 *
 * That is the whole wrong-password story: GCM verifies the tag before yielding a byte, so a bad
 * key cannot produce plausible-looking garbage — it produces nothing, and the caller can say so.
 */
expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?
