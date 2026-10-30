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
- **VOD**: the user's own `vod-organizer` container on the home server
  (`http://192.168.68.74:8000`), not Dispatcharr. It serves the provider's movies (~20,000) and
  series (~6,700) over the Xtream Codes API, regrouped into TMDb genres with TMDb posters and
  plots. Its `/movie/` and `/series/` URLs redirect (302) to the provider, which allows 5
  connections (corrected 2026-10-30); Zapparr still plays one stream at a time.
- **Player today**: TiviMate.

## Layout: three decks

`[ Apps ] -> [ Guide ] -> [ Library ]`. Left and Right move within a deck's row; at the end of a row
they slide to the next deck. They never jump diagonally into another row.

1. **Apps** *(built)*: Favorites row (defaults: the apps above, skipping missing ones; hold OK
   to add, remove, move, or open App info), Continue Watching and On now (see Phase 2 step 4),
   then every launchable app, six to a row.
2. **Guide** *(Phase 2)*: a time grid with channels (number, logo, name) down the side and time
   across the top, a now line, and filters (Favorites, Sports, News, Movies, Locals). In Phase 3, live
   video keeps playing behind it.
3. **Library** *(Phase 2)*: poster grid of movies and series with Recently added, genres, details and
   resume.

The tabs at the top work like Google TV's: Up from the top of any deck reaches its tab; Left and
Right move along a search button, Apps, Guide, Library and a Settings gear, showing each deck as its
tab is focused;
Down or OK goes back into the deck where it left off. So no deck needs paging through to leave it.
Home on Home returns to the first favorite. Back from Guide, Library or the tabs returns to Apps.
The gear opens the TV's Settings, the only way into Settings once the stock launcher is off.

## Phases

1. **Foundation** *(done)*: Gradle project (AGP 9.4, built-in Kotlin, Views with
   `androidx.leanback:leanback-grid`), HOME and LEANBACK_LAUNCHER intents, the deck pager, app
   discovery that follows installs and enable/disable, the Favorites row, and `tools/debloat.py` with
   the onn list.
2. **Dispatcharr and VOD**, in four steps, then additions asked for after them:
   1. *Connection (built)*: the server screen (address and XC login, saved only once the server
      accepts it), the Xtream Codes client, and channels plus a 13-hour guide window (1 hour back,
      12 ahead) loaded when Home opens with a new server.
   2. *Guide (built)*: one custom-drawn grid, two hours wide, starting at the current half hour:
      channel number, logo and name down the side, a now line, the focused program's details above.
      Up and Down keep to the same time (never before now); Left and Right step through programs and
      change deck past either end; CH+ and CH- page. Filters: Favorites (hold OK on a channel), All
      channels, and the server's groups. OK hands the channel to a video player. The guide reloads
      once it is an hour old.
   3. *Library*: movies and series from vod-organizer, with its own server setting (separate from
      Dispatcharr), details, play and resume. In three parts: (a) reading the library (*built*:
      `Vod`, `XcClient`, tested against replies shaped from vod-organizer's source; to check against
      the real server), (b) the Library deck (*built*): its own server screen (port 8000 when none
      is typed), Movies or Series, rows of posters (Recently added, then each genre that has any),
      the focused title's name, year, rating and genre above them, loaded once and again every 6
      hours, (c) the details screen (*built*): backdrop, plot, Play, or Resume and Start over; a
      series' seasons (shown as soon as one is focused) and episodes with Watched marks and
      progress bars, and a Play button for the episode to watch next. Players are asked where they
      stopped (see Playback handoff) and `ProgressStore` keeps the newest 200 positions on the box.
      Under a minute in starts over; past 95% counts as watched.
   4. *Continue Watching and On now (built)*, two rows of wide cards on the Apps deck, under
      Favorites, each shown only when it has cards. Continue Watching: Library titles left
      part-way (one card per series, the episode left last) mixed by time with other apps' Watch
      Next entries (not their watchlists), newest first, at most 20. OK on a Library title plays
      it at once from where it was left, over its details page; holding OK removes it. OK on
      another app's entry starts that app's own intent. Until Zapparr may read Watch Next, the
      row ends with an *Other apps' shows* card that asks (see Watch Next below). On now: the
      channels watched lately (newest first), then the favorite channels, at most 12, each with
      the program airing, its minutes left and a progress bar, updated every minute while Home
      is up. OK watches the channel.
   5. *Search (built)*, for live TV and the Library at once, from the search button left of the
      tabs or a remote's Search key. Results follow typing (a 200 ms pause), or what's said into
      the mic when the box has a speech recognizer: Channels (by name, or by number: 4 first, then
      40 to 49), On TV (programs that haven't ended, each title once per channel, on now first),
      Movies and Series (every title, those in no known category too). A name matches when it
      starts with the query, or the query starts a later word, or each typed word starts a word in
      it, in that order; case, accents and punctuation don't count. Opened from the Library deck,
      movies and series come first. OK on a channel or a program on now watches it; on a later
      program, Home's Guide opens on that channel at that time; on a movie or series, its details.
   6. *Library extras*: My List (hold OK on a poster; a row at the top of the Library),
      marking movies and episodes watched or unwatched, watched marks and progress bars on
      posters, and Up next in Continue Watching once an episode is finished.
   7. *Channel management (built)*, as TiviMate's: holding OK on a channel in the Guide offers
      Favorites, Move (Up and Down carry it, OK or Back puts it down), Rename, and hiding the
      channel or its group; holding OK on a group's filter moves it left or right or hides it.
      Settings > Guide adds Channel order (yours, by number, A to Z) and Hidden channels (show them
      dimmed, to bring them back). The arrangement (`live/Arrange.kt`, unit-tested) is kept in the
      Guide's preferences, and On now and Search see the channels as arranged.
   8. *Backup and restore (built)*, in Settings > System: Back up now (settings, favorites, the
      channel arrangement, where things were left, and the server logins unless left out) to
      Zapparr's own storage and to Downloads/Zapparr; Restore from the backups found there (asking
      first); and Phone or computer: while on, a small web page on the home network, with a code
      shown on the TV (it stops after 10 wrong codes), downloads a backup or takes one to restore.
      Most TV boxes have no file picker, and newer Android hides other apps' files, so the web page
      is how a backup reaches a new box. Checked on the emulator on 2026-10-30.
   9. *Look and settings (built)*. The gear opens Zapparr's own settings, laid out like
      TiviMate's: sections (Look, Home, Guide, Library, System) down the left, the focused one's
      options on the right; OK moves an option to its next value. System holds the TV's own
      Settings, both server screens, Reset to defaults and the version. Anything with more than
      one sensible answer is a setting:
      - *Look*: color theme (36: Zapparr's own; cable-box looks: Cable Blue, Retro Guide; the
        interfaces of Xfinity, Comcast's older guide, DirecTV, DISH, TiviMate, Plex, Emby, Kodi,
        XBMC, Windows Media Center, Threadfin, Xbox, PlayStation and Nintendo, in their colors only,
        no logos; retro terminals: Phosphor, Amber; Crimson, Graphite; and well-known themes from
        their published palettes: Dracula,
        Nord, Gruvbox, Solarized, Catppuccin, Tokyo Night, One Dark, Monokai, Rosé Pine,
        Everforest, GitHub Dark, Night Owl, SynthWave '84, Cobalt2, Kanagawa), background (a glow
        from the top-left corner, or solid), zoom on focus, a focus glow in the accent, the clock
        (12-hour, 24-hour or as the TV is set: every time Zapparr shows follows it) and the date.
      - *Home*: Continue Watching and On now shown or hidden, app names under the focused app or
        every app, roomy or compact rows.
      - *Guide*: normal, compact or roomy rows; channel numbers and logos shown or hidden; the now
        line down the grid or a notch at the top only; the aired part of programs shaded or not.
      - *Library*: titles under posters, and the backdrop (the focused poster's colors, blurred).
      A theme is a set of palette colors (`attrs.xml`, `palettes.xml`) that every screen lays over
      its theme before it draws; Home draws itself again when Settings changes anything. The app
      icon stays as it is: once Zapparr is Home, nothing else shows it except Settings > Apps,
      which always shows the original, so the theme's bolt sits in Zapparr's top bar instead.
      The cable-box polish: the bolt in the theme's accent beside search, the tabs in one pill
      (as on Google TV), a notch on the Guide's time header at the current time, the focused
      channel's number in the accent, and LIVE badges on channels and programs on now.

   M3U and XMLTV output stay a fallback for servers without the XC API.
3. **Built-in player**: Media3 (ExoPlayer) with hardware decoding; mini-guide OSD (OK or Down for
   now/next, arrows to browse channels without leaving the stream); channel numbers from remotes
   with digits and an on-screen pad for the onn remote, which has none; last-channel recall; live TV
   playing behind the menus. Asked for on 2026-10-30:
   - **Preview, then play, as in TiviMate**: OK once on a channel in the Guide starts it in the
     preview; OK again plays it full screen; Back from full screen returns to the Guide with it
     still playing in the preview.
   - **Where the preview goes, the user's choice (a setting)**: a box in a corner of the Guide
     while the grid scrolls, or the show full screen behind a see-through Guide (how see-through
     is a setting too).
   - **A mini guide over the video**, one fixed design: what's on now and next, and other
     channels, without going back to Home.
   - **One stream at a time, always**: choosing a channel stops the one playing before the new
     one starts. The preview, the background and the mini guide all show that one channel. (The
     provider allows 5 connections; Zapparr still uses one.)
   - **Movies and series stay a simple tile layout**, like Netflix, Hulu, Paramount+ or Max.

## TiviMate parity

The user doesn't want to lose anything TiviMate gives them. This is TiviMate's feature set (v5.x,
2025-26) against Zapparr's, so nothing slips through. Sources: a feature inventory on 2026-10-30
from TroyPoint's TiviMate guide, Neighborhood Nerd's settings walkthrough, the Play text mirrored on
APKMirror and AFTVnews; TiviMate's own site and the Play listing returned no text, so a few items are
unverified (marked ?). **Built**: in Zapparr now. **Next**: the next Phase 2 batch. **Phase 3**: needs
the built-in player. **Later**: after Phase 3. **Server**: done in Dispatcharr instead.

| TiviMate | Zapparr |
|---|---|
| Xtream Codes playlists | Built (Dispatcharr live, vod-organizer library) |
| M3U URL or file playlists | Later: the M3U/XMLTV fallback in Phase 2's plan |
| Several playlists | Later: one live server and one library server today |
| Update on start, update interval | Built: the guide reloads hourly and on a new server, the library every 6 h. Next: the interval as a setting |
| Extra guide (XMLTV) sources, past days kept, per-channel guide offset | Later; Server: Dispatcharr merges and matches guide data |
| Manual guide-channel matching, channel name cleanup | Server: Dispatcharr |
| Guide preview window, autoplay, stay on guide | Phase 3 (the corner preview or the see-through guide) |
| Sort channels by playlist order, name or watch time | Built: yours, by number, A to Z. Later: by watch time |
| Show channel numbers; full-height now line; highlight progress | Built: Settings > Guide |
| Enlarge the focused logo; two-line program tiles | Next |
| Catch-up icon, watch from start | Phase 3 (needs the provider's archive) |
| Info panel, side channel list, mini guide | Phase 3 (the mini guide, one fixed design) |
| Recent channels | Built: On now, the last 12 |
| Global search: channels, programs, VOD | Built |
| Favorites | Built: one list. Next: several favorites lists? |
| Reorder channels and groups, hide channels and groups, rename | Built: hold OK in the Guide, or on a group's filter |
| Custom channel logo, edit channel numbers | Next ? |
| Player: buffer, decoders, AFR, audio passthrough, external player | Phase 3 (today every stream goes to an external player) |
| Audio and subtitle tracks, audio offset, display mode, sleep timer | Phase 3 |
| Pause live TV (timeshift) | Phase 3 ? |
| Recording, scheduled and series | Later: needs storage, or Dispatcharr's own recording ? |
| Reminders (and auto-play at the start) | Next |
| Multi-view, picture-in-picture | Later: each panel is another stream, against the one-stream rule, so only as an option |
| Movies and series, by season, resume | Built (the Library, details, Continue Watching) |
| VOD favorites | Next: My List |
| Themes and colors, clock | Built: 36 color themes, 12/24-hour clock, date |
| Interface transparency | Phase 3 (the see-through guide over video) |
| Font size | Next |
| Parental control: PIN, locked channels and groups | Next |
| Remote key mapping | Later |
| Channel up/down, previous channel, number entry | Phase 3 (in the player); CH+/CH- page the guide today |
| Backup and restore (file, URL, clipboard) | Built: to the box and Downloads, and to or from a phone or computer |
| Auto start on boot | Built: Zapparr is Home |
| Start with the last channel | Phase 3, as a setting |
| Several devices, Companion app | Not needed |

## Technical decisions

- **Views, not Compose.** A launcher stays resident, and this box has 2 GB. Plain views with
  `leanback-grid` keep memory and cold start low and give TV-grade D-pad focus in rows. The EPG grid
  will be one custom-drawn view, not a view per program.
- **Signing.** Every build uses the public development key in `keystore/`, so updates install over
  each other. A launcher can't be uninstalled safely while it is Home.
- **Network.** `INTERNET`, and cleartext allowed, since Dispatcharr on a home network is usually
  plain `http://<ip>:9191`. Zapparr talks to no server but the one the user enters.
- **Few dependencies.** HTTP is `HttpURLConnection`, JSON is the platform's `org.json`, and XMLTV is
  read with the platform's pull parser, streaming and keeping only the lineup's channels inside the
  guide window. Unit tests add `org.json` and `kxml2` for the JVM.

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

What the XC replies hold, from `apps/output/views.py` and `apps/output/epg.py`:

- A bare `player_api.php` call returns `user_info` with `auth: 1` and `status: "Active"`.
- `get_live_streams`: `num` is always a whole number (fractional channel numbers are bumped to the
  next free one), `epg_channel_id` is that number as a string, `stream_icon` may be null.
- `xmltv.php`: `<channel id>` and `<programme channel>` are the same whole channel number, so they
  match `epg_channel_id`. Times are `YYYYmmddHHMMSS +0000`, titles plain text. Channels with no
  guide data get generated placeholder programs.
- `get_short_epg` (per channel, base64 titles) exists but means one request per channel; the guide
  uses `xmltv.php` instead.

Dispatcharr also serves movies and series over the same XC calls (`xc_get_vod_streams` and the
like in `apps/output/views.py`: dates in `added`/`last_modified`, `category_id` honored, episode
text in `overview`). `Vod` reads that shape too, but the user's library comes from vod-organizer.

M3U and EPG output obey Dispatcharr's network-access settings. Users get XC credentials ("XC
password") in Dispatcharr.

**Plan**: use the XC API as the main source. One login covers channels (numbers, groups and logos
as managed in Dispatcharr), EPG, catch-up and VOD with metadata, and the same client works with any
XC-compatible server. M3U and XMLTV stay as a fallback.

## vod-organizer: what the user's library server answers

Read from its source (`app.py`, as the user pasted it on 2026-10-29), not yet from the running
server. `tools/fake_vod_organizer.py` answers the same way.

- **Login**: never checked. A bare `player_api.php` call answers `auth: 1`, `status: "Active"`, and
  echoes the provider's own login to any caller on the LAN.
- **Categories**: `get_vod_categories` and `get_series_categories` both list the same 28 TMDb genres
  plus `Uncategorized`, ids `"1"` to `"28"` by name, including genres with nothing in them.
- **`category_id` is ignored**: `get_vod_streams` always returns all movies (about 20,000) and
  `get_series` all series (about 6,700). So the Library fetches each list once, streams it, and
  sorts it into categories on the box.
- **A movie**: `stream_id` (the provider's), `name` as `Title (Country) (Year)`, `stream_icon` and
  `cover` (a TMDb `w500` poster, or the provider's), `backdrop_path` (`[TMDb original]` or `[]`),
  `plot`, `rating` (a string, `"0"` for none), `year` (a string), `category_id`,
  `container_extension`. **No date**: "Recently added" goes by highest id, assuming the provider
  numbers new items upward (to check).
- **A series**: `series_id`, `name`, `cover`, `backdrop_path`, `plot`, `genre`, `releaseDate` (a
  year), `rating`, `category_id`. No date either.
- `get_vod_info`: `info` with `name`, `cover_big`, `plot`, `rating`, `releasedate` (the year) and
  `backdrop_path`; no genre (use the category's name) and no duration. `cast` and `director` are
  always empty.
- `get_series_info`: the provider's own reply passed through (with the TMDb cover and plot put into
  `info`), so episodes look like a typical XC panel's: string `id`s, the description in `plot`,
  `duration_secs` and `duration`. It asks the provider each time, so it is slower than the lists.
- **Images**: TMDb URLs. `original` backdrops can be several megabytes, so Zapparr asks TMDb for
  `w1280` backdrops, `w342` posters and `w300` episode stills.
- **Streams**: `/movie/<user>/<pass>/<id>.<ext>` and `/series/...` answer `302` to the same path on
  the provider, so `<user>` and `<pass>` must be the **provider's** login: the Library's login is the
  one TiviMate uses for this server. Only `GET` is handled; `HEAD` gets `501`.
- **Connections**: the user's provider allows 5 at a time (corrected 2026-10-30; an earlier note
  said 1). Zapparr still keeps to one: Phase 3's live video in the preview or behind the menus
  stops before anything else plays, so nothing streams twice.

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
- Zapparr asks for the permission only from Continue Watching's *Other apps' shows* card, never on
  its own: a permission prompt over Home at first start would also stop `debloat.py set-home` from
  seeing Home land on Zapparr. If the user says no, the card goes away; the permission can still be
  given in App info, or with the `pm grant` command above.
- An entry's `intent_uri` comes from another app, so before starting it Zapparr drops any selector
  and URI-permission flags, and refuses one aimed at Zapparr itself. Its pictures may be
  `content:` links, which Zapparr opens; never `file:` links.

## Playback handoff (Phase 2)

- **TiviMate**: no documented incoming "play this URL" intent. The known launch is
  `ar.tvplayer.tv/.ui.MainActivity`. Test on the box before relying on it:
  `adb shell am start -a android.intent.action.VIEW -d <stream-url> -t video/* -p ar.tvplayer.tv`.
- **Default handoff**: `ACTION_VIEW` with the stream URL and `video/*`, to a player the user picks
  (Just Player, VLC, MX Player, Kodi) or the system chooser. Phase 3 replaces this with the built-in
  player.
- **Resume** needs the player to say where it stopped, as an activity result:
  - VLC ([Android player intents](https://wiki.videolan.org/Android_Player_Intents/)): takes
    `position` (ms) and `title`; returns `extra_position` and `extra_duration` (long).
  - MX Player's API, which Just Player implements in part, including the result (its author, in
    moneytoo/Player issue #203): takes `position` and `return_result`; returns `position`,
    `duration` and `end_by`. Not documented by Just Player itself: check on the box.
- **Recent channels**: Zapparr records what it tunes, from the Guide or On now, for the On now
  row (the last 12). Knowing what TiviMate played would mean polling Dispatcharr's active streams
  (untested).

## Debloat rules (any Android TV or Google TV box)

`tools/debloat.py` enforces them; [docs/debloat.md](debloat.md) is the user's guide. It detects the
platform (Google TV by `launcherx`, Android TV by `tvlauncher` or `leanbacklauncher`, else other),
the brand, model and Android version, and the stock launcher, falling back to whatever answers
Home. Lists layer: one per platform (`google-tv.txt`, `android-tv.txt`, `other.txt`), plus vendor or
model lists whose `match:` line fits (onn, Chromecast, NVIDIA SHIELD, Xiaomi, Sony, TCL, Hisense,
Philips), each citing its sources (CleanYourTV's tested lists, rcland12's). Checked on the Google TV
14 emulator on 2026-10-30: probe recognised it and picked the Google TV and Chromecast lists,
set-home made Zapparr Home, and restore gave Home back to the stock launcher. Not yet run on a
real box.

- Disable, never uninstall (`pm disable-user --user 0`); record each change before making it;
  `restore` undoes it.
- **Setup wizard** (`com.google.android.tungsten.setupwraith` on Google TV; `com.android.provision`
  elsewhere) owns every Wi-Fi network saved during setup, and disabling it deletes them. Forget and re-join each saved network (out-of-range ones too)
  first. Its recovery screen takes Home once the launcher is gone, and it re-enables the launcher, so
  it is disabled together with the stock launcher, by `set-home`.
- **Stock launcher**: Google TV's launcher keeps Home while it is enabled, even after
  `cmd package set-home-activity` reports success (seen on the Google TV 14 emulator). So `set-home`
  first checks that Zapparr starts, then disables the launcher, presses Home, and undoes everything
  at once if Home doesn't land on Zapparr.
- **Never touched** (`PROTECTED`, `PROTECTED_PREFIXES`): Android's system apps and providers, Play
  Services and Play Store, TV Settings, keyboards, TvProvider, WebView, DRM key provisioning, the
  file picker, network services, the remote and Bluetooth services, the vendors' HDMI, tuner,
  picture and remote services (Amlogic, MediaTek, MStar, Realtek, Rockchip, Sony and others),
  `com.gretzky.GlobalKey` (the onn remote's app buttons and mic), and anything that created a saved
  Wi-Fi network.
- **Known trade-offs** (`probe` prints those for the box): casting stops while the setup wizard is
  disabled (the Cast receiver reads its credentials from it); the remote's YouTube button does
  nothing without the stock launcher; on one onn unit, voice search for titles needed the stock
  launcher (app commands still worked).

## Corrections to the brief

| The brief said | What's true | Change |
|---|---|---|
| Hand streams to TiviMate with `ACTION_VIEW` for `video/*` | Not documented by TiviMate; unverified | Hand off to a chosen player; test TiviMate with the command above |
| Read Watch Next from TvProvider | True, but only with `READ_TV_LISTINGS` and only for apps that publish there | Ask for the permission; check which apps publish on the box |
| VOD from the `.strm` scraper and a `catalog.json` | The user's vod-organizer serves movies and series with TMDb metadata over the XC API | Read vod-organizer's XC API, with its own server setting |
| Leanback project | `androidx.leanback`'s full UI library isn't needed | Plain views plus `leanback-grid` for rows |
| minSdk 26 | Kept. The SDK needs compileSdk 37 for current AndroidX; targetSdk stays 36 | |
| Stock launcher is `launcherx` | Only on Google TV; the 2021 onn Android TV box uses `tvlauncher` | `debloat.py` handles both |

## Known issues

- **Down doesn't keep the column.** Moving Down from a Favorites tile lands on the first tile of All
  apps, because each leanback row remembers its own position. A cable-box feel would keep the column.

## Open questions

1. The onn 4K (1st gen, not the Pro): the `probe` output will confirm the Android version and stock
   launcher (`python tools/debloat.py --host <ip> probe`).
2. *Answered*: Live TV comes from Dispatcharr's XC API; movies and series from vod-organizer's XC
   API (corrected 2026-10-29: not from Dispatcharr). Local news apps are added from the Apps deck (hold OK), not
   as defaults.
3. Does TiviMate accept a stream URL? Run the test command above.
4. Do Prime Video and Paramount+ publish Watch Next rows on this box? Press *Other apps' shows*,
   allow it, watch something in each app for a few minutes, then see whether it shows in Continue
   Watching.
5. Does the player on the box hand back where it stopped? Play a movie from the Library for a few
   minutes, press Back, and the details page should offer *Resume from* that time. Checked against
   VLC's documentation and MX Player's API (which Just Player follows in part), not yet on a box:
   the emulator has no real player.
