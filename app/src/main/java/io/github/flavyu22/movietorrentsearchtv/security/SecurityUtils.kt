package io.github.flavyu22.movietorrentsearchtv.security

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * Local access control for the application.
 *
 * This is deliberately described as local access control, not remote authentication:
 * no credential leaves the device and there is no server-side identity. Only a random
 * salt and a PBKDF2 verifier are persisted. The secret itself is never stored. While a
 * salted authenticated-session flag may be persisted on the device (so a cold start does
 * not re-lock an already-unlocked profile), it holds no secret material and is cleared
 * when the user logs out manually.
 */
object SecurityUtils {
    private const val AUTH_PREFS_FILE = "local_auth_v3"
    private const val LEGACY_PREFS_FILE = "secure_prefs_v2"

    private const val KEY_VERSION = "credential_version"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_SALT = "credential_salt"
    private const val KEY_VERIFIER = "credential_verifier"
    private const val KEY_ALGORITHM = "credential_algorithm"
    private const val KEY_ITERATIONS = "credential_iterations"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKED_UNTIL = "locked_until_epoch_ms"
    private const val KEY_LEGACY_REMOVED = "legacy_credentials_removed"

    private const val CREDENTIAL_VERSION = 1
    private const val PBKDF2_ITERATIONS = 310_000
    private const val DERIVED_KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val MIN_SECRET_LENGTH = 6
    private const val MAX_SECRET_LENGTH = 128
    private const val MAX_PROFILE_LENGTH = 64
    private const val MAX_LOCKOUT_MS = 5 * 60 * 1000L

    sealed interface AuthenticationResult {
        data class Success(val credentialCreated: Boolean) : AuthenticationResult
        data class InvalidCredential(val retryAfterMs: Long) : AuthenticationResult
        data class Locked(val retryAfterMs: Long) : AuthenticationResult
        data object InvalidInput : AuthenticationResult
        data object CorruptConfiguration : AuthenticationResult
    }

    /** Returns private preferences containing verifier material only, never a password. */
    fun getAuthPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(AUTH_PREFS_FILE, Context.MODE_PRIVATE)

        // The previous implementation persisted a username and password. It cannot be
        // migrated safely, so remove it once and require a new local credential.
        if (!preferences.getBoolean(KEY_LEGACY_REMOVED, false)) {
            appContext.deleteSharedPreferences(LEGACY_PREFS_FILE)
            // The in-memory value changes immediately; persisting asynchronously avoids
            // blocking the first Activity frame on a one-time migration marker write.
            preferences.edit().putBoolean(KEY_LEGACY_REMOVED, true).apply()
        }
        return preferences
    }

    fun isCredentialConfigured(preferences: SharedPreferences): Boolean =
        preferences.getInt(KEY_VERSION, 0) == CREDENTIAL_VERSION &&
            !preferences.getString(KEY_PROFILE_NAME, null).isNullOrBlank() &&
            !preferences.getString(KEY_SALT, null).isNullOrBlank() &&
            !preferences.getString(KEY_VERIFIER, null).isNullOrBlank() &&
            !preferences.getString(KEY_ALGORITHM, null).isNullOrBlank() &&
            preferences.getInt(KEY_ITERATIONS, 0) > 0

    fun savedProfileName(preferences: SharedPreferences): String =
        preferences.getString(KEY_PROFILE_NAME, "").orEmpty()

    /** Explicit recovery path used only after the UI reports corrupt verifier state. */
    fun resetLocalCredential(preferences: SharedPreferences): Boolean = synchronized(this) {
        preferences.edit()
            .clear()
            .putBoolean(KEY_LEGACY_REMOVED, true)
            .commit()
    }

    fun remainingLockoutMs(
        preferences: SharedPreferences,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Long = (preferences.getLong(KEY_LOCKED_UNTIL, 0L) - nowEpochMs).coerceAtLeast(0L)

    /**
     * Creates the first local credential, or authenticates against the existing one.
     * Creation and verification are serialized so two rapid submissions cannot overwrite
     * one another or bypass the attempt counter.
     */
    fun authenticateOrCreate(
        preferences: SharedPreferences,
        profileName: String,
        secret: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AuthenticationResult = synchronized(this) {
        val normalizedProfile = normalizeProfile(profileName)
        if (!isValidInput(normalizedProfile, secret)) {
            return@synchronized AuthenticationResult.InvalidInput
        }

        if (!isCredentialConfigured(preferences)) {
            if (hasPartialCredential(preferences)) {
                return@synchronized AuthenticationResult.CorruptConfiguration
            }
            return@synchronized createCredential(preferences, normalizedProfile, secret)
        }

        val remaining = remainingLockoutMs(preferences, nowEpochMs)
        if (remaining > 0L) {
            return@synchronized AuthenticationResult.Locked(remaining)
        }

        val salt = preferences.getString(KEY_SALT, null)?.hexToBytes()
        val expected = preferences.getString(KEY_VERIFIER, null)?.hexToBytes()
        val algorithm = preferences.getString(KEY_ALGORITHM, null)
        val iterations = preferences.getInt(KEY_ITERATIONS, 0)
        if (salt == null || expected == null || algorithm.isNullOrBlank() || iterations <= 0) {
            return@synchronized AuthenticationResult.CorruptConfiguration
        }

        val actual = runCatching {
            deriveVerifier(normalizedProfile, secret, salt, algorithm, iterations)
        }.getOrElse { return@synchronized AuthenticationResult.CorruptConfiguration }

        if (MessageDigest.isEqual(actual, expected)) {
            preferences.edit()
                .remove(KEY_FAILED_ATTEMPTS)
                .remove(KEY_LOCKED_UNTIL)
                .commit()
            return@synchronized AuthenticationResult.Success(credentialCreated = false)
        }

        val failedAttempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0)
            .coerceAtLeast(0) + 1
        val delayMs = lockoutForAttempt(failedAttempts)
        preferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
            .putLong(KEY_LOCKED_UNTIL, nowEpochMs + delayMs)
            .commit()
        AuthenticationResult.InvalidCredential(delayMs)
    }

    private fun createCredential(
        preferences: SharedPreferences,
        normalizedProfile: String,
        secret: String,
    ): AuthenticationResult {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val algorithm = preferredPbkdf2Algorithm()
        val verifier = runCatching {
            deriveVerifier(normalizedProfile, secret, salt, algorithm, PBKDF2_ITERATIONS)
        }.getOrElse { return AuthenticationResult.CorruptConfiguration }

        val saved = preferences.edit()
            .putInt(KEY_VERSION, CREDENTIAL_VERSION)
            .putString(KEY_PROFILE_NAME, normalizedProfile)
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_VERIFIER, verifier.toHex())
            .putString(KEY_ALGORITHM, algorithm)
            .putInt(KEY_ITERATIONS, PBKDF2_ITERATIONS)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKED_UNTIL)
            .commit()

        return if (saved) AuthenticationResult.Success(credentialCreated = true)
        else AuthenticationResult.CorruptConfiguration
    }

    private fun deriveVerifier(
        normalizedProfile: String,
        secret: String,
        salt: ByteArray,
        algorithm: String,
        iterations: Int,
    ): ByteArray {
        // Binding the public profile name into the verifier prevents a different name from
        // authenticating with the same secret, while the final comparison stays constant-time.
        val input = "$normalizedProfile\u0000$secret".toCharArray()
        val spec = PBEKeySpec(input, salt, iterations, DERIVED_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            input.fill('\u0000')
        }
    }

    private fun preferredPbkdf2Algorithm(): String = runCatching {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        "PBKDF2WithHmacSHA256"
    }.getOrDefault("PBKDF2WithHmacSHA1")

    private fun normalizeProfile(profileName: String): String =
        profileName.trim().lowercase(Locale.ROOT)

    private fun isValidInput(normalizedProfile: String, secret: String): Boolean =
        normalizedProfile.isNotBlank() &&
            normalizedProfile.length <= MAX_PROFILE_LENGTH &&
            normalizedProfile.none(Char::isISOControl) &&
            secret.length in MIN_SECRET_LENGTH..MAX_SECRET_LENGTH &&
            secret.none(Char::isISOControl)

    private fun hasPartialCredential(preferences: SharedPreferences): Boolean =
        preferences.contains(KEY_VERSION) ||
            preferences.contains(KEY_PROFILE_NAME) ||
            preferences.contains(KEY_SALT) ||
            preferences.contains(KEY_VERIFIER) ||
            preferences.contains(KEY_ALGORITHM) ||
            preferences.contains(KEY_ITERATIONS)

    private fun lockoutForAttempt(failedAttempts: Int): Long {
        if (failedAttempts < 3) return 0L
        val exponent = (failedAttempts - 3).coerceIn(0, 18)
        return min(1_000L shl exponent, MAX_LOCKOUT_MS)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0 || !matches(Regex("[0-9a-fA-F]+"))) return null
        return runCatching {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}

/** Strict parser used before an external magnet is allowed into application state. */
object MagnetLinkValidator {
    const val MAX_MAGNET_LENGTH = 4_096
    private const val MAX_QUERY_PARTS = 64
    private const val MAX_TRACKERS = 32
    private val infoHash = Regex("(?i)^urn:btih:(?:[a-f0-9]{40}|[a-z2-7]{32})$")

    fun validate(link: String): String? {
        val candidate = link.trim()
        if (candidate.length !in 1..MAX_MAGNET_LENGTH ||
            candidate.any { it.isWhitespace() || it.isISOControl() } ||
            !candidate.startsWith("magnet:?", ignoreCase = true) ||
            candidate.indexOf('#') >= 0
        ) {
            return null
        }

        // Magnet URIs are opaque (rather than hierarchical) according to both Android's
        // Uri and java.net.URI. Their query helpers therefore either throw or return no
        // query. Parse only the well-defined scheme-specific query form ourselves.
        val encodedQuery = candidate.substring("magnet:?".length)
        val parts = encodedQuery.split('&')
        if (parts.size > MAX_QUERY_PARTS || parts.any { it.isBlank() || it.length > 1_024 }) return null

        val parameters = ArrayList<Pair<String, String>>(parts.size)
        for (part in parts) {
            val separator = part.indexOf('=')
            if (separator !in 1 until part.length) return null
            val name = decodeQueryComponent(part.substring(0, separator)) ?: return null
            val value = decodeQueryComponent(part.substring(separator + 1)) ?: return null
            if (name.length !in 1..32 ||
                name.any { it.isWhitespace() || it.isISOControl() } ||
                value.any(Char::isISOControl)
            ) return null
            parameters += name to value
        }

        val exactTopics = parameters
            .filter { it.first.equals("xt", ignoreCase = true) }
            .map { it.second }
        if (exactTopics.size != 1 || !infoHash.matches(exactTopics.single())) return null
        if (parameters.count { it.first.equals("tr", ignoreCase = true) } > MAX_TRACKERS) {
            return null
        }

        return candidate
    }

    /** Percent-decodes without accepting malformed escapes or invalid UTF-8. */
    private fun decodeQueryComponent(encoded: String): String? {
        val bytes = ByteArrayOutputStream(encoded.length)
        var index = 0
        while (index < encoded.length) {
            when (val character = encoded[index]) {
                '%' -> {
                    if (index + 2 >= encoded.length) return null
                    val high = encoded[index + 1].digitToIntOrNull(16) ?: return null
                    val low = encoded[index + 2].digitToIntOrNull(16) ?: return null
                    bytes.write((high shl 4) or low)
                    index += 3
                }
                '+' -> {
                    bytes.write(' '.code)
                    index++
                }
                else -> {
                    val charCount = when {
                        character.isHighSurrogate() &&
                            index + 1 < encoded.length &&
                            encoded[index + 1].isLowSurrogate() -> 2
                        character.isSurrogate() -> return null
                        else -> 1
                    }
                    bytes.write(
                        encoded.substring(index, index + charCount)
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                    index += charCount
                }
            }
        }

        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }.getOrNull()
    }
}
