# Zapparr

The set-top "launcharr" for Android TV: a lean home screen that works like a cable box. Your apps, a
live TV guide from [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr), and your movies and
series sit side by side, with no ads and nothing deciding what you want to watch.

> **Status: early development (Phase 2's four steps built; Phase 3 next).** The home screen, the
> debloat tool, the live TV guide and the Library work, and the Apps deck has Continue Watching
> (what you left part-way, in the Library and in other apps) and On now (your recent channels with
> what's airing). Search finds channels, programs, movies and series in one place. The gear opens
> Zapparr's own settings: 36 color themes, a 12 or 24-hour clock, and how each screen looks.
> Streams go to your video player until Phase 3's built-in player.

## What it is

Three decks, side by side. Left and Right on the remote move between them at the edge of a row,
or press Up to the tabs at the top, as on Google TV, and pick one.

| Apps | Guide | Library |
|---|---|---|
| Your favorite apps in a row, then every app on the box | Dispatcharr channels in a cable-style time grid | Movies and series from your library server, in rows of posters by genre |

- **Built for budget boxes.** Plain Android views, no background services, and no network use
  beyond your own Dispatcharr server. Made on and for the 2 GB onn 4K box.
- **Favorites you arrange.** Hold OK on any app to add it, move it or open its App info.
- **A guide like a cable box.** Channels down the side with their logos, two hours across, a line
  at the current time. Filter by Favorites or by your Dispatcharr groups; hold OK on a channel to
  move, rename, hide or favorite it, as in TiviMate; OK watches it in the video player you pick (VLC,
  Just Player and the like) until Zapparr's own player arrives.
- **Home always comes back.** Pressing Home returns to your first favorite; Back from the Guide,
  the Library or the tabs returns to Apps; the gear opens Zapparr's settings (themes, clock, rows,
  the guide, backup and restore), with the TV's own Settings inside.
- **A reversible debloat tool** for Android TV and Google TV boxes (onn, Chromecast, SHIELD, Mi Box
  and others): picks the lists that fit the box, switches off telemetry, ad services and preloads
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
   python tools/debloat.py --host 192.168.1.50 plan
   python tools/debloat.py --host 192.168.1.50 apply
   python tools/debloat.py --host 192.168.1.50 set-home
   ```
   `python tools/debloat.py --host 192.168.1.50 restore` puts everything back.
5. **Connect Dispatcharr.** Press Right to the Guide, choose *Connect to Dispatcharr*, and enter the
   server's address (like `192.168.1.20:9191`), a Dispatcharr user name and that user's XC password.
6. **Connect your library.** On the Library, choose *Connect library server* and enter the Xtream
   Codes server with your movies and series and its login. For vod-organizer that's port 8000 and
   your IPTV provider's login, the same one TiviMate uses.

Don't uninstall Zapparr while it is Home and the stock launcher is disabled: run `restore` first.

## Build from source

JDK 17 or newer and the Android SDK (Android Studio brings both).

```sh
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest lintDebug
python -m unittest discover -s tools
```

No Dispatcharr to test against? `python tools/fake_dispatcharr.py` serves 60 made-up channels with
logos and a guide around the current time, and `python tools/fake_vod_organizer.py` a made-up
library of movies and series on port 8000 (any login). In the Android emulator, connect to `10.0.2.2` with user and password
`zapparr`.

Every build, local or CI, is signed with the same public development key in [keystore/](keystore/README.md),
so a newer APK always installs over an older one.

## Roadmap

1. **Foundation** *(done)*: TV launcher with Home and Leanback intents, the three-deck skeleton,
   app discovery and the Favorites row, the onn debloat tool.
2. **Dispatcharr and VOD** *(built)*: live TV from Dispatcharr's Xtream Codes API, movies and
   series from your library server's (vod-organizer); the time-grid guide; the Library's poster
   rows, details and resume; Continue Watching (with the TV's Watch Next data) and On now on the
   Apps deck; handing streams to an external player. Not built: M3U and XMLTV as a fallback for
   servers without the Xtream Codes API.
3. **Built-in player**: Media3 (ExoPlayer), a mini-guide over live video, channel-number entry,
   last-channel recall, and live TV that keeps playing behind the guide.

Details, verified API notes and open questions: [docs/SPEC.md](docs/SPEC.md).

## Credits and license

Zapparr is licensed under the [GNU GPL v3](LICENSE).

The onn debloat list is adapted from the tested list in
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT). The settings icon is from Material
Icons (Apache 2.0).

Zapparr is an independent project, not affiliated with Dispatcharr, TiviMate, Google, Walmart or
onn. Product names are used only to say what it works with.
