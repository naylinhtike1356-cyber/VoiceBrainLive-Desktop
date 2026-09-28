package com.example.voicebrainlive.desktop.platform

/**
 * Windows DPAPI credential protection via JNA (Crypt32Util).
 *
 * Secrets are encrypted with CryptProtectData in the current Windows user's
 * scope, so only that user account on this machine can decrypt them.
 * [isAvailable] is false on non-Windows platforms or when the JNA artifacts
 * are absent from the classpath; callers must handle that case explicitly
 * (dev/test fallback) and must never silently downgrade on Windows.
 */
object DpapiCredentialStore {
    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    fun isAvailable(): Boolean {
        if (!isWindows) return false
        return runCatching {
            Class.forName("com.sun.jna.platform.win32.Crypt32Util")
        }.isSuccess
    }

    /** Encrypts [plain] with DPAPI (current-user scope). Null when unavailable or on failure. */
    fun protect(plain: ByteArray): ByteArray? {
        if (!isAvailable()) return null
        return runCatching {
            com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain)
        }.getOrNull()
    }

    /** Decrypts DPAPI-protected [protected] bytes. Null when unavailable, corrupt, or on failure. */
    fun unprotect(protected: ByteArray): ByteArray? {
        if (!isAvailable()) return null
        return runCatching {
            com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(protected)
        }.getOrNull()
    }
}
