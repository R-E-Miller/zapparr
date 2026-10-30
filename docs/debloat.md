# Debloating an Android TV or Google TV box

`tools/debloat.py` switches off a box's telemetry, ad services and preloaded apps, and makes Zapparr
the Home screen. Nothing is uninstalled: it uses `pm disable-user`, the same switch as
Settings > Apps > Disable, and writes down every change before making it, so one command puts the
box back.

It works on Google TV (Chromecast with Google TV, Google TV Streamer, onn, Sony, TCL, Hisense or
Philips sets with Google TV) and Android TV (NVIDIA SHIELD, Xiaomi Mi Box and Mi TV Stick, older
sets). `probe` says which the box is and picks the lists that fit it from [tools/lists](../tools/lists):

- one list for the platform: `google-tv.txt`, `android-tv.txt`, or `other.txt` for a box that is
  neither (a conservative list);
- plus any vendor or model list whose `match:` line fits the box (manufacturer, brand, model or
  device name): `onn-4k-2023.txt`, `google-chromecast.txt`, `nvidia-shield.txt`, `xiaomi.txt`,
  `sony.txt`, `tcl.txt`, `hisense.txt`, `philips.txt`.

The lists come from tested per-device lists (CleanYourTV's, and rcland12's for the Chromecast); each
file cites its sources next to the packages. Where a source wasn't sure, the package was left out.

## 1. Turn on debugging

1. Settings > System > About (on some boxes Settings > Device Preferences > About): press OK on
   **Android TV OS build** (or **Build**) seven times.
2. Back, then Developer options: turn on **USB debugging**.
3. Settings > Network & Internet: note the box's IP address.

On most boxes network ADB (port 5555) opens as soon as USB debugging is on. On Android 14, it may
open only after pairing once under Developer options > Wireless debugging; `probe` says so.

## 2. Install adb and Python

- Windows: `winget install Google.PlatformTools` and Python 3 from python.org or the Store.
- macOS: `brew install android-platform-tools python`.
- Debian or Ubuntu: `sudo apt install adb python3`.

The first command shows "Allow USB debugging?" on the TV. Check "Always allow from this computer".

## 3. Look before changing anything

```sh
python tools/debloat.py --host 192.168.1.50 probe
python tools/debloat.py --host 192.168.1.50 plan
```

`probe` prints the model, Android version, the platform, the lists that fit, what answers Home, the
stock launcher, and who created the saved Wi-Fi networks, with notes for that box (for example,
that casting stops while the setup wizard is off). `plan` lists what `apply` would disable and why
it skips the rest. Neither changes anything. To use only some lists, name them:
`plan tools/lists/onn-4k-2023.txt`.

## 4. Apply the lists

```sh
python tools/debloat.py --host 192.168.1.50 apply
```

It asks before changing anything (`-y` skips the question). Edit a list first if you want to keep
something: comment a line out with `#`.

## 5. Make Zapparr Home

Install Zapparr first (`adb install -r app-release.apk`). Then, **before** `set-home`:

> **Re-join your Wi-Fi.** On Google TV, networks saved during the box's first setup belong to the
> setup wizard, and disabling it deletes them. In Settings > Network & Internet, forget and re-join
> each saved network, including any that are out of range. `probe` tells you whether any are left;
> `set-home` refuses until none are.

```sh
python tools/debloat.py --host 192.168.1.50 set-home
```

It starts Zapparr to check that it runs (if it doesn't, nothing changes), makes it the Home activity,
disables the stock launcher (Google TV's `launcherx`, Android TV's `tvlauncher` or older
`leanbacklauncher`, or the vendor's) and, where it would take Home or bring the launcher back, the
setup wizard, then presses Home. Google TV's launcher keeps answering Home for as long as it is
enabled, even after the Home setting changes, so this is the first moment the switch can be checked:
if Home doesn't land on Zapparr, everything is re-enabled and Home handed back on the spot.

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

`debloat.py` refuses these whatever a list says (`PROTECTED` and `PROTECTED_PREFIXES` in the tool):
Android's own system apps and content providers, Play Services and the Play Store, TV Settings,
keyboards, TvProvider, WebView and DRM key provisioning, the file picker, network services, the
remote and Bluetooth services, the vendors' HDMI, picture, tuner and remote services (Amlogic,
MediaTek, MStar, Realtek, Rockchip, Sony and others), the onn remote's app buttons and mic
(`com.gretzky.GlobalKey`), anything that created a saved Wi-Fi network, and Zapparr itself.

## Known trade-offs

`probe` prints the ones that apply to the box. Common ones:

- **Casting** from a phone stops while the setup wizard is disabled ("Untrusted device"): the Cast
  receiver reads its credentials from it. `restore` brings it back.
- **The remote's YouTube button** does nothing once the stock launcher is disabled.
- **Voice search for titles** ("play The Office") needed the stock launcher on one onn unit; app
  commands ("open YouTube") kept working.
- **Firmware updates** can re-enable packages and give Home back to the stock launcher. Run `plan`
  and `apply` again, then `set-home`.
