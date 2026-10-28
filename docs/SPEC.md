# Zapparr spec

The project brief, kept up to date as the build goes. Where the original brief turned out to be
wrong or untested, the change and the evidence are in [Corrections to the brief](#corrections-to-the-brief).

## Goal

A home screen for a budget Android TV box that works like a cable box: the user's apps, a full live
guide and a VOD library in one place, fast on 2 GB of RAM, with none of Google TV's ads, sponsored
rows or "For You" guessing. Plus a reversible debloat of the box itself.

- **Repo**: [R-E-Miller/zapparr](https://github.com/R-E-Miller/zapparr), GPL-3.0.
- **App**: `tv.zapparr`, Kotlin, minSdk 26, a HOME and LEANBACK_LAUNCHER activity.
- **Target box**: onn 4K Streaming Box (Walmart, 2023): Google TV 14 (SDK 34), Amlogic, 2 GB RAM,
  8 GB storage, stock launcher `com.google.android.apps.tv.launcherx`. The 2021 onn Android TV box
  (stock launcher `com.google.android.tvlauncher`) should work too; `tools/debloat.py probe` says
  which one you have.

## The user's setup

- **Apps**: Prime Video (`com.amazon.amazonvideo.livingroom`), Paramount+ (`com.cbs.ott`), TiviMate
  (`ar.tvplayer.tv`), YouTube (`com.google.android.youtube.tv`), YouTube Music
  (`com.google.android.youtube.tvmusic`), local news apps (package names not yet known).
- **Live TV**: a self-hosted Dispatcharr server: stream proxy, channel numbers and groups, logos,
  XMLTV EPG.
- **VOD**: a custom script that scrapes the provider's VOD into `.strm` files for TiviMate.
- **Player today**: TiviMate for live TV and `.strm` playback.

## Layout: three decks

`[ Apps ] -> [ Guide ] -> [ Library ]`. Left and Right move within a deck's row; at the end of a row
they slide to the next deck. They never jump diagonally into another row.

1. **Apps** *(Phase 1, built)*: Favorites row (defaults: the apps above, skipping missing ones;
   hold OK to add, remove, move, or open App info), then every launchable app, six to a row.
   *Planned*: a Continue Watching row from Watch Next (see below) and an "On now" row of recent
   channels with what's airing.
2. **Guide** *(Phase 2)*: a time grid with channels (number, logo, name) down the side and time
   across the top, a now line, and filters (Favorites, Sports, News, Movies, Locals). In Phase 3, live
   video keeps playing behind it.
3. **Library** *(Phase 2)*: poster grid of movies and series with Recently added, genres, details and
   resume.

Home on Home returns to the first favorite. Back from Guide or Library returns to Apps. A gear in
the clock bar opens the TV's Settings, the only way into Settings once the stock launcher is off.

## Phases

1. **Foundation** *(done)*: Gradle project (AGP 9.4, built-in Kotlin, Views with
   `androidx.leanback:leanback-grid`), HOME and LEANBACK_LAUNCHER intents, the deck pager, app
   discovery that follows installs and enable/disable, the Favorites row, and `tools/debloat.py` with
   the onn list.
2. **Dispatcharr and VOD**: Xtream Codes client for live, EPG, movies and series; M3U and XMLTV as a
   fallback; the guide grid; the Library grid; Continue Watching from Watch Next; external player
   handoff; a settings screen for the server address and login.
3. **Built-in player**: Media3 (ExoPlayer) with hardware decoding; mini-guide OSD (OK or Down for
   now/next, arrows to browse channels without leaving the stream); channel numbers from remotes
   with digits and an on-screen pad for the onn remote, which has none; last-channel recall; live TV
   playing behind the menus.

## Technical decisions

- **Views, not Compose.** A launcher stays resident, and this box has 2 GB. Plain views with
  `leanback-grid` keep memory and cold start low and give TV-grade D-pad focus in rows. The EPG grid
  will be one custom-drawn view, not a view per program.
- **Signing.** Every build uses the public development key in `keystore/`, so updates install over
  each other. A launcher can't be uninstalled safely while it is Home.
- **No network permission yet.** Phase 2 adds `INTERNET` along with the Dispatcharr client.

## Dispatcharr: what the server actually exposes

Checked against Dispatcharr's source (`dispatcharr/urls.py`, `apps/output/urls.py`,
`apps/output/views.py`) and docs. Default port 9191.

| What | Endpoint | Notes |
|---|---|---|
| M3U | `/output/m3u` or `/output/m3u/<profile>` | `tvg_id_source` = `channel_number` (default), `tvg_id` or `gracenote`; `cachedlogos=false` for direct logo URLs; `direct=true` for raw provider URLs (trusted/local use) |
| XMLTV | `/output/epg` or `/output/epg/<profile>` | `days`, `prev_days` |
| Xtream Codes API | `/player_api.php?username=&password=&action=` | actions: `get_live_categories`, `get_live_streams`, `get_short_epg`, `get_simple_data_table`, `get_vod_categories`, `get_vod_streams`, `get_vod_info`, `get_series_categories`, `get_series`, `get_series_info`. Also `/get.php`, `/xmltv.php` |
| Streams | `/live/<user>/<pass>/<channel_id>`, `/movie/<user>/<pass>/<id>.<ext>`, `/series/<user>/<pass>/<id>.<ext>`, `/timeshift/...` | VOD is proxied through Dispatcharr |
| REST API | `/api/`, docs at `/api/swagger/` | |

M3U and EPG output obey Dispatcharr's network-access settings. Users get XC credentials ("XC
password") in Dispatcharr.

**Plan**: use the XC API as the main source. One login covers channels (numbers, groups and logos
as managed in Dispatcharr), EPG, catch-up and VOD with metadata, and the same client works with any
XC-compatible server. M3U and XMLTV stay as a fallback.

## Watch Next (Continue Watching): how a third-party launcher can read it

Checked against AOSP `TvProvider` (`TvProvider.java`, `AndroidManifest.xml`):

- `android.permission.READ_TV_LISTINGS` is declared by TvProvider with
  `protectionLevel="dangerous"`: a normal runtime permission the app can ask for, or that can be
  granted with `adb shell pm grant tv.zapparr android.permission.READ_TV_LISTINGS`. Projectivy asks
  for the same thing at setup.
- With it, a query on `watch_next_program` (or preview programs) returns rows where
  `package_name` is the caller **or `searchable = 1`**, and `searchable` defaults to 1. So Zapparr
  can see other apps' Watch Next rows unless an app opts out.
- Callers without `ACCESS_ALL_EPG_DATA` (signature|privileged, not grantable) can't pass a
  selection ("Selection not allowed") and their sort order is validated, so query everything and
  filter and sort in the app.
- Only apps that publish to TvProvider appear. Whether Prime Video and Paramount+ do on this box is
  untested: check on the device before building the row around them.

## Playback handoff (Phase 2)

- **TiviMate**: no documented incoming "play this URL" intent. The known launch is
  `ar.tvplayer.tv/.ui.MainActivity`. Test on the box before relying on it:
  `adb shell am start -a android.intent.action.VIEW -d <stream-url> -t video/* -p ar.tvplayer.tv`.
- **Default handoff**: `ACTION_VIEW` with the stream URL and `video/*`, to a player the user picks
  (Just Player, VLC, MX Player, Kodi) or the system chooser. Phase 3 replaces this with the built-in
  player.
- **Recent channels**: Zapparr records what it tunes. Knowing what TiviMate played would mean polling
  Dispatcharr's active streams (untested).

## Debloat rules (onn 4K 2023)

From the tested CleanYourTV list for this box; `tools/debloat.py` enforces them.

- Disable, never uninstall (`pm disable-user --user 0`); record each change before making it;
  `restore` undoes it.
- **Setup wizard** (`com.google.android.tungsten.setupwraith`) owns every Wi-Fi network saved during
  setup, and disabling it deletes them. Forget and re-join each saved network (out-of-range ones too)
  first. Its recovery screen takes Home once the launcher is gone, and it re-enables the launcher, so
  it is disabled together with the stock launcher, by `set-home`.
- **Stock launcher**: Google TV's launcher keeps Home while it is enabled, even after
  `cmd package set-home-activity` reports success (seen on the Google TV 14 emulator). So `set-home`
  first checks that Zapparr starts, then disables the launcher, presses Home, and undoes everything
  at once if Home doesn't land on Zapparr.
- **Never touched**: `com.gretzky.GlobalKey` (the onn remote's app buttons and mic),
  `com.droidlogic.*` (HDMI, picture, remote pairing), Play Services and Play Store, TV Settings,
  keyboards, the remote and Bluetooth services, TvProvider.
- **Known trade-offs**: on one unit, voice search for titles needed the stock launcher (app commands
  still worked); casting with the setup wizard disabled is untested on the onn.

## Corrections to the brief

| The brief said | What's true | Change |
|---|---|---|
| Hand streams to TiviMate with `ACTION_VIEW` for `video/*` | Not documented by TiviMate; unverified | Hand off to a chosen player; test TiviMate with the command above |
| Read Watch Next from TvProvider | True, but only with `READ_TV_LISTINGS` and only for apps that publish there | Ask for the permission; check which apps publish on the box |
| VOD from the `.strm` scraper and a `catalog.json` | Dispatcharr now serves movies and series with metadata over the XC API | XC first; the `.strm` catalog becomes the fallback |
| Leanback project | `androidx.leanback`'s full UI library isn't needed | Plain views plus `leanback-grid` for rows |
| minSdk 26 | Kept. The SDK needs compileSdk 37 for current AndroidX; targetSdk stays 36 | |
| Stock launcher is `launcherx` | Only on Google TV; the 2021 onn Android TV box uses `tvlauncher` | `debloat.py` handles both |

## Known issues

- **Down doesn't keep the column.** Moving Down from a Favorites tile lands on the first tile of All
  apps, because each leanback row remembers its own position. A cable-box feel would keep the column.

## Open questions

1. Which onn box is it, exactly? `python tools/debloat.py --host <ip> probe` prints the model,
   Android version and stock launcher.
2. Is the IPTV provider an Xtream Codes account in Dispatcharr? If so, Dispatcharr's VOD may replace
   the `.strm` scraper.
3. The local news apps' package names, for the default Favorites row
   (`adb shell pm list packages | findstr -i news`).
4. Does TiviMate accept a stream URL? Run the test command above.
5. Do Prime Video and Paramount+ publish Watch Next rows on this box?
