package dev.kizzyplus.companion.detect

enum class Launcher(val label: String, val logoUrl: String) {
    GAMENATIVE(
        "GameNative",
        "https://raw.githubusercontent.com/utkarshdalal/GameNative/master/app/src/main/ic_launcher-playstore.png",
    ),
    EDEN(
        "Eden",
        "https://git.eden-emu.dev/eden-emu/eden/raw/branch/master/dist/qt_themes/default/icons/256x256/eden.png",
    ),
}

data class DetectedGame(
    val launcher: Launcher,
    val packageName: String,
    /** "Steam", "GOG", "Epic Games", "Amazon", "Nintendo Switch"; null for custom GameNative games. */
    val store: String?,
    val title: String,
    /** Steam app ID, GOG/Epic/Amazon ID, or Switch title ID. */
    val gameId: String?,
    val coverUrls: List<String>,
    val headerUrls: List<String>,
    val startedAtMs: Long,
    /** How the game was identified, for the troubleshooting report. */
    val identifiedBy: String,
    /** The name detection came up with, kept when [title] is replaced by a name you saved. */
    val detectedTitle: String = title,
) {
    /** Changes whenever what Discord should show changes. */
    val presenceKey: String get() = "${launcher.name}|$packageName|$title|$gameId|$startedAtMs"

    /**
     * Identifies the game across launches for saved names: its store ID when there is one,
     * otherwise the detected name (custom games are named the same way every launch).
     */
    val nameKey: String
        get() = "${launcher.name}:${store ?: "custom"}:${gameId ?: detectedTitle.lowercase()}"

    val sourceLine: String
        get() = listOfNotNull(launcher.label, store).joinToString(" · ")
}

data class Proc(val uid: Int, val pid: Int, val cmdline: String)

sealed class Outcome {
    data class Playing(val game: DetectedGame) : Outcome()
    data object Idle : Outcome()

    /** Neither GameNative nor Eden is on screen, so nothing is shared even if a game runs behind. */
    data object NotOnScreen : Outcome()
    data class Failed(val reason: String) : Outcome()
}
