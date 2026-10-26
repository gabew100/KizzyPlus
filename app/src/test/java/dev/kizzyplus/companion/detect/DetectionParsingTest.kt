package dev.kizzyplus.companion.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionParsingTest {

    private val steamGame =
        "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Hollow Knight\\hollow_knight.exe"

    @Test
    fun psLinesParseWithPaddingAndSpacesInCommandLine() {
        val p = GameDetector.parsePs("  10234 31337 C:\\Program Files (x86)\\Steam\\steam.exe -silent")
        assertEquals(Proc(10234, 31337, "C:\\Program Files (x86)\\Steam\\steam.exe -silent"), p)
        assertNull(GameDetector.parsePs("  UID   PID CMDLINE"))
        assertNull(GameDetector.parsePs("10234 999 "))
    }

    @Test
    fun wineProcessesAreRecognisedButTheAppItselfIsNot() {
        assertTrue(GameNativeDetector.isWine("app.gamenative", "/data/data/app.gamenative/files/imagefs/usr/bin/wineserver"))
        assertTrue(GameNativeDetector.isWine("app.gamenative", steamGame))
        assertFalse(GameNativeDetector.isWine("app.gamenative", "app.gamenative"))
        assertFalse(GameNativeDetector.isWine("app.gamenative", "app.gamenative:wine_service"))
        assertFalse(GameNativeDetector.isWine("app.gamenative", "/system/bin/sh"))
    }

    @Test
    fun exeNamesComeFromTheLastPathSegment() {
        assertEquals(listOf("hollow_knight"), GameNativeDetector.exeNames(steamGame))
        assertEquals(
            listOf("winhandler", "game-win64-shipping"),
            GameNativeDetector.exeNames("winhandler.exe \"A:\\Binaries\\Win64\\Game-Win64-Shipping.exe\" -dx12"),
        )
        assertEquals(listOf("explorer"), GameNativeDetector.exeNames("wine64 C:\\windows\\explorer.exe /desktop=shell,1280x720"))
    }

    @Test
    fun fallbackTitleUsesTheGameFolder() {
        val procs = listOf(
            Proc(1, 10, "C:\\windows\\system32\\services.exe"),
            Proc(1, 11, "/data/data/app.gamenative/files/imagefs/usr/bin/wine64 $steamGame"),
        )
        assertEquals("Hollow Knight", GameNativeDetector.titleFromCommandLines(procs))
    }

    @Test
    fun fallbackTitleSkipsGenericFoldersAndCleansTheExeName() {
        val procs = listOf(Proc(1, 10, "winhandler.exe \"A:\\Binaries\\Win64\\Trover-Win64-Shipping.exe\""))
        assertEquals("Trover", GameNativeDetector.titleFromCommandLines(procs))
    }

    @Test
    fun saveHopIsNeverTakenForTheGame() {
        // Play.bat runs "%~dp0SaveHop.exe" run; GameNative maps a custom game's folder to A:.
        val procs = listOf(
            Proc(1, 10, "C:\\windows\\system32\\cmd.exe /c A:\\Play.bat"),
            Proc(1, 11, "A:\\SaveHop.exe run"),
            Proc(1, 12, "A:\\HollowKnight.exe"),
        )
        val exe = GameNativeDetector.gameExe(procs)!!
        assertEquals("HollowKnight", exe.exe)
        assertNull(exe.folder)
        assertEquals("A:", exe.windowsDir)
        assertTrue("savehop" in GameNativeDetector.exeNames("A:\\SaveHop.exe run"))
    }

    @Test
    fun onlySaveHopRunningGivesNoGameExe() {
        // While SaveHop downloads the newest save, the game hasn't started yet.
        assertNull(GameNativeDetector.gameExe(listOf(Proc(1, 11, "A:\\SaveHop.exe run"))))
    }

    @Test
    fun libraryRecordMatchesItsInstallFolderInAWinePath() {
        val record = GnRecord("STEAM_367520", "Steam", "367520", "Hollow Knight", listOf("Hollow Knight"), emptyList(), emptyList())
        assertTrue(record.matchesAny(listOf(GameNativeDetector.normalize(steamGame))))
        assertFalse(record.matchesAny(listOf(GameNativeDetector.normalize("C:\\games\\Hollow Knight Silksong\\game.exe"))))
    }

    private fun game(title: String, store: String?, id: String?) = DetectedGame(
        Launcher.GAMENATIVE, "app.gamenative", store, title, id, emptyList(), emptyList(), 0L, "test",
    )

    @Test
    fun savedNamesKeepTheirKeyAfterRenaming() {
        val steam = game("ELDEN RING", "Steam", "1245620")
        assertEquals("GAMENATIVE:Steam:1245620", steam.nameKey)
        assertEquals(steam.nameKey, steam.copy(title = "Elden Ring").nameKey)

        // Custom games have no ID; the detected name stands in for it and survives a rename.
        val custom = game("Hollow Knight", null, null)
        val renamed = custom.copy(title = "Hollow Knight (modded)")
        assertEquals("GAMENATIVE:custom:hollow knight", renamed.nameKey)
        assertEquals("Hollow Knight", renamed.detectedTitle)
    }

    @Test
    fun switchUpdateIdsMapToTheBaseGame() {
        assertEquals("0100F2C0115B6000", EdenDetector.baseTitleId("0100F2C0115B6800"))
        assertEquals("0100F2C0115B6000", EdenDetector.baseTitleId("0100F2C0115B6000"))
    }
}
