package dev.kizzyplus.companion

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** [BACKEND_BUILTIN] or [BACKEND_KIZZY]. */
    var backend: String
        get() = prefs.getString("backend", BACKEND_BUILTIN) ?: BACKEND_BUILTIN
        set(v) = prefs.edit().putString("backend", v).apply()

    var watchGameNative: Boolean
        get() = prefs.getBoolean("watch_gamenative", true)
        set(v) = prefs.edit().putBoolean("watch_gamenative", v).apply()

    var watchEden: Boolean
        get() = prefs.getBoolean("watch_eden", true)
        set(v) = prefs.edit().putBoolean("watch_eden", v).apply()

    /** [ART_COVER] or [ART_HEADER]. */
    var artwork: String
        get() = prefs.getString("artwork", ART_COVER) ?: ART_COVER
        set(v) = prefs.edit().putString("artwork", v).apply()

    var showLauncher: Boolean
        get() = prefs.getBoolean("show_launcher", true)
        set(v) = prefs.edit().putBoolean("show_launcher", v).apply()

    var pollSeconds: Int
        get() = prefs.getInt("poll_seconds", 10).coerceIn(5, 60)
        set(v) = prefs.edit().putInt("poll_seconds", v).apply()

    /** Discord status sent with the presence: online, idle or dnd. */
    var status: String
        get() = prefs.getString("status", "online") ?: "online"
        set(v) = prefs.edit().putString("status", v).apply()

    var applicationId: String
        get() = prefs.getString("application_id", DEFAULT_APPLICATION_ID)?.ifBlank { null } ?: DEFAULT_APPLICATION_ID
        set(v) = prefs.edit().putString("application_id", v).apply()

    var extraGameNative: String
        get() = prefs.getString("extra_gamenative", "") ?: ""
        set(v) = prefs.edit().putString("extra_gamenative", v).apply()

    var extraEden: String
        get() = prefs.getString("extra_eden", "") ?: ""
        set(v) = prefs.edit().putString("extra_eden", v).apply()

    fun gameNativePackages(): List<String> =
        (GAMENATIVE_PACKAGES + parsePackages(extraGameNative)).distinct()

    fun edenPackages(): List<String> =
        (EDEN_PACKAGES + parsePackages(extraEden)).distinct()

    companion object {
        const val BACKEND_BUILTIN = "builtin"
        const val BACKEND_KIZZY = "kizzy"
        const val ART_COVER = "cover"
        const val ART_HEADER = "header"

        // Kizzy's public application ID (data/rpc/Constants.kt in dead8309/Kizzy). Discord needs an
        // application to attach external artwork to; any ID works with a user token.
        const val DEFAULT_APPLICATION_ID = "962990036020756480"

        // From GameNative's app/build.gradle.kts (applicationId plus the .gold build type suffix).
        val GAMENATIVE_PACKAGES = listOf("app.gamenative", "app.gamenative.gold")

        // From Eden's src/android/app/build.gradle.kts: mainline, nightly, legacy and the
        // "Eden Optimized" flavor that ships under Genshin's package name for GPU driver spoofing.
        val EDEN_PACKAGES = listOf(
            "dev.eden.eden_emulator",
            "dev.eden.eden_emulator.nightly",
            "dev.legacy.eden_emulator",
            "com.miHoYo.Yuanshen",
        )

        // Package names end up inside root shell commands, so anything else is dropped.
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        fun isValidPackage(name: String): Boolean = PACKAGE_NAME.matches(name)

        fun parsePackages(text: String): List<String> =
            text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() && isValidPackage(it) }
    }
}
