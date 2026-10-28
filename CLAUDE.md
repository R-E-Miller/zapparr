# Zapparr

A cable-box style Android TV launcher (Apps, Guide, Library decks) fed by Dispatcharr, plus a
reversible debloat tool for the onn 4K box. Read [docs/SPEC.md](docs/SPEC.md) first: it holds the
plan, the verified Dispatcharr and TvProvider facts, and the open questions.

## Layout

- `app/src/main/java/tv/zapparr/home/`: `HomeActivity` (the HOME activity), `DeckPager` (decks and
  Left/Right at row edges), `Decks`.
- `app/src/main/java/tv/zapparr/apps/`: app discovery (`AppRepository`), the Favorites rules and
  store, the Apps deck rows and tiles, launch helpers.
- `tools/debloat.py`: probe, plan, apply, set-home, restore over adb. `tools/lists/`: per-box lists.
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
- Debloat safety rules live in `tools/debloat.py` (`PROTECTED`, `set-home`): never disable the stock
  launcher before Zapparr comes up as Home; disable the setup wizard only together with it and only
  when no saved Wi-Fi network belongs to it; record every change before making it.
- Package names in lists are validated before they reach `adb shell`.
