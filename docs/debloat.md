# Debloating the onn 4K box

`tools/debloat.py` switches off the box's telemetry, ad services and preloaded apps, and makes
Zapparr the Home screen. Nothing is uninstalled: it uses `pm disable-user`, the same switch as
Settings > Apps > Disable, and writes down every change before making it, so one command puts the
box back.

The list for this box, [tools/lists/onn-4k-2023.txt](../tools/lists/onn-4k-2023.txt), is adapted
from the [tested CleanYourTV list](https://github.com/Gathin23/cleanyourtv/tree/main/examples/onn-4k-streaming-box-2023)
for the onn 4K Streaming Box (2023, Google TV 14).

## 1. Turn on debugging

1. Settings > System > About: press OK on **Android TV OS build** seven times.
2. Back, then Settings > System > Developer options: turn on **USB debugging**.
3. Settings > Network & Internet: note the box's IP address.

On this box, network ADB (port 5555) opens as soon as USB debugging is on; no pairing code.

## 2. Install adb and Python

- Windows: `winget install Google.PlatformTools` and Python 3 from python.org or the Store.
- macOS: `brew install android-platform-tools python`.
- Debian or Ubuntu: `sudo apt install adb python3`.

The first command shows "Allow USB debugging?" on the TV. Tick "Always allow from this computer".

## 3. Look before changing anything

```sh
python tools/debloat.py --host 192.168.1.50 probe
python tools/debloat.py --host 192.168.1.50 plan tools/lists/onn-4k-2023.txt
```

`probe` prints the model, Android version, what answers Home, and whether the setup wizard owns any
saved Wi-Fi network. `plan` lists what `apply` would disable and why it skips the rest. Neither
changes anything.

## 4. Apply the list

```sh
python tools/debloat.py --host 192.168.1.50 apply tools/lists/onn-4k-2023.txt
```

It asks before changing anything (`-y` skips the question). Edit the list first if you want to keep
something: comment a line out with `#`. Voice search, Chromecast and Google's ambient screensaver
are kept unless you uncomment them.

## 5. Make Zapparr Home

Install Zapparr first (`adb install -r app-release.apk`). Then, **before** `set-home`:

> **Re-join your Wi-Fi.** Networks saved during the box's first setup belong to the setup wizard
> (`setupwraith`), and disabling it deletes them. In Settings > Network & Internet, forget and
> re-join each saved network, including any that are out of range. `probe` tells you whether any
> are left; `set-home` refuses until none are.

```sh
python tools/debloat.py --host 192.168.1.50 set-home
```

It starts Zapparr to check that it runs (if it doesn't, nothing changes), makes it the Home activity,
disables the setup wizard and the stock launcher together, then presses Home. Google TV's launcher
keeps answering Home for as long as it is enabled, even after the Home setting changes, so this is
the first moment the switch can be checked: if Home doesn't land on Zapparr, everything is
re-enabled and Home handed back on the spot. The wizard goes with the launcher because on Google TV
it re-enables the launcher, and its recovery screen takes over Home once the launcher is gone.

## Undo

```sh
python tools/debloat.py --host 192.168.1.50 restore
```

Re-enables everything this tool disabled (the stock launcher first) and hands Home back to the
launcher that had it. The record lives in `~/.zapparr/debloat/`, one file per box, so run `restore`
from the computer that made the changes. Zapparr stays installed; remove it in Settings > Apps.

If the computer is gone, the box's own Settings > Apps > (app) > Enable does the same per app.

**Don't uninstall Zapparr while it is Home and the stock launcher is disabled.** Run `restore`
first, or the box has no Home screen to fall back to.

## Never touched

`debloat.py` refuses these whatever a list says: Play Services, Play Store, TV Settings,
keyboards, TvProvider, `com.gretzky.GlobalKey` (the onn remote's app buttons and mic),
`com.droidlogic.*` and `com.amlogic.*` (HDMI, picture, remote pairing), the remote and Bluetooth
services, and Zapparr itself.

## Known trade-offs on this box

- **Voice search for titles** ("play The Office") needed the stock launcher on one unit; app
  commands ("open YouTube") kept working.
- **Casting** with the setup wizard disabled hasn't been tested on the onn. On the Chromecast with
  Google TV, the Cast receiver lost its credentials without it. Check before you rely on casting.
- **Firmware updates** can re-enable packages and give Home back to the stock launcher. Run `plan`
  and `apply` again, then `set-home`.
