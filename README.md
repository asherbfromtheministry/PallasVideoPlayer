# PallasVideoPlayer

Android TV app for NVIDIA Shield that browses Synology SMB3 shares and plays files in VLC, with Trakt metadata and TMDB fanart cards styled like the Home Assistant *UPCOMING TV* rows. Also includes **Live TV (IPTV)** with M3U playlists and XMLTV EPG.

## Stack (read this first — agents)

| | |
|--|--|
| **Platform** | Native **Android TV** (NVIDIA Shield, Google TV / Chromecast) |
| **Language / UI** | **Kotlin** + **Jetpack Compose** under `app/src/main/java/…` |
| **Build** | Gradle (`.\gradlew.bat :app:assembleDebug`) → APK, install via **ADB** |
| **Not this repo** | **Not** Svelte, SvelteKit, React, web, or Node. There are no `.svelte` files and no frontend npm app. |

If Cursor injects Svelte MCP tools, Svelte skills, or “always apply” Svelte rules from a plugin, **ignore them for this workspace**. Do not load Svelte docs, run `@sveltejs/mcp`, or treat UI work as a web component task. Edit Compose screens (e.g. `ui/radio/RadioScreen.kt`) instead.

## Download (end users)

Get the latest **clean** APK from [Releases](../../releases) — no personal NAS, keys, or IPTV baked in. On Android TV / Google TV / NVIDIA Shield:

1. Enable **Unknown sources** / allow installs from your browser or file manager.
2. Install the APK, open **PallasVideoPlayer**, open the **gear**.
3. Enter your Synology host, credentials, Trakt/TMDB keys, and optional IPTV playlist.

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

Use your own Android TV IPs with ADB. ADB lives at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (or `adb` on PATH).

If port `5555` is refused, the device may use **wireless debugging** with a dynamic port — read it from Developer options → Wireless debugging and `adb connect <ip>:<port>`.

**Chromecast notifications:** Android 14 needs `POST_NOTIFICATIONS`. Grant over ADB: `adb shell pm grant com.vizvag.shieldvideo android.permission.POST_NOTIFICATIONS` and `adb shell cmd notification allow_listener com.vizvag.shieldvideo/com.vizvag.shieldvideo.playback.ShieldNotificationListener`.

## Build

```powershell
cd c:\temp\videoplayer
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
cd c:\temp\videoplayer
.\gradlew.bat :app:assembleClean
```

Clean APK path (debug-signed, installs like any other debug build):

`app\build\outputs\apk\clean\app-clean.apk`

The latest clean APK is published to the NAS download share as `\\DiskStation\download\PallasVideoPlayer-clean-v<versionName>.apk`.

## Update / install on all devices

One-time per device:

- **Shield:** Settings → Device Preferences → About (click Build 7×) → Developer options → enable **Network debugging**
- **Kitchen Chromecast (Google TV):** Settings → System → About → click Build 7× → Developer options → enable **USB debugging** / **Network debugging** (wording varies)

Accept the “Allow USB debugging?” prompt on the TV the first time this PC connects.

Then from PowerShell (build + push). Replace the IPs with your TVs:

```powershell
cd c:\temp\videoplayer
.\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = "c:\temp\videoplayer\app\build\outputs\apk\debug\app-debug.apk"
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

After first install: open **PallasVideoPlayer** → **gear** → enter NAS credentials → **Test connection** → set Device id if using HA handoff (`lounge` / `bedroom`) → **Save**.

## UI

- **v2.1 layout:** **Option C split** for all NAS browse (list left, cinematic preview right). Music uses **Option D immersive** (full-bleed now-playing; Browse slides in from the left, Playlist from the right). Radio keeps Option C split. Option B focus-stage was not used.
- **v2.0 remake:** cinema near-black + luminous lime (video) and indigo-black + gold (Music/Radio); Google TV–scale type; soft glass surfaces; white focus rings with accent glow — not the old olive/muddy HA look
- **Motion:** cinematic route transitions, snappy focus springs, staggered list entrances, theater-mesh ambient light
- **Sound:** system navigation/click cues plus a soft click tone on select
- Top icon row: share/folder switcher moved into the **left nav rail** on Browse (shares, recordings, Live TV, Radio, Music, sleep, settings, up). **Search** lives on the NAS video browse page (list header), not the shared rail
- Search: query across folders via Index (fast) or Raw scan; index prefers Synology **Video Station**, falls back to folder walk; auto-updates every 24h
- Settings: tabbed groups — **NAS** · **Library** · **Playback** · **Live TV** · **Radio** · **Integrations** · **Backup** (Save stays in the header). **Playback** includes on-screen **clock corner** (subtle updating time + long date; default bottom right)
- Settings backup: **Settings → Backup** selects a private NAS folder and exports/imports `PallasVideoPlayer-settings.json`. The portable backup contains NAS credentials, Trakt/TMDB keys and tokens, IPTV playlists, custom radio stations, favorites, custom channel order/names, manual EPG assignments, measured stream badges, and parental settings. Import replaces those values so another TV has the same setup; if this TV already has a Device id (`lounge` / `bedroom`), that id is kept so HA handoff stays correct. Caches and viewing/search history are rebuilt locally. The JSON contains secrets in readable form, so store it only in a private NAS folder. File transfer uses SMB3 on port 445 automatically; when the app is configured for HTTP browsing, this is a temporary in-memory connection and the saved HTTP mode/port are never changed.
- Browser: hero + Continue Watching / Folders / Videos poster shelves; **folder tiles use Trakt/TMDB art only when every video inside is the same title** (season packs); mixed bins like `Films` and top-level `/video` categories use generic icon tiles; path still navigates with Back / rail Up
- **`.rar` archives:** shown only when a folder has **no video files**; **Hold OK** opens Extract (into the same folder via File Station; the `.rar` is kept). Short OK does not play in VLC. Extract runs on the NAS; progress is overall volumes done/total for multi-part sets (not per-volume). **Hide** (or Back) while extracting minimizes to a slim top progress bar so you can browse/watch; OK on the bar reopens the dialog
- Search / Settings: glass chrome, underline tabs, ambient backdrop
- Music / Radio: Music is a **ground-up Option D immersive stage** (v2.4) — blurred album-art backdrop, **square track art on the left**, title/artist/progress/transport on the right (scales down when a side panel is open); **Browse** expands from the left and **Queue/Playlist** from the right, each pushing the player so both panes fit; close restores full-width player. Radio keeps Option C split
- Horizontal media cards remain in Search results; browse uses portrait posters on shelves
- Trakt matches require title-token coverage and an exact year match when the filename includes a year
- Long-press OK on a **file** tile (or Menu) clears wrong Trakt/TMDB metadata; long-press again restores lookup
- Long-press OK on a **folder** with Trakt art clears it **and every file inside**; long-press on a folder **without** art opens an assign picker (Trakt search candidates) so you can pick the TV show/movie or **Keep empty** — the assignment applies to that folder, **all nested subfolders**, and **all files** under it
- Folder titles keep season labels (e.g. `The Musketeers S01`); release junk is still stripped
- Manual folder assignments are remembered until cleared again
- Nested folders: open folder / Back / remote Back to go up
- Folders named `screens` are hidden (browse, search, and index)

## Live TV (IPTV)

- **Playlists:** Settings → IPTV — add/switch multiple M3U URLs; each can have its own EPG URL
- **Browse:** full-bleed live preview with nested snap-scroll wheels — groups first, OK opens the **bottom-docked channel/EPG guide** (title + time ruler above the list; programmes use subtle typography and a 0.5dp outline to show their end boundaries, with the airing programme distinguished by cyan text; **Right** +30m / **Left** earlier, then groups). **Hold OK / Menu on a group** opens persistent group options: channel order (**Custom**, **Alphabetical**, or **Most watched**), **Rename group**, **Move group…** (pick it up, Up/Down carry it through the wheel, OK/Back drops it — same interaction as moving a channel), and **Hide group** / **Show group**; custom names appear in the group list, guide title, and fullscreen HUD while the provider key remains unchanged internally. Hidden groups sink greyed-out to the bottom of the wheel (subtitle "Hidden — hold OK to show") so they stay reachable for un-hiding; group order and hidden state are per playlist and included in settings backups. Most-watched counts build whenever a channel is played. The focused row uses a soft background rather than a thick white border. Preview is out of the D-pad focus chain — **Down** from the top bar returns to the guide. Toggle **Show EPG in preview** and **Preview guide size** under Settings → Live TV. Scroll browses; **OK** plays / fullscreen. **Hold OK / Menu** opens Assign EPG · Record · Favorite · Rename · Move channel · VLC (rename/order/EPG mapping are saved per playlist). **Move channel…** picks the channel up (amber ring): **Up/Down** carry it through the group — hold for fast travel — and **OK** or **Back** drop it in place.
- **Search:** channels + EPG programme titles — channel results show quality badges (4K/FHD/HD) and favorite state; **OK** previews the channel, **hold OK / Menu** adds/removes favorite. The 10 most recent searches are saved per playlist and shown when the query is empty.
- **Quality badges:** while a channel plays, the real resolution / fps / HDR are measured from the decoder output (fullscreen + preview HUDs) and cached per channel; guide rows, search results, and the history list show confirmed badges in cyan for channels watched before, falling back to grey name-derived hints (e.g. `4K`, `50fps`, `HDR` tokens in the channel name) otherwise. While zapping, the HUD keeps showing the previously measured badges for a channel until the new live measurement is confirmed (~1s of rendered frames), so the resolution no longer appears to flip back to the name-declared value on every channel change. Fullscreen also shows the selected audio layout (`Mono`, `Stereo`, `5.1`, `7.1`, `Dolby Atmos`, etc.) in an orange badge, plus a `♫ N` indicator when multiple audio tracks are available. Hold **OK** or press **Menu** in fullscreen for detailed stream information: video codec, resolution, frame rate, bitrate, audio codec (of the selected track, e.g. AAC / Dolby Digital / MP2), layout, sample rate, language, support, and per-track details.
- **EPG / catch-up:** Playlist/EPG under `iptv_cache/` with a fast channel snapshot (`.idx`). Prefetched at app start; Live TV UI opens immediately from local data (no spinner), then EPG/network refresh in the background. Force refresh still re-downloads. **Assign EPG…** on long-press maps an XMLTV channel (searchable list) when M3U `tvg-id` is missing/wrong; assignments are persisted per playlist using a stable channel identity (tvg-id + name, with and without group) and are self-healed onto those keys when Live TV loads — older assignments keyed only by URL-hash channel ids survive provider URL rotation and settings import on another device; the XMLTV channel index is de-duplicated by id (some EPG feeds declare the same channel twice, which crashed the Assign EPG search list); catch-up when playlist provides catchup tags
- **HDR:** fullscreen playback uses a SurfaceView so HDR streams switch the TV to HDR mode (browse preview is TextureView → SDR only, needed for Compose overlay clipping)
- **Screensaver:** Live TV preview, fullscreen playback, and multiview keep the display awake on Shield and Google TV/Chromecast; normal ambient/screensaver behavior resumes when the player view closes
- **Sleep timer** (timer icon): shared across **NAS browser**, **Live TV**, and **Radio** — cycles **15 → 30 → 45 → 60 → 90 → Off**; countdown stays visible when you switch screens; volume fades over the last minute then stops the active player (VLC for NAS, Live TV / Radio in-app). Optional **Home Assistant standby**: Settings → Integrations → **Sleep timer → HA standby** — POSTs to `…/api/webhook/pallas_sleep` with `{"device":"lounge"|"bedroom","action":"standby"}` (derived from the now-playing webhook URL).
- **Playback history:** Live TV resumes the last watched channel for each playlist. In fullscreen, **Up** previous / **Down** next channel in the current group (wraps), **Right** jumps back one channel, and **Left** opens a combined history containing the 10 most recent IPTV channels and 10 most recent NAS videos. Selecting a channel zaps in-app; selecting a NAS video opens the configured external player at its saved resume position. HUD shows the channel name/group/badges at the top and a bottom EPG tile (Now title + description + progress bar, Next title, with times) briefly
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
- **Layout:** landscape stage — **title (white)** and **artist (gold)** on separate rows; if **Album Artist** differs from **Artist**, both are shown (album artist slightly quieter); album + muted **Track xx**; type/controls scale with pane width. Top chrome: Music · Browse · Queue · Sleep · Black screen. **Browse** (left) / **Queue** (right) push the player (~30% side panels) so both panes fit. Folder **hold OK** → Play folder / Add to playlist. Queue: album-grouped setlist with cover thumbs, live EQ on the playing row, remaining-time progress rail, **UP NEXT** marker, **hold OK** to pick up, **Up/Down** to move, OK to drop. Browse defaults to **Artists**; empty library shows Sync.
- **Folders** tab (default in Library): browse configured music NAS folders like a file tree — with multiple folders, the root lists each path; **OK** opens a folder; **hold OK** for Play now (clear playlist) · Add to playlist · Open
- **Hold OK** menu: Play now · Add to playlist only (Back dismisses; OK already opens folders)
- **Clickable MP3 metadata** on the player: live-reads ID3 tags for the playing track (artist, album artist, album, year, genre, track/disc, composer, lyricist, conductor, publisher, grouping, mood, BPM, ISRC, codec/bitrate/sample rate, comment, …). Browseable fields open matching albums on the left; playlist stays on the right
- **Folders:** **Play folder** and **Add to playlist** chips on the current folder; hold OK on a row for Play now · Add. Status line confirms how many tracks were added
- **Add to playlist** starts playback if nothing is playing (leaving Music stops audio but keeps the queue)
- **Random** chip: play a random album, artist, or genre
- Leaving Music (Back) **stops playback**; in Library Folders, Back goes up one folder first, then returns to the player
- **Sleep timer** works while music is playing (same shared timer as NAS / Live TV / Radio)
- **Black screen** (moon icon): blanks the display while audio keeps playing — fully black; **OK** or **Back** wakes the UI (Back while black does not leave Music)

## Radio

- Top-bar **Radio** icon (next to Live TV) opens a dedicated internet-radio player
- **All streams are user-managed** in Settings → Radio — add, edit, or delete any station; nothing is hard-coded in the player. On first install, BBC national stations are seeded once (Radio 1, 1Xtra, Radio 1 Dance, Radio 2, Radio 3, Radio 4, Radio 4 Extra, Radio 5 Live, 6 Music, World Service); tap **Add BBC defaults** to restore any you removed
- **Now playing metadata** from the BBC Sounds API when a station has a BBC metadata id set (not in the HLS stream): music stations show the **current show name** (e.g. Radio 1 Breakfast), artist, track, artwork, and recently played; speech stations show programme title, episode, synopsis, and a progress bar. Metadata is polled every 30s and works outside the UK (`experience=international` first, then domestic)
- **Find on NAS:** OK on the now-playing **artist** or **track** (or a recently-played row) switches to Music and opens Browse filtered to matching **artists** / **song titles** (uses the in-memory library; spinner only until first Room load after process start)
- Station chips and the player header use bundled **BBC station logos** (from official brand artwork) when the station id matches a known BBC default; other stations keep the short-letter badge
- Station-coloured dial, animated equaliser ring, ON AIR / TUNING status, and a horizontally scrollable row of D-pad-friendly station cards
- **Geo-block handling:** if the 320 kbps UK stream is blocked, the player automatically falls back to 96 kbps then 48 kbps international feeds and shows which bitrate is active
- **Settings → Radio:** name, tagline, stream URL, optional BBC metadata id for each station; included in settings backup/import
- Play / pause from the centre button; last station is remembered; screensaver stays off while listening
- **Record** (red button next to play/pause): captures the live stream while you listen and saves it to the same **Recording storage** folder as Live TV (Settings → Live TV — Local or NAS). Press again to stop and save. **Hold OK** while recording opens **Stop after** presets (15 / 30 / 45 / 60 / 120 min); the REC pill shows the remaining time. With a timed stop set, leaving Radio keeps recording in the background until it expires (or you stop it). Switching station or the sleep timer finishing still stop and save. Filenames use `yyyy-MM-dd_HH-mm - Station - Programme.mp4`
- **Sleep timer** (timer icon in the Radio top bar): same shared timer as NAS / Live TV — countdown continues if you started it elsewhere; fades then pauses radio
- **Black screen** (moon icon): blanks the display while audio keeps playing — fully black (no wake hint text); **OK** or **Back** wakes the UI (Back while black does not leave Radio)
- Music has the same **Black screen** moon control as Radio
- Station logos: bundled BBC artwork including **Radio 1 Dance**
- Default BBC URLs live in `RadioDefaults.kt` (seed/restore only); if a station goes silent after a BBC CDN change, edit the stream URL in Settings or re-add BBC defaults

## Home Assistant → VLC / move between Shields

Browse/play on one Shield can use **HTTP or SMB**. Cross-TV move streams through a foreground **LAN HTTP proxy** on the target (Pallas authenticates to the NAS over SMB; VLC plays `http://<shield-ip>:…` with Range/resume). Bedroom TV must be awake (the move script turns the Shield on first).

### Cross-TV resume (NAS sidecars)

While you watch a NAS video, Pallas writes a tiny sidecar next to the file (e.g. `Movie.mkv` → `Movie.mkv.pallas.json`) over SMB. Other TVs read it when browsing or playing, so resume position and “watched” dimming stay in sync across Lounge / Bedroom / Kitchen. Sidecars never appear in the app browse/search UI. The NAS `shield` user needs write permission on the video shares. Progress is also kept locally; NAS writes are throttled (~30s) and flushed when playback stops.

```text
pallas://play?share=download&path=<encoded relative path>&host=<nas-host>&title=&position=
pallas://stop
```

While playing (notification access on), each Shield posts now-playing to your Home Assistant webhook (configure under Settings → Integrations), e.g. `http://<ha-host>:8123/api/webhook/pallas_nowplaying`.

**Per Shield settings:** set Device to `lounge` or `bedroom`, Save.

### Sleep timer standby (optional)

1. In HA: **Settings → Automations → Create automation → Webhook** — webhook ID `pallas_sleep` (URL `http://<ha>:8123/api/webhook/pallas_sleep`).
2. On each Shield: **Settings → Integrations** — enable **Sleep timer → HA standby** and Save.
3. When the sleep timer expires, Pallas stops playback, fades volume, then POSTs `{"device":"lounge"|"bedroom","action":"standby"}`.

Example automation action (replace entity IDs):

```yaml
action:
  - choose:
      - conditions:
          - condition: template
            value_template: "{{ trigger.json.device == 'lounge' }}"
        sequence:
          - action: media_player.turn_off
            target:
              entity_id: media_player.shield_lounge
      - conditions:
          - condition: template
            value_template: "{{ trigger.json.device == 'bedroom' }}"
        sequence:
          - action: media_player.turn_off
            target:
              entity_id: media_player.shield_bedroom
```

**Voice:** “move the movie to the bedroom” / “move the movie to the lounge”
