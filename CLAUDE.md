# Zapparr

A cable-box style Android TV launcher (Apps, Guide, Library decks) fed by Dispatcharr, plus a
reversible debloat tool for Android TV and Google TV boxes (made on an onn 4K). Read
[docs/STATUS.md](docs/STATUS.md) for where things stand, then [docs/SPEC.md](docs/SPEC.md): it holds
the plan, the verified Dispatcharr and TvProvider facts, and the open questions.

## Layout

- `app/src/main/java/tv/zapparr/home/`: `HomeActivity` (the HOME activity), `DeckPager` (decks,
  Left/Right at row edges, Up to the tabs), `Decks`, `ServerFeed` (keeps a deck loaded from its server),
  `HomeRows` (the Apps deck's Continue Watching and On now cards), `ContinueWatching` (what that row
  shows; unit-tested), `WatchNext` (other apps' Watch Next entries from TvProvider).
- `app/src/main/java/tv/zapparr/apps/`: app discovery (`AppRepository`), the Favorites rules and
  store, the Apps deck rows and tiles, `Cards` (the wide cards of Continue Watching and On now),
  launch helpers.
- `app/src/main/java/tv/zapparr/live/`: the Xtream Codes API, for Dispatcharr (live TV) and the
  library server (movies and series). `Server` (address, login, `ServerKind`), `Xc` (URLs and JSON
  replies), `Xmltv` (streaming guide reader), `Vod` (movies and series), `XcClient` (HTTP, streaming
  the big lists), `ServerActivity` (the connect screen, for either kind), `Images` (logos and
  posters), `watch` (hands a stream to a video player). The Guide deck: `Guide` (cells, scrolling
  and filters, unit-tested), `GuideView` (the custom-drawn grid), `GuideDeck` (details, filters,
  favorite and recent channels), `OnNow` (the On now row's channels; unit-tested).
- `app/src/main/java/tv/zapparr/look/`: Zapparr's settings and themes. `Look` and `Option` (every
  setting and its values; unit-tested), `Looks` (the store, the 36 `Palettes`, `applyLook`, which
  each screen calls first in `onCreate`), `SettingsActivity` (the gear's TiviMate-style screen),
  `PalettesActivity` and `PaletteSwatch` (the theme picker). Colors come from theme attributes
  (`?attr/zAccent` and the like in `res/values/attrs.xml`), never `@color/` directly: each
  `Palette.*` style in `palettes.xml` sets them all.
- `app/src/main/java/tv/zapparr/search/`: `SearchActivity` (one search through live TV and the
  Library), `Search` and `SearchIndex` (matching and ranking; unit-tested), `SearchRows`.
- `app/src/main/java/tv/zapparr/library/`: the Library deck: `LibraryDeck` (Movies or Series, rows
  of posters), `PosterRows`, `DetailsActivity` (a movie or a series, its seasons and episodes, Play
  and Resume), `Resume` (reading players' results, what counts as watched, the next episode;
  unit-tested), `ProgressStore` (where each movie and episode was left).
- `tools/debloat.py`: probe, plan, apply, set-home, restore over adb, for any Android TV or Google TV
  box. `tools/lists/`: a list per platform plus vendor and model lists (`match:` lines), with sources.
- `app/src/main/java/tv/zapparr/backup/`: backup and restore. `Backup` (the file format; unit-tested),
  `Backups` (Zapparr's storage and Downloads/Zapparr), `Transfer` (the web page for a phone or
  computer, with a code), `BackupActivity`.
- `tools/fake_dispatcharr.py`: a stand-in Dispatcharr (live TV) for the emulator (`10.0.2.2`,
  `zapparr`/`zapparr`). `tools/fake_vod_organizer.py`: a stand-in for the user's movie and series
  server (`10.0.2.2:8000`, any login). Neither holds real logins or keys: keep it that way.
- `keystore/`: the public development signing key used by every build.

## Commands

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
python -m unittest discover -s tools
```

Gradle needs a JDK 17+ and the Android SDK (compileSdk 37). GitHub Actions builds the APKs on every
push.

## Conventions

- Kotlin with plain Android Views and `androidx.leanback:leanback-grid`. No Compose: the launcher
  stays resident on a 2 GB box. Keep dependencies few and the APK small.
- Keep logic that can be tested without Android (like `Favorites`, `Decks`) free of Android types,
  and unit-test it.
- Debloat safety rules live in `tools/debloat.py` (`PROTECTED`, `set-home`): check Zapparr starts
  before disabling the stock launcher, and undo at once if Home doesn't land on Zapparr afterwards
  (Google TV keeps Home while its launcher is enabled); disable the setup wizard only together with
  the launcher and only when no saved Wi-Fi network belongs to it; record every change before making it.
- Package names in lists are validated before they reach `adb shell`.

## Working with the owner

- The owner commits and pushes with GitHub Desktop. Never run `git commit` or `git push`. When a
  piece of work is done, give a commit title and a description to paste, and update
  docs/STATUS.md.
- Show features working, not just tested: drive the app in a visible emulator against the
  stand-in servers, take screenshots, and look at them (they have caught bugs the checks missed).
  Then report the results without waiting to be asked.
- Don't run Gradle inside this folder: on the owner's PC it crashed the IDE. Build in a scratch
  copy (the IDE's Gradle extension is off for this workspace); GitHub Actions builds the APKs.
- Give research or review subagents a cheaper model (Sonnet; Haiku for simple look-ups).
- Text the owner pastes from Gemini or similar is unverified; check it against the source.
