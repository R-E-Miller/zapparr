# Where Zapparr stands

Updated 2026-10-30. The plan and the verified facts are in [SPEC.md](SPEC.md); this is the short
version of what's done, what's checked, and what's next.

## Built

Phases 1 and 2, and the additions asked for after them (SPEC, Phases):

- Home with Apps, Guide and Library decks, Google TV-style tabs, a search button and the gear.
- The Guide grid (Dispatcharr), with channel management as in TiviMate: favorites, your own order,
  rename, hide channels and groups, move groups.
- The Library (vod-organizer): poster rows, details, seasons and episodes, resume.
- Continue Watching and On now on the Apps deck; Search across live TV and the Library.
- Settings: 36 color themes, the clock, and how each screen looks (Look, Home, Guide, Library,
  System).
- Backup and restore: on the box, in Downloads/Zapparr, and to or from a phone or computer.
- `tools/debloat.py` for any Android TV or Google TV box.

## Checked on the emulator

Every feature above, in the Google TV 14 emulator against stand-in servers
(`tools/fake_dispatcharr.py`, `tools/fake_vod_organizer.py`), driven like a remote, with
screenshots: the last runs passed 18/18 (look and settings) and 29/29 (debloat probe, set-home and
restore; channel management; backup and restore). Unit tests: 71 in the app, 41 for the tools.

## To check at home, on the real box and servers

1. Install on the onn 4K: `debloat.py probe`, then `set-home` (docs/debloat.md).
2. Memory use on the 2 GB box after a while: `adb shell dumpsys meminfo tv.zapparr`.
3. The real Dispatcharr and vod-organizer replies (the stand-ins follow their source code).
4. Resume with the real video player: play a movie a few minutes, Back, look for *Resume from*.
5. Continue Watching: allow *Other apps' shows*, watch something in Prime Video or Paramount+,
   see whether it appears.
6. Whether TiviMate accepts a stream handed to it (SPEC, Playback handoff).

## Next

- **Organizarr** (the vod-organizer, renamed): its own GitHub project, with a web page for the
  provider's address and login and the TMDb key (kept in its settings, never in the repo), a Docker
  image, and the same Xtream Codes API, so Zapparr and TiviMate keep working with it. Then see what
  Dispatcharr's API offers for sharing the provider login, and let Zapparr find both servers.
- **The rest of Phase 2**: the "Next" items in SPEC's TiviMate parity table (reminders, My List,
  font size, a parental PIN, custom logos and numbers, the guide reload interval as a setting),
  watched marks on posters, Up next, and Down keeping the column on the Apps deck.
- **Phase 3**: the built-in player, with the preview (corner box or behind a see-through Guide, the
  user's choice), the mini guide, channel numbers, last channel, one stream at a time.
