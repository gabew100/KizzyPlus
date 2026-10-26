# Kizzy+

Kizzy+ is a small companion app for a rooted Android handheld (built for the Retroid Pocket 6). It notices the PC game you're running in **GameNative** or the Switch game you're running in **Eden**, and puts it on your Discord profile as "Playing *game name*", with the game's cover art and an elapsed-time counter.

Your status shows a game only while GameNative or Eden is the app on screen. It clears when you quit the game, go to the home screen or another app, or turn the screen off, and comes back when you return to the game. Kizzy+ doesn't modify, patch or reinstall GameNative or Eden. It only reads their files and process list through root.

## Install

1. Download `KizzyPlus-<version>.apk` from the latest release on this repository's **Releases** page, copy it to the device and open it. Allow installs from your file manager if Android asks. It installs over earlier versions and keeps your login and settings.
2. Open **Kizzy+**. Magisk (or KernelSU / APatch) asks for superuser access. Tap **Grant**.
3. Under **Discord account**, pick one:
   - **Copy login from Kizzy**: if Kizzy is installed and signed in, Kizzy+ reads Kizzy's saved login through root. This is the quickest option.
   - **Sign in with Discord**: Discord's normal web login inside the app.
   - **Paste a token**: if you already have your token.

   Kizzy+ checks the token with Discord and shows "Signed in as *name*".
4. Turn on **Share my game on Discord**. On Android 13 and later, allow notifications when asked. The ongoing notification is what keeps the service alive in the background.
5. Start a game in GameNative or Eden. Within a few seconds the game appears at the top of Kizzy+ and on your Discord profile. Kizzy+ grants itself usage access through root the first time, so it can tell which app is on screen.

Sharing turns itself back on after a reboot or an app update if you left it on.

### If you also use Kizzy

Kizzy's own **App Detection** shows "Playing GameNative" whenever GameNative is open, and it keeps running whatever Kizzy+ does, including when you turn sharing off here. Turn it off in Kizzy, or leave GameNative and Eden unticked in Kizzy's app list.

Kizzy+ warns you at the top of its screen when one of Kizzy's presence modes is running. **Clear my Discord status** clears Kizzy+'s own status and stops those modes. It uses Kizzy's own "stop" shortcut, plus root for Kizzy's Custom RPC.

## What you'll see on Discord

- **Name**: the game's title from GameNative's library (Steam, GOG, Epic, Amazon) or from Eden's game list. Custom games are named from SaveHop's `SaveHop.json` when the game runs through SaveHop, otherwise from the game's folder.
- **Second line**: where it's running, such as "GameNative · Steam" or "Eden · Nintendo Switch". You can turn this off.
- **Big image**: Steam's library cover (or the store banner, if you pick that), GOG/Epic/Amazon art from GameNative's library, or the Switch icon for Eden games.
- **Small image**: the GameNative or Eden logo.
- **Timer**: counts your current stretch of play. A break under 5 minutes, like a quick screen-off or a look at another app, keeps it running. After a longer break it starts again from when you came back, even if the game was running in the background the whole time.

## Renaming a game

While a game is showing at the top of Kizzy+, tap **Rename this game** and type the name you want on Discord. Kizzy+ uses that name every time it detects the same game again, and the new name goes out on the open connection straight away.

Kizzy+ recognizes the game by its Steam app ID, GOG, Epic or Amazon ID, or Switch title ID. Custom games have no ID, so it uses the name it detected (from `SaveHop.json` or the game's folder), which stays the same between launches.

Saved names are listed under **Renamed games**, with what each game was detected as. **Edit** changes a name, and **Remove** goes back to the detected one. So does saving an empty name or the detected name.

## Settings

| Setting | What it does |
| --- | --- |
| How presence gets to Discord | **Kizzy+ connects to Discord itself** (default) opens its own Discord connection only while a game is running and the screen is on. **Hand each game to the Kizzy app** starts Kizzy's Custom RPC through root instead and uses Kizzy's login (see the Kizzy section below). |
| What to watch | Turn GameNative or Eden detection off. |
| Check every | How often Kizzy+ checks GameNative's or Eden's processes while one of them is on screen: 5, 10, 20 or 30 seconds. Switching apps is noticed within 3 seconds regardless. |
| Cover art / Store banner | Which artwork to try first. The other is used if the first isn't available. |
| Show GameNative or Eden as the small icon | Also controls the "GameNative · Steam" line. |
| Discord status while playing | Online, Idle or Do not disturb. |
| Discord application ID | Discord attaches outside images to an application. The default is Kizzy's public ID. You can make your own on the Discord Developer Portal and paste its ID here. |
| Extra package names | For GameNative or Eden forks installed under a different package name. |

## Battery use

- **Screen off**: nothing runs. Kizzy+ sends Discord an empty status, closes the connection, and stops checking. It holds a wake lock for at most 15 seconds, just long enough to send that, so your status doesn't linger while the device sleeps.
- **Screen on, lock screen showing**: treated like screen off. Checking starts again when you unlock.
- **Screen on, another app in front**: every 3 seconds Kizzy+ asks Android which app is on screen. That's a local call with no root and no Discord connection.
- **Screen on, GameNative or Eden in front**: the same, plus one `ps` call through the root shell every 10 seconds.
- **Game being shared**: the same again, plus Discord's heartbeat on an open connection (about every 41 seconds).

Kizzy+ keeps a single root shell open instead of starting `su` for every check, so Magisk asks once and doesn't toast every few seconds. The shell runs in Android's global mount namespace (`su --mount-master`), because Magisk's default per-app namespace hides other apps' data folders.

## How detection works

Everything here comes from reading the GameNative and Eden source code. Kizzy+ doesn't rely on any API those apps don't have.

First, Kizzy+ asks Android's usage stats which app is on screen. Only when that's GameNative or Eden does it check for a game. Kizzy+'s own screen doesn't count, so opening it mid-game doesn't clear your status.

### GameNative

- GameNative runs games through Wine as processes under its own app UID. Kizzy+ runs `ps` as root and looks for processes under that UID whose command line contains `wine` or `.exe`. That's the same test GameNative itself uses (`WineProcessSnapshotHelper.readFromProc`). If any are alive, a game is running. When they all exit, the game is over.
- To name the game, Kizzy+ copies GameNative's Room database (`/data/data/app.gamenative/databases/pluvia.db` plus its `-wal` file) into its own cache and reads the copy. It never opens GameNative's own file. It's copied again only when the original changes.
  - Every launch writes a row to `library_play_history` (`MainViewModel.launchAppWithArgs`) with a container ID like `STEAM_1245620`, `GOG_…`, `EPIC_…` or `AMAZON_…`. That gives the Steam App ID and the launch time.
  - The running `.exe` path is checked against each candidate's install folder (Steam games run from `C:\Program Files (x86)\Steam\steamapps\common\<folder>\…`, per `XServerScreen.getWineStartCommand`). A match confirms the game. Otherwise the most recent launch is used.
  - Title and artwork come from the `steam_app`, `gog_games`, `epic_games` and `amazon_games` tables. Steam cover URLs follow GameNative's own `SteamApp.getCapsuleUrl` scheme.
  - Custom games (folders you added yourself) have no library row. GameNative maps their folder to drive `A:`, so the command line only says `A:\Game.exe`. Kizzy+ reads the drive's real folder from the Wine prefix (`WINEPREFIX` in the process environment, then the `dosdevices/a:` symlink) and uses the folder's name.
  - **SaveHop**: a SaveHop game runs as `Play.bat`, then `SaveHop.exe`, then the game. Kizzy+ never treats `SaveHop.exe` as the game. When it's running, Kizzy+ reads the game's `name` from `SaveHop.json` in the same folder. Without SaveHop installed, none of this runs.
- `library_play_history` exists from GameNative database version 23. On older builds Kizzy+ matches by install folder only.

### Eden

- Eden runs a game inside `org.yuzu.yuzu_emu.activities.EmulationActivity`. When Eden's process is alive, Kizzy+ runs `dumpsys activity -p <package> activities` as root. If that activity exists, a game is running.
- To name the game, it reads, in order:
  1. the activity's intent data, when the game was started from a home screen shortcut
  2. the newest of Eden's `"<path>_LastPlayed"` preference (written when you start a game from the library) and `files/cache/launched.json` (written by the emulator core when a game boots, in recent Eden builds)
  3. the `Loading <title> (<title ID>) ...` line Eden's core writes to `files/log/eden_log.txt`

  Titles come from Eden's own game list (the `Games` preference). The log line also catches a game switch inside the same session.
- Eden supplies no artwork, so the Switch icon comes from community title databases keyed by title ID (`tinfoil.media`, then `api.nlib.cc`). If neither has the game, the Eden logo is used instead.
- Watched packages: `dev.eden.eden_emulator`, `.nightly`, `dev.legacy.eden_emulator`, and `com.miHoYo.Yuanshen` (the "Eden Optimized" build, which ships under Genshin's package name).

## Kizzy: integrate or build a separate presence?

Both. Here's what Kizzy's source (github.com/dead8309/Kizzy) offers:

- **No public API for custom presence.** Kizzy's only exported entry points are `ShortcutsActivity`, which starts or stops its built-in App Detection, Media RPC and Experimental RPC modes, and a quick-settings tile. None of them take a game name or image. App Detection reports the foreground app, which would be "GameNative", not the game.
- **`CustomRpcService` takes a JSON `RPC` extra** (`RpcConfig`: name, state, images, timestamps) but isn't exported. Root `am` can still start it, so the optional **Hand each game to the Kizzy app** mode does exactly that. This drives an internal part of Kizzy, so a Kizzy update could break it. Kizzy also holds a wake lock for as long as that service runs. Kizzy+ stops it when the screen goes off.
- **Kizzy's login can be reused.** Kizzy stores the Discord token in MMKV (`files/mmkv/mmkv.default`, key `token`). **Copy login from Kizzy** reads it through root, so you don't need to sign in twice.

So by default Kizzy+ talks to Discord itself. It uses the same gateway approach as Kizzy's `gateway` module (identify, then a presence update), plus resume, reconnect with backoff, and a heartbeat check. For artwork it calls Discord's `external-assets` endpoint directly, the same call Kizzy's Imgur path makes, so no third-party image proxy is involved.

## Things to know

- **This uses your user token, like Kizzy does.** Setting presence from a user account is against Discord's Terms of Service ("self-botting"). Discord rarely acts on presence-only use, but the risk is yours. Kizzy+ only sets your status. It never reads or sends messages.
- Kizzy+ stores your token encrypted with an Android Keystore key, and only sends it to `discord.com`. The troubleshooting report never includes it.
- Images: Steam and GameNative art comes from Valve's and the stores' CDNs. Switch icons come from `tinfoil.media` and `api.nlib.cc`, which are community services and may change.
- Kizzy+ hasn't run on a Retroid yet. See **What was tested** below for what was checked and what wasn't. If something doesn't show up, run the troubleshooting check and look at what it reports.

## What was tested

On an Android 14 emulator rooted with Magisk 26.4, using two stand-in apps installed under GameNative's and Eden's real package names. They write the same files and processes the real apps do: a `pluvia.db` in WAL mode with Wine-like processes, and Eden's preferences, `launched.json`, `eden_log.txt` and an `EmulationActivity`. The real Kizzy 6.2 was installed alongside.

- Magisk's grant prompt, the "denied" state and **Ask for root again**.
- GameNative: a game named by its running `.exe` folder (Hollow Knight), and one named only by play history (ELDEN RING). The newest rows were read from the WAL file. Quitting cleared it.
- Eden: the game named from `launched.json`, a switch to another game inside the same session through the log line, and clearing when Eden closed.
- Screen off cleared the status within a second. A lit lock screen showed nothing. Unlocking brought it back with the original start time.
- The Kizzy bridge: Kizzy's notification read "Custom RPC is running: Hollow Knight". Screen off stopped Kizzy's service and unlocking restarted it. Switching back to the built-in mode stopped it.
- **Copy login from Kizzy** with Kizzy signed out, and **Paste a token** with a fake token (Discord's API rejected it).
- Discord's gateway, from a JVM test against the live servers: a fake token is refused with close code 4004 and Kizzy+ stops instead of retrying.
- Light and dark theme, portrait and landscape, and D-pad focus.
- Version 1.1.0: a SaveHop custom game (`A:\SaveHop.exe` with `SaveHop.json` in the folder `A:` maps to) was named from `SaveHop.json`. Pressing Home cleared the status within 3 seconds. Opening Kizzy+ from the home screen didn't bring it back, returning to the game did, and opening Kizzy+ mid-game kept it. The same went for Eden. With Kizzy's App Detection turned on through Kizzy's shortcut, Kizzy+ showed the warning, and **Clear my Discord status** stopped it.
- Version 1.2.0: renaming a running game changed the status within a second. The saved name was used again after the game was force-stopped and relaunched as a new session. Removing it went back to the detected name.

Testing found two bugs, both fixed in this build:

- Magisk's default mount namespace hides other apps' data folders, so GameNative looked uninstalled. The root shell now runs in the global namespace (`su --mount-master`, with an `nsenter` fallback).
- The unlock broadcast comes from SystemUI and wasn't reaching Kizzy+. The receiver now accepts it, and as a fallback Kizzy+ checks the lock state every 2 seconds while the lock screen is lit.

Not tested, because they need your account or the real apps:

- a real presence appearing on your Discord profile, with artwork
- capturing the token after the WebView login (the login page loads)
- real GameNative and Eden builds on the Retroid

## Troubleshooting

Tap **Run a detection check now** with a game running. The report shows:

- whether root works, and which GameNative/Eden/Kizzy versions are installed
- GameNative's Wine processes, the running `.exe` names and the last launches from its database
- whether Eden's emulation screen is open, and which of Eden's sources named the game
- the Discord connection state and the last 150 events

**Copy check results** puts the report on the clipboard. It never includes your token.

| What you see | What to do |
| --- | --- |
| A status stays on Discord after you stop playing or turn sharing off | Tap **Clear my Discord status**. If Kizzy+ shows a red note about Kizzy's App Detection, that's where the status came from; turn it off in Kizzy. |
| "Root access was denied" | Open Magisk, Superuser, allow Kizzy+, then tap **Ask for root again**. |
| "Not signed in to Discord" | Sign in with one of the three options. |
| "Discord rejected the saved token" | Your token changed (password change or logout). Sign in again. |
| Game shows as "a PC game" or a wrong name | GameNative had no library entry for it (a custom game). The report lists the `.exe` it saw and the folder `A:` points to. |
| No artwork | The check lists the image URLs it tried. If Discord didn't accept them, the event log says so. |

## Building from source

Requirements: JDK 17 and the Android SDK (platform 36, build tools 35). Point `sdk.dir` in `local.properties` at the SDK (Android Studio writes this file for you), then run:

```
gradlew.bat assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`.

### Signing

The signing key isn't in this repository, and `.gitignore` keeps it out. To sign release builds with it, put `release.jks` and a `keystore.properties` file in a `keystore/` folder at the root of the project:

```
storeFile=keystore/release.jks
storePassword=<store password>
keyAlias=kizzyplus
keyPassword=<key password>
```

Without that folder, release builds are signed with the Android debug key. Android won't install an update signed with a different key over an existing install, so keep a backup of the key somewhere safe.

### Tests

`gradlew.bat :app:testDebugUnitTest` runs the unit tests: parsing of `ps` output, Wine command lines, SaveHop and custom-game naming, Kizzy's MMKV file, saved names and the play timer. Add `-PliveDiscord` to also run the gateway test against Discord's servers. GitHub Actions runs the tests, lint and a debug build on every push to `main`.

The launcher icon ("K+" on grey) is a placeholder. Swap `app/src/main/res/drawable/ic_launcher_foreground.xml` and `values/colors.xml` for a real one.
