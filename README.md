# Zapparr

The set-top "launcharr" for Android TV: a lean home screen that works like a cable box. Your apps, a
live TV guide from [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr), and your movies and
series sit side by side, with no ads and nothing deciding what you want to watch.

> **Status: early development (Phase 1).** The home screen, the app rows and the debloat tool work.
> The Guide and Library decks are placeholders until Phase 2.

## What it is

Three decks, side by side. Left and Right on the remote move between them at the edge of a row.

| Apps | Guide | Library |
|---|---|---|
| Your favourite apps in a row, then every app on the box | Dispatcharr channels in a cable-style time grid *(Phase 2)* | Movies and series from Dispatcharr's VOD or your own catalog *(Phase 2)* |

- **Built for budget boxes.** Plain Android views, no background services, no network permission
  yet. Made on and for the 2 GB onn 4K box.
- **Favorites you arrange.** Hold OK on any app to add it, move it or open its App info.
- **Home always comes back.** Pressing Home returns to your first favourite; Back from the Guide or
  Library returns to Apps; the gear opens the TV's Settings.
- **A reversible debloat tool** for the onn 4K: switches off telemetry, ad services and preloads
  with `pm disable-user`, records every change first, and undoes it all with one command.

## Try it

You need a computer with `adb` ([platform-tools](https://developer.android.com/tools/releases/platform-tools);
on Windows, `winget install Google.PlatformTools`) and Python 3 for the debloat tool.

1. **Turn on debugging on the box.** Settings > System > About, press OK on *Android TV OS build*
   seven times, then Settings > System > Developer options > USB debugging on. Note the box's IP
   address under Settings > Network & Internet.
2. **Get the APK.** Each push to this repo builds one: Actions > the latest *Build* run > Artifacts >
   `zapparr-apk`. Use `app-release.apk`. Or [build it yourself](#build-from-source).
3. **Install it.**
   ```sh
   adb connect 192.168.1.50:5555      # accept "Allow USB debugging?" on the TV
   adb install -r app-release.apk
   ```
4. **Debloat and make it Home.** Follow [docs/debloat.md](docs/debloat.md). In short:
   ```sh
   python tools/debloat.py --host 192.168.1.50 probe
   python tools/debloat.py --host 192.168.1.50 plan tools/lists/onn-4k-2023.txt
   python tools/debloat.py --host 192.168.1.50 apply tools/lists/onn-4k-2023.txt
   python tools/debloat.py --host 192.168.1.50 set-home
   ```
   `python tools/debloat.py --host 192.168.1.50 restore` puts everything back.

Don't uninstall Zapparr while it is Home and the stock launcher is disabled: run `restore` first.

## Build from source

JDK 17 or newer and the Android SDK (Android Studio brings both).

```sh
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest lintDebug
python -m unittest discover -s tools
```

Every build, local or CI, is signed with the same public development key in [keystore/](keystore/README.md),
so a newer APK always installs over an older one.

## Roadmap

1. **Foundation** *(done)*: TV launcher with Home and Leanback intents, the three-deck skeleton,
   app discovery and the Favorites row, the onn debloat tool.
2. **Dispatcharr and VOD**: the Xtream Codes API from Dispatcharr (channels, EPG, movies, series)
   with M3U and XMLTV as a fallback; the time-grid guide; the VOD poster grid; a Continue Watching
   row from the TV's Watch Next data; handing streams to an external player.
3. **Built-in player**: Media3 (ExoPlayer), a mini-guide over live video, channel-number entry,
   last-channel recall, and live TV that keeps playing behind the guide.

Details, verified API notes and open questions: [docs/SPEC.md](docs/SPEC.md).

## Credits and licence

Zapparr is licensed under the [GNU GPL v3](LICENSE).

The onn debloat list is adapted from the tested list in
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT). The settings icon is from Material
Icons (Apache 2.0).

Zapparr is an independent project, not affiliated with Dispatcharr, TiviMate, Google, Walmart or
onn. Product names are used only to say what it works with.
