# PallasVideoPlayer

Android TV app for NVIDIA Shield that browses Synology SMB3 shares and plays files in VLC, with Trakt metadata and TMDB fanart cards styled like the Home Assistant *UPCOMING TV* rows. Also includes **Live TV (IPTV)** with M3U playlists and XMLTV EPG, plus **YouTube** via Piped (ad-free direct streams).

## HARD RULES (agents — do not break)

These are non-negotiable product laws. Do not “optimize” them away, and do not reintroduce regressions when fixing unrelated bugs.

1. **Quit = silence.** When the user leaves / closes / backgrounds the app (Home, another app, activity `ON_STOP`), **all in-app playback must stop immediately** — Radio ExoPlayer, Music `PlayerController`, Podcasts, Live TV / YouTube / Multiview players. No audio may continue after the app is no longer in the foreground. (NAS video in **external VLC** is separate; `pallas://stop` still stops VLC handoff.)
2. **Playing = screen stays on.** While Radio, Music, Podcasts, Live TV (preview/fullscreen/multiview), or YouTube is actively playing inside Pallas, the device must **not** fall into screensaver, ambient mode, or display timeout. Use `FLAG_KEEP_SCREEN_ON` / `keepScreenOn` while playing; clear those flags when playback stops or the player UI is torn down. Do **not** stop Radio/Music/Podcasts solely on `ON_PAUSE` (transient); stop on **`ON_STOP`** / leaving the player so screensaver workarounds never leave audio running after quit.
3. **Overlays trap D-pad focus.** Any full-screen or modal sheet (search, find-on-NAS, options, clear/assign dialogs, leave-confirm, etc.) **must** own the focus tree until dismissed. D-pad / remote focus must **not** move to controls behind the overlay (nav rail, play/pause, station chips, shelves). `zIndex` / drawing on top is **not** enough — use a Compose `Dialog` (preferred; same pattern as Music search / `MusicModalOverlay`) or an explicit focus trap, and set `railFocusEnabled = false` (or equivalent) on chrome behind the sheet while it is open. The user must **Back** / close the overlay to reach anything underneath.

If a change touches playback lifecycle, wake locks, Radio/Music composables, or any modal/overlay, re-read this section and verify these rules still hold.

## Stack

| | |
|--|--|
| **Platform** | Native **Android TV** (NVIDIA Shield, Google TV / Chromecast) |
| **Language / UI** | **Kotlin** + **Jetpack Compose** under `app/src/main/java/…` |
| **Build** | Gradle (`.\gradlew.bat :app:assembleDebug`) → APK, install via **ADB** |

## Download (end users)

Get the latest **clean** APK from [Releases](../../releases) — no personal NAS, keys, or IPTV baked in. On Android TV / Google TV / NVIDIA Shield:

1. Enable **Unknown sources** / allow installs from your browser or file manager.
2. Install the APK, open **PallasVideoPlayer**, open the **gear**.
3. Enter your Synology host, credentials, Trakt/TMDB keys, and optional IPTV playlist.

## Features

Android TV app for NVIDIA Shield / Google TV. Browses a Synology NAS, plays video in external VLC, and includes in-app Music, Podcasts, Live TV, Radio, YouTube, and LAN remote control.

### Video player (NAS library)

Browse Synology shares over **SMB3** or **DSM File Station HTTP**. Split UI: file list left, cinematic preview right. Shares switch from the left rail or home corridor plaques.

- **Shelves:** Hero, Continue Watching, Folders, Videos. Folder posters only when every video inside is the same title; mixed bins use generic icons.
- **Metadata:** Filename → Trakt → TMDB art. Hold OK to assign/clear folder or file metadata (nested clear options), or **Show all videos** to flatten a folder (ignore subfolders; list every playable file underneath).
- **Search:** Video Station index (preferred) or raw scan; auto-refresh ≥24h. **Refresh** (↻ next to Search) force-syncs Video Station and reloads the current folder so new NAS folders appear.
- **Playback:** External player (VLC). Resume via notification listener + local store + NAS `.pallas.json` sidecars (cross-TV).
- **Extras:** `.rar` extract on NAS, delete from NAS, DVR shortcut to recording folder.

### Music player (NAS library)

Immersive player (blurred album art, track art, indigo/gold chrome). Streams from File Station with the same NAS credentials.

- **Library:** Audio Station → local Room cache (or File Station + ID3); sync ≥24h or on demand.
- **Browse / Search / Queue:** Left browse (Folders, Artists, Albums, Random), search overlay (Artists / Albums / Songs), right queue with reorder, EQ, disc headers.
- **Art & tags:** NAS cover → MusicBrainz / Cover Art Archive → Deezer / iTunes; live ID3 preferred over filenames; clickable fields jump to search.
- **Lyrics:** `.lrc` on NAS, or free [LRCLIB](https://lrclib.net) fetch.
- **Extras:** Track info sheet, random album/artist/genre, black screen (audio continues), Hue lightbulb toggle, shared sleep timer. Leaving Music stops audio.

### IPTV (Live TV)

Multiple **M3U** playlists, each with its own **XMLTV** EPG. Opens on last channel fullscreen; Back → guide → category gallery.

- **Groups:** Custom / Alphabetical / Most watched order; rename, move, hide.
- **AI EPG auto-assign:** Enter a Gemini or OpenAI API key under Settings → Live TV. Hold OK on a group → **AI match EPG…** and the model maps unmatched M3U channel names to XMLTV EPG channels (e.g. `UK: FHD BBC ONE` → `BBC One`), then saves the assignments. Per-channel **Assign EPG…** still offers ranked manual suggestions when you prefer to pick yourself.
- **Guide & zap:** EPG wheel, time scrub, Up/Down zap, Right = previous channel, Left = IPTV + NAS history.
- **Channel tools:** Favorite, rename, move, Assign EPG, Record, Open in VLC.
- **Search:** Channels + programme titles; quality badges; recent searches.
- **Stream HUD:** Measured res/fps/HDR; audio layout (Stereo, 5.1, Atmos…); detailed stream info sheet.
- **Recording:** EPG programme, presets, or custom duration; Local or NAS storage; schedules survive reboot; remux MP4 or keep `.ts`.
- **Multiview:** 1 / 2 / 4 panes. Parental PIN + locked groups. HDR SurfaceView; FFmpeg Dolby fallback.

### Radio

User-managed internet stations (BBC nationals seeded once; restorable).

- Station dial, EQ ring, ON AIR / TUNING, BBC logos when known.
- **BBC Sounds** metadata (show/track/art or speech programme + progress); geo-block bitrate fallback (320 → 96 → 48).
- **Find on NAS:** OK on artist/track → Music Search.
- **Record** to the same storage as Live TV, with timed stop presets.
- Black screen, Hue sync, sleep timer. Last station remembered. Leaving the app stops radio.

### Podcasts

Immersive player for RSS podcast subscriptions imported from Podcast Addict (or any standard OPML).

- **Import:** Settings → Podcasts → **Import OPML…** — browse NAS or this device, OK on the `.opml` file to import.
- **Browse:** Home hotspot + left rail (toggle under Display). Opens on **all shows · recent** episode feed from the last catalog snapshot (instant), then quietly refreshes feeds in the background; Shows picker can filter to one subscription. Sort **A–Z / Recent / Genre / In progress**; episode sort **Newest / Oldest / Unplayed**; resume progress. Manual **Refresh** still force-updates with a progress overlay.
- **Playback:** In-app ExoPlayer; −15s / +30s; sleep timer; black screen. Quit stops audio; playing keeps the screen on.
- Included in settings backup (path + subscriptions prefs).

### YouTube (optional on home/rail)

Ad-free via **Innertube** ([Piped](https://github.com/TeamPiped/Piped) fallback) into ExoPlayer. Search or paste URLs; Piped subscription feed (sort, Takeout CSV import); continue watching (last 20); related while playing.

### General

| Feature | What it does |
|--------|----------------|
| **Home landing** | Media-room art hotspots mapped to each prop (Radio, Music, Podcasts, Download/Library, YouTube, Live TV) when enabled in Display; share plaques; Settings; Remote room picker |
| **Sleep timer** | 15→30→45→60→90→Off across NAS / Live TV / Radio / Music; fade then stop; optional HA standby |
| **On-screen clock** | Corner-selectable; hidden in Live TV/YouTube fullscreen |
| **Display** | Full vs Reduced visuals; toggle which players show on home/rail |
| **Forced landscape** | Phones letterboxed to 16:9 |
| **LAN remote** | Phone/tablet connects to a TV room, then **mirrors that room**: navigating on the phone opens the same screen on the TV; Podcasts shows the TV’s subscriptions/episodes and plays on the TV. Phone shows a Connected chip; the TV shows **Controlled by …** (supports multiple remotes). Disconnected = local library only. |
| **Settings backup** | Export/import JSON to a private NAS folder |
| **Hard rules** | Playing keeps screen on; quit stops all in-app audio (including Podcasts) |

**Settings tabs:** NAS · Library · Playback · Display · Live TV · YouTube · Radio · Podcasts · Integrations · Backup

### Other integrations

- **Home Assistant** — now-playing webhook; radio station + recent podcast catalog webhooks; `pallas://play` / `pallas://radio` / `pallas://podcast` / `pallas://stop`; sleep → `pallas_sleep` standby with device id
- **Philips Hue** — Music/Radio audio → light pulse; restore on pause/stop
- **Trakt / TMDB** — video metadata and posters
- **Synology Video Station / Audio Station** — preferred indexes
- **Piped** — YouTube browse/auth
- **BBC Sounds** — radio metadata
- **Gemini / OpenAI** — AI auto-assigns M3U Live TV channels to XMLTV EPG ids (group **AI match EPG…**)
- **LRCLIB + art APIs** — lyrics and cover art
- **Cross-TV sidecars** — resume/watched sync via `.pallas.json` on the NAS

## Defaults (developers)

Personal defaults for private builds live in gitignored `personal.defaults.properties` (copy from `personal.defaults.properties.example`). The public/clean APK leaves them blank so each user configures Settings themselves.

| Setting | Notes |
|---------|-------|
| NAS host / user | Set in Settings (or `personal.defaults.properties` for your own debug builds) |
| Connection | `SMB3` (preferred on Shield) or `HTTP` (DSM File Station, port 5000) |
| VPN note | Tailscale/VPN (`10.x` source IPs) often blocks LAN NAS — disable VPN or use SMB3 on LAN |
| Shares | Browse NAS folders in Settings (examples: `download`, `video`, `docs`) |
| Search index | Local video index from **Video Station** when available (else folder walk), auto-refresh ≥ every 24h |
| IPTV | Your own M3U + XMLTV URLs under Settings → Live TV |

Trakt Client ID and TMDB keys are entered in Settings (gear). **Client secret is not required** for search/metadata.

## Devices (local deploy)

Install over ADB to your own Android TV / Google TV devices. ADB is usually at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (or `adb` on PATH).

If port `5555` is refused, the device may use **wireless debugging** with a dynamic port — read it from Developer options → Wireless debugging and `adb connect <ip>:<port>`.

**Chromecast / Google TV notifications:** Android 14 needs `POST_NOTIFICATIONS`. Grant over ADB: `adb shell pm grant com.vizvag.shieldvideo android.permission.POST_NOTIFICATIONS` and `adb shell cmd notification allow_listener com.vizvag.shieldvideo/com.vizvag.shieldvideo.playback.ShieldNotificationListener`.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

APK path:

`app\build\outputs\apk\debug\app-debug.apk`

## Clean (distributable) build

A **clean** build is a shareable version with **zero personalization baked in** — no API keys (Trakt/TMDB), no IP addresses, no NAS host/user, no Home Assistant webhook, no accounts, and no IPTV playlist. A new user must enter all of their own data in the app's **Settings** on first run. **BBC radio stations are included** — they live in `RadioDefaults.kt` (not BuildConfig) and are seeded on first install like any other build.

- Personalized defaults load from gitignored `personal.defaults.properties` into `buildConfigField` values in `app/build.gradle.kts`. Without that file, debug builds are blank like clean. The `clean` build type always forces `""` and `BuildConfig.CLEAN_BUILD = true`.
- Source never hard-codes personal values — `SettingsRepository`, `IptvDefaults`, and `NasPaths` read `BuildConfig`.
- When adding a new personalized default, add it to `personal.defaults.properties.example`, load it in `defaultConfig`, blank it in the `clean` build type, and read it via `BuildConfig`.

Build the clean APK:

```powershell
.\gradlew.bat :app:assembleClean
```

Clean APK path (debug-signed, installs like any other debug build):

`app\build\outputs\apk\clean\app-clean.apk`

Publish clean builds from [Releases](../../releases) (e.g. `PallasVideoPlayer-clean-v<versionName>.apk`).

## Update / install on all devices

One-time per device:

- **NVIDIA Shield:** Settings → Device Preferences → About (click Build 7×) → Developer options → enable **Network debugging**
- **Google TV / Chromecast:** Settings → System → About → click Build 7× → Developer options → enable **USB debugging** / **Network debugging** (wording varies)

Accept the “Allow USB debugging?” prompt on the TV the first time this PC connects.

Then from PowerShell (build + push). Replace the IPs with your TVs:

```powershell
.\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = "app\build\outputs\apk\debug\app-debug.apk"
$devices = @("192.168.x.x", "192.168.x.y")  # your Android TV LAN IPs

foreach ($ip in $devices) {
    Write-Host "=== $ip ==="
    & $adb connect "${ip}:5555"
    & $adb -s "${ip}:5555" install -r $apk
}
```

`-r` replaces the existing install and keeps app settings (NAS password, device id, etc.).

Check connections:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
```

After first install: open **PallasVideoPlayer** → **gear** → enter NAS credentials → **Test connection** → set Device id if using HA handoff → **Save**.

## UI

- **v2.1 layout:** **Option C split** for all NAS browse (list left, cinematic preview right). Music uses **Option D immersive** (full-bleed now-playing; Browse slides in from the left, Playlist from the right). Radio keeps Option C split. Option B focus-stage was not used.
- **v2.0 remake:** cinema near-black + luminous lime (video) and indigo-black + gold (Music/Radio); Google TV–scale type; soft glass surfaces; white focus rings with accent glow — not the old olive/muddy HA look
- **Motion:** cinematic route transitions, snappy focus springs, staggered list entrances, theater-mesh ambient light
- **Phones / tiny windows:** forced **landscape**; UI letterboxes into a 16:9 stage that scales to fit. The remote **Connected** banner overlays the stage (compact single row) so it does not shrink the letterbox. Home room hotspots stay aligned to the media-room art.
- **Sound:** system navigation/click cues plus a soft click tone on select
- Top icon row: share/folder switcher moved into the **left nav rail** on Browse (shares, recordings, Live TV, YouTube, Radio, Music, Podcasts, sleep, settings, up). **Search** lives on the NAS video browse page (list header), not the shared rail
- Search: query across folders via Index (fast) or Raw scan; index prefers Synology **Video Station**, falls back to folder walk; auto-updates every 24h
- Settings: tabbed groups — **NAS** · **Library** · **Playback** · **Display** · **Live TV** · **YouTube** · **Radio** · **Podcasts** · **Integrations** · **Backup** (Save stays in the header). **Library → Default folder** is what the home landing focuses and opens for the HOME THEATRE tile (and the browser’s starting share). **Playback** includes on-screen **clock corner** (subtle updating time + long date; default bottom right; hidden during Live TV / YouTube fullscreen). **Display → Visual effects** shows your current mode (**Full visuals** or **Reduced visuals**); reduced turns off blur, ambient motion, and looping EQ for weaker devices (Chromecast HD). **Display → Home & side nav** toggles which players appear on home and the left rail (Radio / Music / Podcasts / Library / YouTube / Live TV)
- Settings backup: **Settings → Backup** selects a private NAS folder and exports/imports `PallasVideoPlayer-settings.json`. The portable backup contains NAS credentials, Trakt/TMDB keys and tokens, IPTV playlists, YouTube Piped API URL, custom radio stations, podcast OPML path + subscriptions, favorites, custom channel order/names, manual EPG assignments, EPG AI API key/provider, measured stream badges, parental settings, Display (lite visuals), home landing tile visibility, the **LAN remote control token**, and **Philips Hue** Music sync (bridge IP, username, selected lights). Import replaces those values so another TV/phone has the same setup; if this TV already has a Device id (`lounge` / `bedroom`), that id is kept so HA handoff stays correct. Caches and viewing/search history are rebuilt locally. The JSON contains secrets in readable form, so store it only in a private NAS folder. File transfer uses SMB3 on port 445 automatically; when the app is configured for HTTP browsing, this is a temporary in-memory connection and the saved HTTP mode/port are never changed.
- Browser: hero + Continue Watching / Folders / Videos poster shelves; **folder tiles use Trakt/TMDB art only when every video inside is the same title** (season packs); mixed bins like `Films` and top-level `/video` categories use generic icon tiles; path still navigates with Back / rail Up
- **`.rar` archives:** shown only when a folder has **no video files**; **Hold OK** opens Extract (into the same folder via File Station; the `.rar` is kept). Short OK does not play in VLC. Extract runs on the NAS; progress is overall volumes done/total for multi-part sets (not per-volume). **Hide** (or Back) while extracting minimizes to a slim top progress bar so you can browse/watch; OK on the bar reopens the dialog
- Search / Settings: glass chrome, underline tabs, ambient backdrop
- Music / Radio: Music is a **ground-up Option D immersive stage** (v2.4) — blurred album-art backdrop, **square track art on the left**, title/artist/progress/transport on the right (scales down when a side panel is open); **Search** overlay (Artists / Albums / Songs columns); **Browse** expands from the left and **Queue/Playlist** from the right, each pushing the player so both panes fit; close restores full-width player. Radio keeps Option C split
- Horizontal media cards remain in Search results; browse uses portrait posters on shelves
- Trakt matches require title-token coverage and an exact year match when the filename includes a year
- Long-press OK on a **file** tile (or Menu) clears wrong Trakt/TMDB metadata; long-press again restores lookup
- Long-press OK on a **folder** with Trakt art opens clear options: **This folder and everything inside** (nested subfolders and files inherit the clear) or **This folder only** (nested items keep their metadata); long-press on a folder **without** art opens an assign picker (Trakt search candidates) so you can pick the TV show/movie or **Keep empty** — the assignment applies to that folder, **all nested subfolders**, and **all files** under it
- Folder titles keep season labels (e.g. `The Musketeers S01`); release junk is still stripped
- Manual folder assignments are remembered until cleared again
- Nested folders: open folder / Back / remote Back to go up
- Folders named `screens` are hidden (browse, search, and index)

## YouTube (ad-free)

- **Rail:** YouTube sits next to Live TV on the left nav rail when enabled under **Settings → Display**; optional on the home landing too
- **Playback:** Resolves streams via **YouTube Innertube** from the TV (falls back to **[Piped](https://github.com/TeamPiped/Piped)**), then plays **direct media** in ExoPlayer — no official YouTube player, so mid-roll ads are not loaded
- **Browse:** Search (query or paste a `youtube.com` / `youtu.be` URL), subscription feed (when logged into a Piped account) with **Newest / Popular / A–Z** sort and upload date on cards, refreshes on every open plus the Refresh button, local continue-watching history (last 20), recommendations while playing
- **Settings → YouTube:** Piped API base URL (default `https://api.piped.private.coffee`) plus **Piped username/password** (Log in / Register / Log out) for the subscription feed. Import Takeout `subscriptions.csv` via **Import from Downloads**. Included in settings backup
- **Maintenance:** Public Piped instances and YouTube’s backend change often — feed/search use Piped; playback prefers on-device Innertube when Piped is bot-blocked. Not for Play Store distribution

## Live TV (IPTV)

- **Playlists:** Settings → IPTV — add/switch multiple M3U URLs; each can have its own EPG URL
- **Browse:** opening Live TV resumes the last channel in **fullscreen**. **Back** once → channel list + EPG; **Back** again → category gallery; **Back** again leaves Live TV. From categories, OK opens the channel+EPG guide. Programmes use subtle typography and a 0.5dp outline for end boundaries; airing programme in accent; **Right** +30m / **Left** earlier, then categories. **10s of idle** on browse chrome fades it and returns to **fullscreen viewing** (Up/Down zap). **Hold OK / Menu on a category** opens group options: channel order (**Custom**, **Alphabetical**, or **Most watched**), **Auto-match EPG…**, **Rename group**, **Move group…**, **Hide group** / **Show group**. Hidden groups stay reachable. Preview is out of the D-pad focus chain — **Down** from the top bar returns to the guide. Toggle **Show EPG in preview** and **Preview guide size** under Settings → Live TV. Scroll browses; **OK** plays fullscreen. **Hold OK / Menu** on a channel opens Assign EPG · Record · Favorite · Rename · **Move channel…** · VLC.
- **Search:** channels + EPG programme titles — channel results show quality badges (4K/FHD/HD) and favorite state; **OK** plays fullscreen, **hold OK / Menu** adds/removes favorite. The 10 most recent searches are saved per playlist and shown when the query is empty.
- **Quality badges:** while a channel plays, the real resolution / fps / HDR are measured from the decoder output (fullscreen + preview HUDs) and cached per channel; guide rows, search results, and the history list show confirmed badges in cyan for channels watched before, falling back to grey name-derived hints (e.g. `4K`, `50fps`, `HDR` tokens in the channel name) otherwise. While zapping, the HUD keeps showing the previously measured badges for a channel until the new live measurement is confirmed (~1s of rendered frames), so the resolution no longer appears to flip back to the name-declared value on every channel change. Fullscreen also shows the selected audio layout (`Mono`, `Stereo`, `5.1`, `7.1`, `Dolby Atmos`, etc.) in an orange badge, plus a `♫ N` indicator when multiple audio tracks are available. Hold **OK** or press **Menu** in fullscreen for detailed stream information: video codec, resolution, frame rate, bitrate, audio codec (of the selected track, e.g. AAC / Dolby Digital / MP2), layout, sample rate, language, support, and per-track details.
- **EPG / catch-up:** Playlist/EPG under `iptv_cache/` with a fast channel snapshot (`.idx`). Prefetched at app start; Live TV UI opens immediately from local data (no spinner), then EPG/network refresh in the background. Force refresh still re-downloads. **AI EPG matching** (Settings → Live TV → Gemini or OpenAI API key): hold OK on a group → **AI match EPG…** sends unmatched M3U names + candidate XMLTV channels to the model and persists assignments (e.g. `UK: FHD BBC ONE` → `BBC One`); manual **Assign EPG…** still lists ranked suggestions. Assignments are persisted per playlist using a stable channel identity (tvg-id + name, with and without group) and are self-healed onto those keys when Live TV loads — older assignments keyed only by URL-hash channel ids survive provider URL rotation and settings import on another device; the XMLTV channel index is de-duplicated by id (some EPG feeds declare the same channel twice, which crashed the Assign EPG search list); catch-up when playlist provides catchup tags
- **HDR:** fullscreen playback uses a SurfaceView so HDR streams switch the TV to HDR mode (browse preview is TextureView → SDR only, needed for Compose overlay clipping). **AC-3 / E-AC-3 audio** uses device hardware when available, otherwise FFmpeg software decode so Chromecast and other boxes without a Dolby decoder still get sound.
- **Screensaver:** Live TV preview, fullscreen playback, and multiview keep the display awake on Shield and Google TV/Chromecast; Radio and Music do the same while playing. Normal ambient/screensaver behavior resumes when playback stops or the player view closes. See **HARD RULES** at the top of this README.
- **Sleep timer** (timer icon): shared across **NAS browser**, **Live TV**, and **Radio** — cycles **15 → 30 → 45 → 60 → 90 → Off**; countdown stays visible when you switch screens; volume fades over the last minute then stops the active player (VLC for NAS, Live TV / Radio in-app). Optional **Home Assistant standby**: Settings → Integrations → **Sleep timer → HA standby** — POSTs to `…/api/webhook/pallas_sleep` with `{"device":"<device id>","action":"standby"}` (device id from Settings → Integrations; webhook URL derived from the now-playing webhook).
- **Playback history:** Live TV resumes the last watched channel for each playlist. In fullscreen, **Up** previous / **Down** next channel in the current group (wraps), **Right** jumps back one channel, and **Left** opens a combined history containing the 10 most recent IPTV channels and 10 most recent NAS videos. Selecting a channel zaps in-app; selecting a NAS video opens the configured external player at its saved resume position. HUD shows the channel name/group/badges at the top and a bottom EPG tile (Now title + description + progress bar, Next title, with times) briefly
- **Playback memory:** Live TV uses a capped ExoPlayer buffer (~10s / 8MB), fully stops and clears the previous stream on each zap, and recreates the player every ~20 channel changes so MediaCodec / FFmpeg / AudioTrack native memory does not keep growing during long zap sessions
- **Multiview:** grid icon — 1 / 2 / 4 panes with in-app ExoPlayer
- **Recording:** Hold OK / Menu → **Record** opens a TV-friendly chooser: record the current/upcoming EPG programme, use a 30-minute / 1-hour / 2-hour preset, or enter a custom duration (1 minute–24 hours). **Record** beside a programme in the Guide schedules that programme's exact EPG start/stop time. Scheduled or active programmes are marked **● REC** in red in the EPG wheel and programme dialogs; the fullscreen Now tile also shows **● REC** with a red progress bar while the current programme is being captured. To **cancel** a scheduled recording, reopen **Record…** (or the Guide) and tap the marked **● REC** programme — it toggles to cancel; a scheduled recording is removed, and an in-progress one is stopped keeping what was already captured. Recordings can also be stopped/removed from the Recordings pane. Scheduled recordings use Android alarms, survive app restarts/device reboots/app updates, and start a foreground recorder even when Pallas is not open. An in-progress recording shows **Stop & save** in the Recordings pane (top-bar DVR icon in Live TV) — everything captured so far is remuxed and saved to the recording folder. When recording storage is a NAS folder, that folder also gets its own DVR icon in the browser's top navigation for quick playback of finished recordings. If Android has not granted exact-alarm access, Settings → Live TV shows **Allow precise programme start times**; without it Android may delay the start. Settings → Live TV → **Recording storage** selects either **Local** (a user-selected Android document folder, or app-local `Movies/IPTV Recordings` by default) or **NAS** (a user-selected NAS folder; transfer always uses SMB3/445 even when browsing uses HTTP). The incoming transport stream is remuxed without quality loss to MP4 when its codecs are MP4-compatible; provider streams with codecs Android cannot place in MP4 (for example some MPEG-2 feeds) are retained as playable `.ts` rather than discarded. Filenames use `yyyy-MM-dd_HH-mm - Channel - Programme.mp4` (or `.ts` fallback), with filesystem-unsafe characters removed. The recording destination is included in settings backup; Android local-folder permissions remain device-specific.
- **Parental:** Settings PIN + comma-separated locked group titles
- **Personalization:** show EPG in preview, preview guide size (Small / Medium / Large), compact rows

## Music

- Top-bar **Music** icon (next to Radio) opens the NAS music library player
- **Audio UI chrome:** indigo-black + gold accent (shared with Radio) — distinct from the video cinema/lime look
- Streams audio from Synology **File Station** over HTTP/HTTPS (port 5000/5001) — uses the same NAS host, username, and password as video browsing
- **Cover art:** **Album art** (blurred full-screen background) from NAS only if the folder name matches the album; else MusicBrainz → Cover Art Archive → Deezer / iTunes. Compilations / *NOW That’s What I Call Music* skip artist-folder `folder.jpg` and require the volume number (e.g. **32**) to match. NAS covers are downloaded via the authenticated File Station session into a local Coil file cache (raw Synology download URLs are not used as image models). **Track art** (sharp left tile) is looked up separately from Deezer / iTunes by artist + title (falls back to album art). Now-playing **title/artist always prefer live ID3 / Audio Station tags** over the file name. Art clears and reloads on every track change so the previous cover never sticks.
- **Settings → Library:** **Music folders** — browse/add/remove NAS folders like video folders (default `/music`); optional HTTPS and self-signed cert trust; shows music index status (track count / last updated / live progress) with **Rebuild music index now**. **Video index** prefers Synology **Video Station** (same DSM credentials / HTTPS toggles as Music), falls back to a recursive folder walk; **Rebuild index now** forces a refresh
- **Sync library** (cloud icon) pulls Synology **Audio Station**'s media index into a local Room cache (`pallas_music.db`), then keeps an **app-scoped in-memory copy** so Browse/search stays instant after startup. NAS sync runs **on app start** if the cache is empty/older than **24 hours**, and again on that interval in the background — **not** every time Music opens. The Sync button still forces an immediate refresh. If Audio Station is unavailable, falls back to File Station scan + local tag parse across all configured music folders. **Rebuild music index now** forces a full File Station re-parse when using that fallback.
- **Layout:** landscape stage — **title (white)** and **artist (gold)** on separate rows; if **Album Artist** differs from **Artist**, both are shown (album artist slightly quieter); album + muted **Track xx**; type/controls scale with pane width. Top chrome: Music · **Search** · Browse · Queue · Lyrics · Info · **Hue** (when paired) · Sleep · Black screen. **Search** opens a large overlay with **Artists / Albums / Songs** columns (OK plays; Right opens Browse for artist/album). VA compilations that were indexed as one album per track artist collapse to a single album row (same title + year). **Browse** (left) / **Queue** (right) push the player (~30% side panels) so both panes fit. Folder **hold OK** → Play folder / Add to playlist. Queue: flat setlist with **per-track art** (Deezer/iTunes track art → NAS folder cover → album art), preloaded as soon as tracks are queued so thumbs are ready when Queue opens; live EQ on the playing row, remaining-time progress rail, **UP NEXT** marker, **hold OK** to pick up, **Up/Down** to move, OK to drop. Single-album multi-disc queues show **DISC N** headers and **Disc N · artist** on each row (ignored when the queue mixes albums). Browse defaults to **Artists**; empty library shows Sync.
- **Lyrics:** the lyrics button looks for a matching `.lrc` beside the audio file on the NAS. If none is found, the panel asks whether to fetch synced lyrics from **[LRCLIB](https://lrclib.net)** (free, no API key); Yes looks up by title/artist/album/duration, No leaves “No lyrics found”. Online results are cached for the session.
- **Folders** tab (default in Library): browse configured music NAS folders like a file tree — with multiple folders, the root lists each path; **OK** opens a folder; **hold OK** for Play now (clear playlist) · Add to playlist · Open
- **Hold OK** menu: Play now · Add to playlist only (Back dismisses; OK already opens folders)
- **Clickable MP3 metadata** on the player: live-reads ID3 tags for the playing track (artist, album artist, album, year, genre, track/disc, composer, lyricist, conductor, publisher, grouping, mood, BPM, ISRC, codec/bitrate/sample rate, comment, …). **Artist**, **album artist**, **title**, and **album** open **Search** with that query; year still opens Browse
- **Folders:** **Play folder** and **Add to playlist** chips on the current folder; hold OK on a row for Play now · Add. Status line confirms how many tracks were added
- **Add to playlist** starts playback if nothing is playing (leaving Music stops audio but keeps the queue)
- **Random** chip: play a random album, artist, or genre
- Leaving Music (Back) **stops playback**; in Library Folders, Back goes up one folder first, then returns to the player
- **Sleep timer** works while music is playing (same shared timer as NAS / Live TV / Radio)
- **Black screen** (moon icon): blanks the display while audio keeps playing — fully black; **OK** or **Back** wakes the UI (Back while black does not leave Music)
- **Philips Hue sync** (optional): Settings → Integrations → **Music / Radio Hue sync** — enter bridge IP, press the Hue bridge link button → **Pair bridge**, **Refresh lights**, select lights, Save. While Music or Radio is playing, selected lights pulse brightness/color from the audio; pause/stop restores prior light states. Music and Radio top bars show a **lightbulb** toggle (accent when on) once Hue is paired with lights selected. Not Live TV, YouTube, or external VLC

## Radio

- Top-bar **Radio** icon (next to Live TV) opens a dedicated internet-radio player
- **All streams are user-managed** in Settings → Radio — add, edit, or delete any station; nothing is hard-coded in the player. On first install, BBC national stations are seeded once (Radio 1, 1Xtra, Radio 1 Dance, Radio 2, Radio 3, Radio 4, Radio 4 Extra, Radio 5 Live, 6 Music, World Service); tap **Add BBC defaults** to restore any you removed
- **Now playing metadata** from the BBC Sounds API when a station has a BBC metadata id set (not in the HLS stream): music stations show the **current show name** (e.g. Radio 1 Breakfast), artist, track, artwork, and recently played; speech stations show programme title, episode, synopsis, and a progress bar. Metadata is polled every 30s and works outside the UK (`experience=international` first, then domestic)
- **Find on NAS:** OK on the now-playing **artist** or **track** (or a recently-played row) opens an in-Radio **FIND ON NAS** overlay (`RadioNasFindPanel`) — radio keeps playing; results play into Music. The overlay is a Compose **`Dialog`** so D-pad focus cannot escape to the rail or radio controls (see HARD RULE 3); Back / close returns to Radio
- Station chips and the player header use bundled **BBC station logos** (from official brand artwork) when the station id matches a known BBC default; other stations keep the short-letter badge
- Station-coloured dial, animated equaliser ring, ON AIR / TUNING status, and a horizontally scrollable row of D-pad-friendly station cards
- **Geo-block handling:** if the 320 kbps UK stream is blocked, the player automatically falls back to 96 kbps then 48 kbps international feeds and shows which bitrate is active
- **Settings → Radio:** name, tagline, stream URL, optional BBC metadata id for each station; included in settings backup/import
- Play / pause from the centre button; last station is remembered; screensaver stays off while listening
- **Leaving the app (Home / other app) stops Radio immediately** — audio must not keep playing in the background
- **Record** (red button next to play/pause): captures the live stream while you listen and saves it to the same **Recording storage** folder as Live TV (Settings → Live TV — Local or NAS). Press again to stop and save. **Hold OK** while recording opens **Stop after** presets (15 / 30 / 45 / 60 / 120 min); the REC pill shows the remaining time. With a timed stop set, leaving Radio **inside** the app keeps recording in the background until it expires (or you stop it); quitting the app still stops live playback. Switching station or the sleep timer finishing still stop and save. Filenames use `yyyy-MM-dd_HH-mm - Station - Programme.mp4`
- **Sleep timer** (timer icon in the Radio top bar): same shared timer as NAS / Live TV — countdown continues if you started it elsewhere; fades then pauses radio
- **Black screen** (moon icon): blanks the display while audio keeps playing — fully black (no wake hint text); **OK** or **Back** wakes the UI (Back while black does not leave Radio)
- Music has the same **Black screen** moon control as Radio
- **Hue sync** lightbulb in the Radio top bar (same setting as Music) when Hue is paired
- Station logos: bundled BBC artwork including **Radio 1 Dance**
- Default BBC URLs live in `RadioDefaults.kt` (seed/restore only); if a station goes silent after a BBC CDN change, edit the stream URL in Settings or re-add BBC defaults

## LAN remote control (phone / tablet)

The tablet does **not** get a separate remote UI. Pick a room, then use the **same** Music / Radio / Live TV / YouTube screens as the TV — transport and queue drive that room over Wi‑Fi. **All devices** (TVs and tablets) open on the **home landing** page; tablets reach room picker from the home remote icon (not as the start screen).

**Hard rule:** while a room is selected (`RemoteTargetStore`), **all** playback (NAS/VLC, Music, Radio, Live TV, YouTube) must run on that TV — never decode or open players on the tablet. Live TV keeps the guide UI on the tablet and sends channel changes to the room; Multiview / catch-up stay TV-local only.

1. On each TV: set **Device id** (`lounge` / `bedroom`) under Settings → Integrations (optional but clearer names) and leave **Allow remote control** on.
2. Open Pallas on the phone/tablet — home landing first; open **Remote** to list TVs on Wi‑Fi (playing ones first).
3. Tap a room → returns to **home** with that room selected (banner). Open Music / Radio / Live TV / Library from home to control the TV; leaving Music on the tablet does **not** stop the TV. The TV shows a **Controlled by …** chip listing each connected remote (phone + tablet can both be connected).
4. While controlling a room, the tablet’s NAS browser lists files for play-to-TV but **skips Trakt/TMDB art enrichment** so it does not burn the shared API rate limit and blank the TV’s posters.

Same Wi‑Fi only. No shared token required.



Browse/play on one TV can use **HTTP or SMB**. Cross-TV move streams through a foreground **LAN HTTP proxy** on the target (Pallas authenticates to the NAS over SMB; VLC plays `http://<tv-ip>:…` with Range/resume). The target TV must be awake.

### Cross-TV resume (NAS sidecars)

While you watch a NAS video, Pallas writes a tiny sidecar next to the file (e.g. `Movie.mkv` → `Movie.mkv.pallas.json`) over SMB. Other TVs read it when browsing or playing, so resume position and “watched” dimming stay in sync across devices. Sidecars never appear in the app browse/search UI. The NAS account used by the app needs write permission on the video shares. Progress is also kept locally; NAS writes are throttled (~30s) and flushed when playback stops.

```text
pallas://play?share=<share>&path=<encoded relative path>&host=<nas-host>&title=&position=
pallas://radio?stationId=<id>
pallas://radio?name=<station name>
pallas://podcast?guid=<guid>&showId=<showId>
pallas://podcast?label=<Show · Episode title>
pallas://podcast?show=<show name>   # latest episode of that subscription
pallas://podcast?refresh=1
pallas://podcast?skip=-15
pallas://podcast?skip=15
pallas://stop
```


While playing (notification access on), each TV posts now-playing to your Home Assistant webhook (configure under Settings → Integrations), e.g. `http://<ha-host>:8123/api/webhook/pallas_nowplaying`. **Radio** also posts there (`share: radio`, `path: radio:<stationId>`, `title` = station or BBC track). **Podcasts** post with `share: podcast`, `path: podcast:<guid>`, `title` = `Show · Episode`.

**Radio from Home Assistant:** each TV also POSTs its station list to `…/api/webhook/pallas_radio_stations` (same base URL as now-playing) on launch and when you Save Settings. Body:

```json
{"device":"lounge","stations":[{"id":"bbc_6music","name":"BBC 6 Music","tagline":"…"}]}
```

Play a station with Android TV Remote deep link (no Shield IP needed):

```yaml
action: remote.turn_on
target:
  entity_id: remote.shield_lounge
data:
  activity: "pallas://radio?name=BBC 6 Music"
```

Assist (examples): “Play Radio 6 in the Kitchen” / “Play Radio 1 Dance in the Bedroom” / “… in the Lounge”.

Or over LAN (Allow remote control on): `GET http://<shield-ip>:8765/v1/radio/stations` and `POST /v1/play` with `{"type":"radio","stationId":"bbc_6music"}`.

**Podcasts from Home Assistant:** each TV POSTs its recent episode list (from feed cache) to `…/api/webhook/pallas_podcast_episodes` on launch, Settings save, OPML import, and feed refresh. Body:

```json
{"device":"lounge","episodes":[{"guid":"…","showId":"…","showTitle":"The Show","title":"Episode","label":"The Show · Episode"}]}
```

Play via deep link (label matches the HA dropdown option), or latest episode by show name:

```yaml
action: remote.turn_on
target:
  entity_id: remote.shield_lounge
data:
  activity: "pallas://podcast?label=The Show · Episode"
# or
  activity: "pallas://podcast?show=The Upshot"
```

Assist (examples): “Play the latest The Upshot podcast in the Bedroom” / “… in the Lounge” / “… in the Kitchen”.

Refresh the catalog or seek while playing:

```yaml
activity: "pallas://podcast?refresh=1"
# or
activity: "pallas://podcast?skip=-15"   # also +15
```

Or LAN: `GET /v1/podcasts/episodes` and `POST /v1/play` with `{"type":"podcast","showId":"…","episodeGuid":"…","audioUrl":"…"}`.

**Per-device settings:** set Device id under Settings → Integrations (quick picks `lounge` / `bedroom`, or any custom id), Save. Match that id in your HA automations.

### Sleep timer standby (optional)

1. In HA: **Settings → Automations → Create automation → Webhook** — webhook ID `pallas_sleep` (URL `http://<ha>:8123/api/webhook/pallas_sleep`).
2. On each TV: **Settings → Integrations** — enable **Sleep timer → HA standby** and Save.
3. When the sleep timer expires, Pallas stops playback, fades volume, then POSTs `{"device":"<device id>","action":"standby"}` (whatever Device id you set).

Example automation action (replace entity IDs with your own):

```yaml
action:
  - choose:
      - conditions:
          - condition: template
            value_template: "{{ trigger.json.device == 'lounge' }}"
        sequence:
          - action: media_player.turn_off
            target:
              entity_id: media_player.living_room_tv
      - conditions:
          - condition: template
            value_template: "{{ trigger.json.device == 'bedroom' }}"
        sequence:
          - action: media_player.turn_off
            target:
              entity_id: media_player.bedroom_tv
```

**Voice examples:** “move the movie to the bedroom” / “move the movie to the lounge” (match your Device ids and HA automations).
