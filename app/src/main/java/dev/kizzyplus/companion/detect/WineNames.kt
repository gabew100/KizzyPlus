package dev.kizzyplus.companion.detect

import dev.kizzyplus.companion.root.RootShell
import org.json.JSONObject
import java.nio.file.Paths

/**
 * Names for games GameNative has no library entry for (custom games). GameNative maps a custom
 * game's folder to a drive letter (A:), so a command line like "A:\Game.exe" doesn't say which
 * game it is. The drive's real folder is a symlink in the Wine prefix's dosdevices folder, and the
 * prefix is in the process environment.
 */
internal object WineNames {

    private val DRIVE = Regex("([A-Za-z]):(?=\\\\|$)")

    /**
     * SaveHop (github.com/gabew100/SaveHop-Internal) starts games as Play.bat, then SaveHop.exe,
     * then the game, and keeps the game's name in SaveHop.json next to SaveHop.exe. This only does
     * anything when a SaveHop.exe process exists.
     */
    fun saveHopName(wine: List<Proc>, trace: MutableList<String>?): String? {
        val proc = wine.firstOrNull { "savehop" in GameNativeDetector.exeNames(it.cmdline) } ?: return null
        val at = proc.cmdline.indexOf("savehop.exe", ignoreCase = true)
        val dir = unixDir(proc.pid, proc.cmdline.substring(0, at)) ?: mappedDir(proc.pid)
        if (dir == null) {
            trace?.add("  SaveHop.exe is running but its folder couldn't be found")
            return null
        }
        val text = readText("$dir/SaveHop.json")
        val name = text?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }?.trim()
        trace?.add("  SaveHop.json in $dir: ${name?.ifBlank { null } ?: "no name"}")
        return name?.ifBlank { null }
    }

    /** The real folder name behind a drive-letter path such as "A:\" (a custom game's folder). */
    fun folderTitle(pid: Int, windowsDir: String, trace: MutableList<String>?): String? {
        val dir = unixDir(pid, windowsDir) ?: return null
        trace?.add("  $windowsDir is $dir")
        return dir.split('/').asReversed().firstOrNull { it.isNotBlank() && it.lowercase() !in GENERIC_DIRS }
    }

    /** Unix folder for the last drive-letter path in [windowsText], resolved for process [pid]. */
    fun unixDir(pid: Int, windowsText: String): String? {
        val m = DRIVE.findAll(windowsText).lastOrNull() ?: return null
        val letter = m.groupValues[1].lowercase()
        val rest = windowsText.substring(m.range.last + 1).trim('"', ' ').replace('\\', '/').trim('/')
        val r = RootShell.run(
            "pre=\$(tr '\\0' '\\n' < /proc/$pid/environ | sed -n 's/^WINEPREFIX=//p' | head -n 1); " +
                "[ -n \"\$pre\" ] && echo \"\$pre\" && readlink \"\$pre/dosdevices/$letter:\"",
        )
        val prefix = r.lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val target = r.lines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val root = if (target.startsWith("/")) target else "$prefix/dosdevices/$target"
        val full = if (rest.isEmpty()) root else "$root/$rest"
        return runCatching { Paths.get(full).normalize().toString() }.getOrNull()?.trimEnd('/')
    }

    /** Folder of SaveHop.exe from the process's memory map, if Wine mapped the file. */
    private fun mappedDir(pid: Int): String? {
        val line = RootShell.run("grep -i -m 1 'savehop\\.exe' /proc/$pid/maps").lines.firstOrNull() ?: return null
        val path = line.substring(line.indexOf('/').takeIf { it >= 0 } ?: return null).trim()
        return path.substringBeforeLast('/').ifBlank { null }
    }

    /** Shared storage paths aren't always visible in the global namespace; /data/media/0 always is. */
    private fun readText(path: String): String? = RootShell.readText(path) ?: when {
        path.startsWith("/storage/emulated/0/") -> RootShell.readText("/data/media/0/" + path.removePrefix("/storage/emulated/0/"))
        path.startsWith("/sdcard/") -> RootShell.readText("/data/media/0/" + path.removePrefix("/sdcard/"))
        else -> null
    }

    val GENERIC_DIRS = setOf(
        "bin", "bin64", "bin32", "binaries", "win64", "win32", "x64", "x86", "game", "games", "common",
        "steamapps", "steam", "program files", "program files (x86)", "windows", "system32", "syswow64",
        "retail", "shipping", "launcher", "app", "data", "0", "emulated", "storage", "download", "downloads",
    )
}
