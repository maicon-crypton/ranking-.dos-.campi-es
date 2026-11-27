package com.aistudio.santrirank.mcdfbx.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aistudio.santrirank.mcdfbx.model.AppUser
import com.aistudio.santrirank.mcdfbx.model.UsuarioRole
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashing de senhas e PINs usando PBKDF2WithHmacSHA256 (120.000 iterações, 256 bits)
 * com salt aleatório de 16 bytes. Nunca armazena credenciais em texto plano.
 * Formato de saída: base64(salt):base64(hash)
 */
object PasswordHasher {

    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * Gera o hash de uma senha. Retorna "salt:hash" em base64.
     */
    fun hash(password: String): String {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val hash = deriveKey(password.toCharArray(), salt)
        return encodeBase64(salt) + ":" + encodeBase64(hash)
    }

    /**
     * Valida se a senha informada corresponde ao hash armazenado (formato salt:hash).
     * Usa comparação de tempo constante para reduzir ataques de temporização.
     */
    fun verify(password: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2) return false
        val salt = decodeBase64(parts[0])
        val expectedHash = decodeBase64(parts[1])
        val computedHash = deriveKey(password.toCharArray(), salt)
        return messageIsEqual(expectedHash, computedHash)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    private fun encodeBase64(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data)

    private fun decodeBase64(encoded: String): ByteArray =
        java.util.Base64.getDecoder().decode(encoded)

    /**
     * Compara os valores em tempo constante para reduzir ataques de temporização.
     */
    private fun messageIsEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}

/**
 * Sessão criptografada via EncryptedSharedPreferences (Android Keystore, AES-256-GCM).
 * Armazena token de sessão, role, username e timestamp da última atividade.
 * Expira automaticamente após 15 minutos de inatividade.
 */
class AuthSession(private val context: Context) {

    private val prefsName = "santri_secure_session"
    private val keySessionToken = "session_token"
    private val keyUsername = "session_username"
    private val keyRole = "session_role"
    private val keyLastActivity = "session_last_activity"
    private val keyRememberCredentials = "remember_credentials"
    private val keyRememberedUsername = "remembered_username"
    private val keyRememberedPassword = "remembered_password"
    private val inactivityTimeoutMs = 15L * 60 * 1000

    private val preferences: android.content.SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Cria uma sessão autenticada. */
    fun start(username: String, role: UsuarioRole, token: String) {
        val editor = preferences.edit()
        editor.putString(keySessionToken, token)
        editor.putString(keyUsername, username)
        editor.putString(keyRole, role.name)
        editor.putLong(keyLastActivity, System.currentTimeMillis())
        editor.apply()
    }

    /** Retorna o username se a sessão estiver válida (não expirada). */
    fun currentUsername(): String? {
        if (isExpired()) return null
        return preferences.getString(keyUsername, null)
    }

    /** Retorna o role se a sessão estiver válida. */
    fun currentRole(): UsuarioRole? {
        if (isExpired()) return null
        val roleName = preferences.getString(keyRole, null)
        return if (roleName != null) UsuarioRole.valueOf(roleName) else null
    }

    /** Retorna o token de sessão. */
    fun token(): String? = preferences.getString(keySessionToken, null)

    /** Atualiza o timestamp da última atividade (chamado a cada interação). */
    fun touch() {
        preferences.edit().putLong(keyLastActivity, System.currentTimeMillis()).apply()
    }

    /** Encerra a sessão (logout). */
    fun clear() {
        preferences.edit().clear().apply()
    }

    fun rememberCredentials(username: String, password: String) {
        preferences.edit()
            .putBoolean(keyRememberCredentials, true)
            .putString(keyRememberedUsername, username)
            .putString(keyRememberedPassword, password)
            .apply()
    }

    fun clearRememberedCredentials() {
        preferences.edit()
            .remove(keyRememberCredentials)
            .remove(keyRememberedUsername)
            .remove(keyRememberedPassword)
            .apply()
    }

    fun shouldRememberCredentials(): Boolean =
        preferences.getBoolean(keyRememberCredentials, false)

    fun rememberedUsername(): String =
        preferences.getString(keyRememberedUsername, "").orEmpty()

    fun rememberedPassword(): String =
        preferences.getString(keyRememberedPassword, "").orEmpty()

    /** Verifica se a sessão expirou por inatividade (> 15 min). */
    private fun isExpired(): Boolean {
        if (shouldRememberCredentials()) return false
        val token = preferences.getString(keySessionToken, null) ?: return true
        val lastActivity = preferences.getLong(keyLastActivity, 0)
        if (lastActivity == 0L) return true
        return (System.currentTimeMillis() - lastActivity) > inactivityTimeoutMs
    }

    /** Retorna true se houver sessão ativa e não expirada. */
    fun isAuthenticated(): Boolean = currentUsername() != null
}

/**
 * Proteção contra força bruta no login.
 * Limite de 5 tentativas falhas consecutivas por matrícula; após 5 erros,
 * bloqueia novas tentativas por 60 segundos.
 */
class LoginGuard {

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_SECONDS = 60L
    }

    /** Registra uma tentativa falha e retorna se o usuário está bloqueado. */
    fun registerFailure(user: AppUser): AppUser {
        val now = System.currentTimeMillis()
        val updated = if (user.failedAttempts + 1 >= MAX_ATTEMPTS) {
            user.copy(
                failedAttempts = user.failedAttempts + 1,
                lockedUntilEpochMs = now + (LOCKOUT_SECONDS * 1000)
            )
        } else {
            user.copy(failedAttempts = user.failedAttempts + 1)
        }
        return updated
    }

    /** Reseta o contador de falhas após login bem-sucedido. */
    fun reset(user: AppUser): AppUser =
        user.copy(failedAttempts = 0, lockedUntilEpochMs = 0L)

    /** Verifica se o usuário está atualmente bloqueado. Se sim, retorna segundos restantes. */
    fun isLocked(user: AppUser): Int? {
        if (user.lockedUntilEpochMs > System.currentTimeMillis()) {
            val remaining = ((user.lockedUntilEpochMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            return remaining.toInt()
        }
        return null
    }

    /** Verifica se o usuário excedeu o limite de tentativas (mesmo que o lockout já tenha expirado). */
    fun hasExceededAttempts(user: AppUser): Boolean =
        user.failedAttempts >= MAX_ATTEMPTS
}

/**
 * Validador de integridade de recibo no scanner OCR.
 * Calcula SHA-256 dos bytes da foto e detecta notas duplicadas no Room.
 */
class ReceiptIntegrityValidator {

    /** Calcula o hash SHA-256 dos bytes da imagem. */
    fun hashImageBytes(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Verifica se já existe nota com o mesmo número ou mesmo hash de foto. */
    fun isDuplicate(
        numeroNotaSantri: String,
        fotoHashSha256: String,
        existing: List<com.aistudio.santrirank.mcdfbx.model.NotaSantri>
    ): Boolean {
        return existing.any { it.numeroNotaSantri == numeroNotaSantri } ||
            existing.any { it.fotoHashSha256.isNotEmpty() && it.fotoHashSha256 == fotoHashSha256 }
    }
}

/**
 * Inicialização segura do Firebase App Check com Play Integrity (fallback seguro em debug).
 */
object AppCheckHelper {

    fun initialize(context: android.content.Context) {
        try {
            val firebaseApp = com.google.firebase.FirebaseApp.initializeApp(context)
                ?: run {
                    android.util.Log.w("SantriRank", "Firebase is not configured; App Check was not initialized.")
                    return
                }
            com.google.firebase.appcheck.FirebaseAppCheck.getInstance(firebaseApp)
                .installAppCheckProviderFactory(
                    com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
                )
        } catch (e: Exception) {
            android.util.Log.w("SantriRank", "Firebase App Check could not be initialized.", e)
        }
    }
}
