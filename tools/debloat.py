#!/usr/bin/env python3
"""Debloat an Android TV or Google TV box over ADB, reversibly, and make Zapparr its Home.

Nothing is uninstalled. Packages are switched off with `pm disable-user`, the same switch as
Settings > Apps > Disable, and each change is written down before it is made, so `restore`
puts the box back the way it was.

  python tools/debloat.py --host 192.168.1.50 probe
  python tools/debloat.py --host 192.168.1.50 plan
  python tools/debloat.py --host 192.168.1.50 apply
  python tools/debloat.py --host 192.168.1.50 set-home
  python tools/debloat.py --host 192.168.1.50 restore

`probe` says whether the box is Google TV, Android TV or unknown, and which lists in tools/lists/
fit it: the generic list for its platform, plus any vendor or model list whose `match:` line fits.
`plan` and `apply` use those lists, or only the ones you name (plan tools/lists/onn-4k-2023.txt).

Needs adb from Android platform-tools on PATH (or --adb). See docs/debloat.md.
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import shutil
import subprocess
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

ZAPPARR = "tv.zapparr"
ZAPPARR_HOME = "tv.zapparr/.home.HomeActivity"
SETUP_WIZARD = "com.google.android.tungsten.setupwraith"  # Google's, on Google TV and Android TV
GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
STOCK_LAUNCHERS = (
    GOOGLE_TV_LAUNCHER,  # Google TV
    "com.google.android.tvlauncher",  # Android TV
    "com.google.android.leanbacklauncher",  # older Android TV
)
PLATFORMS = {"google-tv": "Google TV", "android-tv": "Android TV", "other": "unknown platform"}
# First-run wizards that can still answer Home after setup, and take it once the launcher is gone:
# Google's and AOSP's. Lists can't disable them; set-home does, together with the launcher, only when
# one answers Home (or is Google's on Google TV, where it also turns the launcher back on), and only
# while no saved Wi-Fi network belongs to it.
SETUP_WIZARDS = (SETUP_WIZARD, "com.android.provision")
# What answers Home without being a home screen to hand Home back to, along with Settings'
# FallbackHome and factory test screens (CleanYourTV, tools/recovery.py NOT_LAUNCHERS).
NOT_LAUNCHERS = frozenset({*SETUP_WIZARDS, "android", "com.android.settings", "com.android.tv.settings"})
FACTORY_TEST = re.compile(r"factorytest|factory_test|factorymode", re.IGNORECASE)
# Launchers no other app can replace, so set-home stops before changing anything.
HOME_LOCKED = {
    "com.amazon.tv.launcher": "Fire OS keeps Home for Amazon's launcher: it ignores the Home setting and "
                              "refuses to disable the launcher (CleanYourTV, docs/TROUBLESHOOTING.md).",
}

# Never disabled, whatever a list says: the box needs these to boot, install and play apps, take input
# from the remote, reach Settings, or play its inputs. Sources: CleanYourTV
# (https://github.com/Gathin23/cleanyourtv: the core-package refusal in tools/disable.sh,
# docs/TROUBLESHOOTING.md, and the keep notes in examples/<model>/debloat.txt), rcland12/debloat-tv
# (packages-chromecast.txt, packages-tcl.txt) and bharvey88's SHIELD notes
# (https://gist.github.com/bharvey88/c211c6ca1d9e5f1379b204bf75067275).
PROTECTED = frozenset({
    ZAPPARR,
    # Android itself. A Mi TV Stick never booted again after a list took the permission controller,
    # package installer, ext services and module metadata (CleanYourTV #884).
    "android",
    "android.ext.services",
    "android.ext.shared",
    "com.android.ext.services",
    "com.android.ext.shared",
    "com.android.modulemetadata",
    "com.android.packageinstaller",
    "com.android.permissioncontroller",
    "com.android.phone",
    "com.android.providers.settings",
    "com.android.providers.tv",  # TvProvider: channels and Watch Next rows
    "com.android.settings",
    "com.android.shell",
    "com.android.systemui",
    "com.android.tv.settings",
    "com.google.android.ext.services",
    "com.google.android.ext.shared",
    "com.google.android.modulemetadata",
    "com.google.android.packageinstaller",
    "com.google.android.permissioncontroller",
    "com.google.android.providers.media.module",  # MediaProvider, as com.android.providers.* below
    "com.android.location.fused",  # boot loops on some devices (bharvey88)
    "com.android.keychain",  # SHIELDs soft-bricked without these two (CleanYourTV nvidia-shield-tv-pro-2019)
    "com.android.se",
    "com.android.documentsui",  # the system file picker (bharvey88)
    "com.google.android.documentsui",
    # Play Store and Services, and what apps sign in and play with (rcland12 packages-chromecast.txt)
    "com.android.vending",  # Play Store
    "com.google.android.gms",  # Play Services
    "com.google.android.gsf",
    "com.google.android.configupdater",
    "com.google.android.webview",  # sign-in pages in apps
    "com.android.webview",
    "com.google.android.rkpdapp",  # DRM key provisioning: HD and 4K playback
    "com.android.rkpdapp",
    # Keyboards. plan also asks the box for every keyboard it has.
    "com.google.android.inputmethod.latin",  # Gboard
    "com.google.android.leanback.ime",
    # Remotes, pairing and Bluetooth
    "com.android.bluetooth",
    "com.android.inputdevices",
    "com.google.android.tv.remote.service",  # phone remote; also the remote's mic (CleanYourTV onn-4k-plus-2025)
    "com.google.android.tv.axel",  # remote pairing and TV control setup (CleanYourTV google-chromecast-gtv-hd)
    "com.google.android.tv.dfuservice",  # remote firmware updates
    "com.google.android.tv.overlay.dfuservice",  # the same on the Google TV Streamer
    "com.android.tv.globalkeyhandler",  # the remote's global keys (CleanYourTV rocktek-gb1)
    # On a SHIELD, the SHIELD TV phone app's keys stop working after a reboot without it (CleanYourTV
    # docs/TROUBLESHOOTING.md, bisected on two SHIELDs).
    "com.google.android.tvrecommendations",
    "com.dolby.android.audio.service",  # (CleanYourTV SHIELD and Hisense lists)
    # onn
    "com.gretzky.GlobalKey",  # the remote's app buttons and mic
    "com.onn.bluetoothconnect",  # Bluetooth remote setup
    # NVIDIA SHIELD: remote, controllers, picture and sound (CleanYourTV nvidia-shield-tv-*, bharvey88)
    "com.nvidia.blakepairing",
    "com.nvidia.ControllerMapper",
    "com.nvidia.irtuner",
    "com.nvidia.remotelocator",
    "com.nvidia.shield.remote.server",  # the SHIELD TV phone app
    "com.nvidia.shieldtech.accessoryui",
    "com.nvidia.shieldtech.hooks",
    "com.nvidia.shieldtech.proxy",
    "com.nvidia.avsync",
    "com.nvidia.NvCPLSvc",
    "com.nvidia.nvaudiosvc",
    "com.nvidia.overscancomp",
    "com.nvidia.packagemanagerservice",
    "com.nvidia.shieldservice",
    "com.nvidia.KeyComboOta",
    # Xiaomi (CleanYourTV xiaomi-mi-box-s, xiaomi-mitv-afmu0)
    "com.xiaomi.android.tvsetup.partnercustomizer",  # the remote's shortcut buttons, remote pairing
    "mitv.service",  # Xiaomi's TvServer
    # Sony BRAVIA (CleanYourTV sony-*; more Sony prefixes below)
    "com.sony.dtv.b2b.hotelmode",  # Settings opens blank without its provider (CleanYourTV sony-kd-55x8000c)
    "com.uei.quicksetsdk.sony",  # IR control of other devices
    # TCL (CleanYourTV tcl-*, rcland12 packages-tcl.txt)
    "com.tcl.tv",  # Live TV; on some sets also the remote's Power key
    "com.tcl.tvinput",
    "com.tcl.tvpassthrough",
    "com.tvos",
    "com.tcl.suspension",  # the Input picker
    "com.tcl.dashboard",  # picture and sound settings, the quick panel
    "com.tcl.settings",
    "com.tcl.autopair",  # remote pairing
    "com.tcl.tcl_bt_rcu_service",
    "com.tcl.globalkeyoverlay",  # remote keys
    "com.tcl.system.server",
    "com.tcl.systemserver",
    "com.tcl.keycustomfunctionservice",  # its FallbackHome
    "com.tcl.partnercustomizer",  # the remote's T key on some sets
    "com.tcl.ttvs",  # source overlays; firmware updates on some sets
    "com.tcl.inputmethod.international",  # keyboard
    "com.tcl.providers.config",  # Wi-Fi drops without it
    "com.tcl.initsetup",  # setup; owns Wi-Fi
    "com.tcl.factory.view",
    # Hisense (CleanYourTV hisense-u75-hengshan, hisense-u7-wutaishan, hisense-smarttv-4k-ffm-lushan)
    "com.vt.tv.tvcenter",  # Live TV, plays HDMI
    "com.vt.tv.input",  # Inputs menu
    "com.vt.tvservice",
    "com.vt.avmiddleware",
    "com.vt.tvplayer",
    "com.vt.tv.commonui",
    "com.vt.tv.smartservice",
    "com.vt.tv.smartsense",  # with the next two: a set went to Android Recovery without them
    "com.vt.tv.smartsense.ui",
    "com.hisense.tv.smartsense",
    "com.vt.udpserver",  # discovery for casting and the phone remote
    # Philips by TP Vision (CleanYourTV philips-50pus8517's DO NOT DISABLE block, philips-*)
    "org.droidtv",  # TPVTvService
    "org.droidtv.GlobalKey",  # remote keys
    "org.droidtv.lkb",  # front-panel keys
    "org.droidtv.tvsystemui",
    "org.droidtv.tvpowermanagerservice",
    "org.droidtv.settings",
    "org.droidtv.menudataapp",
    "org.droidtv.homeintentresolver",
    "org.droidtv.philipstvdrawer",  # the OPTIONS key
    "org.droidtv.xtv",
    "org.droidtv.xtvsystem",
    "org.droidtv.playtv",  # plays HDMI as well as the tuner
    "org.droidtv.channels",
    "org.droidtv.hdmiservice",
    "org.droidtv.tv.tptvservice",
    "org.droidtv.icp",
    "org.droidtv.fastip",  # the SOURCES drawer dies without it
    "org.droidtv.eum",  # the same
    "org.droidtv.aurora",
    "org.droidtv.devicesetup",
    "org.droidtv.networkwizard",
    "org.droidtv.tv.playFiAudioService",  # Settings binds it
    "com.easy.oad",  # remote firmware updates
    # Skyworth-built boxes (CleanYourTV strong-leap-s3): the remote's app buttons
    "com.sdt.globalkey",
    "com.sdt.vendor",
    # TV middleware and SoC services (CleanYourTV #1578, homeplus-stb-6252c)
    "com.cltv.mal",
    "com.mtk.systemcontrol",
    "com.realsil.ota",  # remote firmware updates
})
PROTECTED_PREFIXES = (
    "com.android.providers.",  # Android's content providers (CleanYourTV tools/disable.sh)
    "com.google.android.overlay.modules.",  # Mainline overlays (CleanYourTV #884)
    "com.android.networkstack",  # DHCP and network checks (bharvey88)
    "com.google.android.networkstack",
    "com.droidlogic.",  # Amlogic: HDMI, CEC, picture, remote pairing
    "com.amlogic.",
    "com.mediatek.",  # MediaTek TV middleware: inputs, tuner, remote pairing (CleanYourTV TV lists)
    "com.mstar.",  # MStar TV SoC services (CleanYourTV homeplus-stb-6252c)
    "com.realtek.",  # Realtek TV: inputs, tuner, providers (CleanYourTV TCL and Sanyo lists)
    "com.rockchip.",  # Rockchip SoC services: kept like Amlogic's, as no tested list touches them
    "com.nvidia.bluetooth.",
    "com.nes.remoteota",  # remote firmware updates on SEI/NES boxes (CleanYourTV nokia-streaming-box-8000)
    # Sony: inputs, HDMI-CEC (Bravia Sync), remote pairing and keys, settings (CleanYourTV sony-*)
    "com.sony.dtv.tvinput.",
    "com.sony.dtv.braviasync",
    "com.sony.dtv.tvx",
    "com.sony.dtv.tvlin",
    "com.sony.dtv.tvapi",
    "com.sony.dtv.sonysystemservice",
    "com.sony.dtv.sonyvendorservice",
    "com.sony.dtv.settings",
    "com.sony.dtv.imeproxy",
    "com.sony.dtv.smartui",  # Input menu and the Quick Settings ribbon
    "com.sony.dtv.remote",  # remotepairing, remotecontrolsettings
    "com.sony.dtv.rcpowerkey",
    "com.sony.dtv.uinput",
    "com.sony.dtv.irb",  # IR blaster
    # Hisense: HDMI and tuner inputs, smart-home services (CleanYourTV hisense-*)
    "com.vt.source.",
    "com.vt.tv.aiot",
)
# What the box uses whatever its package is called: plan never disables these. CleanYourTV's
# tools/disable.sh asks the same: with the input app off, HDMI shows "No Signal" for good (#321), and
# with the Settings app off there is no way back to Developer options.
GUARDS = (
    ("cmd package query-activities --brief -a android.settings.SETTINGS",
     "opens Settings, which Zapparr's gear needs"),
    ("cmd package query-services --brief -a android.media.tv.TvInputService",
     "plays the TV's inputs (HDMI, tuner)"),
    ("cmd package query-activities --brief -a android.intent.action.VIEW -t vnd.android.cursor.item/channel",
     "opens the TV's channels and inputs"),
    ("ime list -a -s", "is a keyboard"),
)
HOME_INTENT = "-a android.intent.action.MAIN -c android.intent.category.HOME"
STATE_DIR = Path.home() / ".zapparr" / "debloat"
LISTS_DIR = Path(__file__).resolve().parent / "lists"
# What a list's `match:` line can test, and the property each one reads.
MATCH_KEYS = {
    "manufacturer": "ro.product.manufacturer",
    "brand": "ro.product.brand",
    "model": "ro.product.model",
    "device": "ro.product.device",
    "name": "ro.product.name",
    "hardware": "ro.hardware",
    "board": "ro.board.platform",
    "soc": "ro.soc.manufacturer",
}
PLATFORM_HOW = {
    "google-tv": f"Google TV: {GOOGLE_TV_LAUNCHER} is installed",
    "android-tv": "Android TV: Google's Android TV launcher is installed",
    "other": "unknown: neither Google TV's nor Android TV's launcher is installed",
}
UNKNOWN_NOTE = ("Unknown:  plan and apply use only the short list for unknown boxes, plus any vendor list "
                "that fits.\n          Read `plan` before `apply`.")

PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
COMPONENT = re.compile(r"^[A-Za-z][\w.]*/[\w.$]+$")
DIRECTIVE = re.compile(r"^#\s*(platform|match|note):\s*(.*?)\s*$")


class Refused(Exception):
    """A step the tool won't take; the message says why and what to do instead."""


def is_protected(pkg: str) -> bool:
    return pkg in PROTECTED or pkg.startswith(PROTECTED_PREFIXES)


@dataclass
class Device:
    """What the box is, read before anything changes."""

    props: dict[str, str]
    platform: str  # a key of PLATFORMS
    launchers: list[str]  # the stock launchers set-home hands Home over from
    launcher_known: bool  # False: found through the Home intent, not by name
    home: str = ""  # the activity that answers Home now
    claimants: list[tuple[str, int]] = field(default_factory=list)  # (activity, priority) of every Home

    def prop(self, name: str) -> str:
        return self.props.get(name, "").strip()

    def name(self) -> str:
        """Maker, model and device code, as probe shows them."""
        text = " ".join(part for part in (self.prop("ro.product.manufacturer"), self.prop("ro.product.model"))
                        if part) or "unknown box"
        code = self.prop("ro.product.device")
        return f"{text} ({code})" if code else text


@dataclass
class ListFile:
    """A list of packages to disable, and which boxes it is for.

    Its comment lines can say `# platform: google-tv` (a generic list for that platform),
    `# match: brand=onn*` (a vendor or model list: every key=pattern on one line must fit, and any one
    line will do) and `# note: ...` (shown by probe and set-home on the boxes it fits).
    """

    path: Path
    packages: list[str] = field(default_factory=list)
    platforms: list[str] = field(default_factory=list)
    matches: list[dict[str, str]] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def fit(self, device: Device) -> str:
        """Why this list fits [device] ("Google TV", "manufacturer=NVIDIA"), or "" when it doesn't."""
        if self.platforms and device.platform not in self.platforms:
            return ""
        if not self.matches:
            return PLATFORMS[device.platform] if self.platforms else ""
        for rule in self.matches:
            if all(fnmatch.fnmatchcase(device.prop(MATCH_KEYS[key]).lower(), pattern.lower())
                   for key, pattern in rule.items()):
                return " ".join(f"{key}={pattern}" for key, pattern in rule.items())
        return ""

    def meant_for(self) -> str:
        rules = " or ".join(" ".join(f"{key}={pattern}" for key, pattern in rule.items()) for rule in self.matches)
        platforms = " or ".join(PLATFORMS[name] for name in self.platforms)
        return " boxes with ".join(part for part in (platforms, rules) if part)


def parse_list(path: Path) -> ListFile:
    """A list file: one package name per line, '#' starts a comment, plus the directives above."""
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError as e:
        raise Refused(f"can't read {path}: {e.strerror}") from e
    found = ListFile(path)
    for number, line in enumerate(lines, 1):
        directive = DIRECTIVE.match(line.strip())
        if directive:
            key, value = directive.groups()
            where = f"{path}:{number}"
            if key == "platform":
                for name in value.split():
                    if name not in PLATFORMS:
                        raise Refused(f"{where}: unknown platform {name!r}; use {', '.join(PLATFORMS)}")
                    found.platforms.append(name)
            elif key == "match":
                rule = {}
                for pair in value.split():
                    prop, _, pattern = pair.partition("=")
                    if prop not in MATCH_KEYS or not pattern:
                        raise Refused(f"{where}: {pair!r} isn't key=pattern with a key from {', '.join(MATCH_KEYS)}")
                    rule[prop] = pattern
                if not rule:
                    raise Refused(f"{where}: a match line needs at least one key=pattern")
                found.matches.append(rule)
            elif value:
                found.notes.append(value)
            continue
        name = line.split("#", 1)[0].strip()
        if not name:
            continue
        # Names go into adb shell commands, so nothing but a package name gets through.
        if not PACKAGE.match(name):
            raise Refused(f"{path}:{number}: {name!r} is not a package name")
        if name not in found.packages:
            found.packages.append(name)
    return found


def read_list(path: Path) -> list[str]:
    """Package names from a list file: one per line, and '#' starts a comment."""
    return parse_list(path).packages


def read_lists(folder: Path) -> list[ListFile]:
    return [parse_list(path) for path in sorted(folder.glob("*.txt"))]


def fitting_lists(device: Device, folder: Path) -> list[tuple[ListFile, str]]:
    """The lists in [folder] that fit [device], with why: generic ones first, then vendor ones."""
    fitting = [(found, why) for found in read_lists(folder) if (why := found.fit(device))]
    return sorted(fitting, key=lambda pair: (bool(pair[0].matches), pair[0].path.name))


def packages_in(lists) -> list[str]:
    packages: list[str] = []
    for found in lists:
        packages += [pkg for pkg in found.packages if pkg not in packages]
    return packages


def platform_of(installed: set[str]) -> str:
    if GOOGLE_TV_LAUNCHER in installed:
        return "google-tv"
    if any(pkg in installed for pkg in STOCK_LAUNCHERS[1:]):
        return "android-tv"
    return "other"


def real_home(component: str) -> bool:
    """Whether a Home activity is a home screen to give Home back to: not Android's chooser, a setup
    wizard's recovery screen, Settings' FallbackHome or a factory test screen."""
    pkg, _, activity = component.partition("/")
    return (bool(COMPONENT.match(component)) and pkg not in NOT_LAUNCHERS
            and not activity.endswith("FallbackHome") and not FACTORY_TEST.search(component))


def launcher_candidate(component: str) -> bool:
    """Whether a Home activity belongs to a launcher set-home could hand Home over from."""
    pkg = component.split("/")[0]
    return real_home(component) and pkg != ZAPPARR and not is_protected(pkg)


def stock_launchers(installed: set[str], disabled: set[str], home: str,
                    claimants: list[tuple[str, int]]) -> tuple[list[str], bool]:
    """The stock launchers, and whether they were known by name.

    Google's count when they answer Home, are already disabled, or the box won't list what answers
    Home (a Sony set's leanbacklauncher has no Home activity at all: CleanYourTV). Failing those, the
    launcher answering Home now, or the only launcher that can.
    """
    answering = {component.split("/")[0] for component, _ in claimants}
    known = [pkg for pkg in STOCK_LAUNCHERS
             if pkg in installed and (pkg in answering or pkg in disabled or not claimants)]
    if known:
        return known, True
    if launcher_candidate(home):
        return [home.split("/")[0]], False
    others = sorted({component.split("/")[0] for component, _ in claimants if launcher_candidate(component)})
    return (others if len(others) == 1 else []), False


def detect(box: Box, installed: set[str], disabled: set[str]) -> Device:
    home, claimants = box.home(), box.home_claimants()
    launchers, known = stock_launchers(installed, disabled, home, claimants)
    return Device(box.props(), platform_of(installed), launchers, known, home, claimants)


def wizards_to_disable(device: Device, installed: set[str], disabled: set[str]) -> list[str]:
    """The setup wizards set-home disables along with the launcher: any that answers Home, which would
    take Home once the launcher is gone, and on Google TV Google's, which also turns the launcher back
    on. Elsewhere they stay: on most Android TV builds Google's doesn't answer Home, and it can still
    own the saved Wi-Fi (CleanYourTV, examples/*)."""
    answering = {component.split("/")[0] for component, _ in device.claimants}
    return [pkg for pkg in SETUP_WIZARDS if pkg in installed and pkg not in disabled
            and (pkg in answering or (pkg == SETUP_WIZARD and device.platform == "google-tv"))]


def boot_problem(device: Device, leaving: list[str]) -> str:
    """Why the box might not finish booting once [leaving] are disabled, or "" if it will.

    With per-file encryption Android starts Home before the first unlock, when only a direct-boot-aware
    activity can run. Settings' FallbackHome stands in until then; Zapparr can't. Without one, a box
    hung on a black screen at every boot (CleanYourTV, examples/vision-2024vision43stv).
    """
    if device.prop("ro.crypto.type") != "file":
        return ""
    if not device.claimants:
        return ("the box encrypts storage per file and won't list its Home apps, so there's no telling "
                "whether a FallbackHome would stand in for Zapparr at boot")
    others = [component for component, _ in device.claimants if component.split("/")[0] not in leaving]
    if any(component.endswith("FallbackHome") for component in others):
        return ""
    return ("the box encrypts storage per file and has no FallbackHome, so before the first unlock at boot "
            "nothing could start in their place and the box could hang on a black screen")


def plan(wanted: list[str], installed: set[str], disabled: set[str], guarded: dict[str, str] | None = None,
         wifi: Counter | None = None, homes=()) -> tuple[list[str], list[tuple[str, str]]]:
    """Splits a list into packages to disable now and (package, reason) pairs left alone.

    [guarded] maps the packages the box needs whatever their name to why; [wifi] counts the saved Wi-Fi
    networks each app created; [homes] are launchers set-home hands Home over from.
    """
    guarded = guarded or {}
    todo: list[str] = []
    skipped: list[tuple[str, str]] = []
    for pkg in wanted:
        if is_protected(pkg):
            skipped.append((pkg, "protected: the box needs it"))
        elif pkg in STOCK_LAUNCHERS or pkg in SETUP_WIZARDS or pkg in homes:
            skipped.append((pkg, "left to set-home, once Zapparr is Home"))
        elif pkg not in installed:
            skipped.append((pkg, "not installed"))
        elif pkg in disabled:
            skipped.append((pkg, "already disabled"))
        elif pkg in guarded:
            skipped.append((pkg, f"kept: it {guarded[pkg]}"))
        elif wifi and wifi[pkg]:
            # Android deletes an app's saved networks when the app is disabled.
            skipped.append((pkg, f"kept: it created {wifi[pkg]} saved Wi-Fi network(s), which disabling it "
                                 "deletes. Forget and re-join them in Settings > Network & Internet first"))
        else:
            todo.append(pkg)
    return todo, skipped


class Box:
    """The TV box, reached through a shell function so the tests can stand in for adb."""

    def __init__(self, shell):
        self.sh = shell  # (command: str) -> output

    def packages(self, flag: str = "") -> set[str]:
        out = self.sh(f"pm list packages {flag}".strip())
        return {line.split(":", 1)[1].strip() for line in out.splitlines() if line.startswith("package:")}

    def disable(self, pkg: str) -> bool:
        return "new state: disabled" in self.sh(f"pm disable-user --user 0 {pkg}")

    def enable(self, pkg: str) -> bool:
        return "new state: enabled" in self.sh(f"pm enable --user 0 {pkg}")

    def home(self) -> str:
        """The activity that answers the Home button right now."""
        out = self.sh(f"cmd package resolve-activity --brief {HOME_INTENT}")
        found = [line.strip() for line in out.splitlines() if COMPONENT.match(line.strip())]
        return found[-1] if found else ""

    def query(self, command: str) -> list[tuple[str, int]]:
        """The activities, services or keyboards a `cmd package query-*` or `ime list` command names,
        each with its intent-filter priority (0 where the answer gives none)."""
        found: list[tuple[str, int]] = []
        priority = 0
        for line in self.sh(command).splitlines():
            text = line.strip()
            given = re.match(r"priority=(-?\d+)", text)
            if given:
                priority = int(given.group(1))
            elif COMPONENT.match(text):
                found.append((text, priority))
                priority = 0
        return found

    def home_claimants(self) -> list[tuple[str, int]]:
        """Every enabled activity that can answer Home, with its priority."""
        return self.query(f"cmd package query-activities --brief {HOME_INTENT}")

    def guarded(self) -> dict[str, str]:
        """The packages that open Settings, play the inputs or channels, or are keyboards, with why."""
        found: dict[str, str] = {}
        for command, why in GUARDS:
            packages = {component.split("/")[0] for component, _ in self.query(command)}
            if command == GUARDS[0][0] and not packages:
                raise Refused("the box didn't say which app opens Settings, so nothing was changed. "
                              "This needs Android 8 or later.")
            for pkg in sorted(packages):
                found.setdefault(pkg, why)
        return found

    def set_home(self, component: str) -> None:
        if not COMPONENT.match(component):
            raise Refused(f"{component!r} is not an activity name")
        self.sh(f"cmd package set-home-activity {component}")

    def on_screen(self) -> str:
        """The activity in front, from the activity manager's dump."""
        match = re.search(r"ResumedActivity[:=]\s*ActivityRecord\{\S+ \S+ ([\w.]+/[\w.$]+)",
                          self.sh("dumpsys activity activities"))
        return match.group(1) if match else ""

    def launches(self, component: str, settle: float) -> bool:
        """Starts an activity directly and checks that it is the one on screen."""
        started = "Status: ok" in self.sh(f"am start -W -n {component}")
        time.sleep(settle)
        return started and self.on_screen().startswith(component.split("/")[0] + "/")

    def shows_after_home(self, settle: float) -> str:
        """Presses Home and returns the activity that comes up."""
        self.sh("input keyevent KEYCODE_HOME")
        time.sleep(settle)
        return self.on_screen()

    def wifi_creators(self) -> Counter | None:
        """How many saved Wi-Fi networks each app created, or None if the firmware won't say.

        Android deletes an app's saved networks when the app is disabled. Networks joined during first
        setup belong to the setup wizard, so those must be re-joined before it goes.
        """
        creators = re.findall(r"\bcname=(\S+)", self.sh("dumpsys wifi"))
        if creators:
            return Counter(creator.split(":", 1)[0] for creator in creators)
        listed = self.sh("cmd wifi list-networks")
        return Counter() if not listed.strip() or "no networks" in listed.lower() else None

    def wifi_owned_by(self, packages: list[str]) -> int | None:
        """How many saved Wi-Fi networks [packages] created, or None if the firmware won't say."""
        creators = self.wifi_creators()
        return None if creators is None else sum(creators[pkg] for pkg in packages)

    def props(self) -> dict[str, str]:
        """Every system property, from a single getprop."""
        found = {}
        for line in self.sh("getprop").splitlines():
            match = re.match(r"^\[([^\]]+)\]: \[(.*)\]$", line.strip())
            if match:
                found[match.group(1)] = match.group(2)
        return found

    def prop(self, name: str) -> str:
        return self.sh(f"getprop {name}").strip()


class State:
    """What this tool changed on one box, saved before each change so restore can undo it."""

    def __init__(self, path: Path):
        self.path = path
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        self.disabled: list[str] = data.get("disabled", [])
        self.home_before: str | None = data.get("home_before")
        # Launchers set-home disabled that were found through the Home intent rather than by name:
        # restore turns them on first, like the stock ones.
        self.launchers: list[str] = data.get("launchers", [])

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        payload = {"disabled": self.disabled, "home_before": self.home_before, "launchers": self.launchers}
        tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        tmp.replace(self.path)

    def about_to_disable(self, pkg: str) -> None:
        if pkg not in self.disabled:
            self.disabled.append(pkg)
            self.save()

    def forget(self, pkg: str) -> None:
        if pkg in self.disabled or pkg in self.launchers:
            self.disabled = [name for name in self.disabled if name != pkg]
            self.launchers = [name for name in self.launchers if name != pkg]
            self.save()


def confirm(question: str, assume_yes: bool) -> bool:
    if assume_yes:
        return True
    try:
        return input(f"{question} [y/N] ").strip().lower() in ("y", "yes")
    except EOFError:
        return False


def disable_all(box: Box, state: State, packages: list[str]) -> list[str]:
    """Disables each package, recording it first; returns the ones that failed."""
    failed = []
    for pkg in packages:
        state.about_to_disable(pkg)
        if box.disable(pkg):
            print(f"  disabled  {pkg}")
        else:
            state.forget(pkg)
            failed.append(pkg)
            print(f"  FAILED    {pkg}")
    return failed


def wifi_problem(owned: int | None) -> str:
    if owned is None:
        return ("This firmware doesn't say which app created the saved Wi-Fi networks, and disabling the "
                "setup wizard deletes the ones it created. In Settings > Network & Internet, forget and "
                "re-join every saved network (out-of-range ones too), then run set-home --wifi-checked.")
    return (f"{owned} saved Wi-Fi network(s) were created by the setup wizard, and disabling it deletes "
            "them. In Settings > Network & Internet, forget and re-join every saved network (out-of-range "
            "ones too), then run set-home again.")


def list_notes(device: Device, folder: Path) -> list[str]:
    """The notes of the lists that fit [device]; none if the lists can't be read."""
    try:
        return [note for found, _ in fitting_lists(device, folder) for note in found.notes]
    except Refused:
        return []


def cmd_probe(box: Box, state: State, lists_dir: Path = LISTS_DIR) -> int:
    installed, disabled = box.packages(), box.packages("-d")
    device = detect(box, installed, disabled)
    print(f"Box:      {device.name()}, brand {device.prop('ro.product.brand') or 'unknown'}")
    print(f"Android:  {device.prop('ro.build.version.release') or '?'} (SDK {device.prop('ro.build.version.sdk') or '?'})")
    print(f"Platform: {PLATFORM_HOW[device.platform]}")
    print(f"Home:     {device.home or 'unknown'}")
    for pkg in device.launchers:
        how = "" if device.launcher_known else ", found through the Home intent: set-home tries without disabling it first"
        print(f"Stock:    {pkg} ({'disabled' if pkg in disabled else 'enabled'}{how})")
    if not device.launchers:
        print("Stock:    none found, so set-home only makes Zapparr the Home setting")
    wizards = wizards_to_disable(device, installed, disabled)
    for pkg in SETUP_WIZARDS:
        if pkg in installed and pkg not in disabled:
            what = "set-home disables it with the launcher" if pkg in wizards else \
                "it doesn't answer Home here, so set-home leaves it alone"
            print(f"Setup:    {pkg}: {what}")
    creators = box.wifi_creators()
    if creators:
        print("Wi-Fi:    saved networks were created by "
              + ", ".join(f"{name} ({count})" for name, count in sorted(creators.items())))
    if wizards:
        owned = None if creators is None else sum(creators[pkg] for pkg in wizards)
        print("Wi-Fi:    " + ("no saved network belongs to the setup wizard" if owned == 0 else wifi_problem(owned)))
    leaving = [pkg for pkg in device.launchers if pkg not in disabled]
    problem = boot_problem(device, wizards + leaving) if leaving else ""
    if problem:
        print(f"Boot:     set-home won't disable {', '.join(leaving)}: {problem}")
    print(f"Zapparr:  {'installed' if ZAPPARR in installed else 'not installed'}")
    chosen = fitting_lists(device, lists_dir)
    print("Lists:    " + (", ".join(f"{found.path.name} ({why})" for found, why in chosen)
                          or f"none in {lists_dir} fits this box"))
    if device.platform == "other":
        print(UNKNOWN_NOTE)
    for found, _ in chosen:
        for note in found.notes:
            print(f"Note:     {note}")
    if state.disabled:
        print(f"Disabled by this tool: {', '.join(state.disabled)}")
    return 0


def cmd_plan(box: Box, state: State, wanted: list[str] | None, apply: bool, assume_yes: bool,
             lists: list[ListFile] | None = None, lists_dir: Path = LISTS_DIR) -> int:
    """Shows what apply would disable, and with [apply] disables it. [wanted] None means the packages
    of the lists in [lists_dir] that fit the box; [lists] are the list files named, if any."""
    installed, disabled = box.packages(), box.packages("-d")
    device = detect(box, installed, disabled)
    print(f"Box:      {device.name()}, {PLATFORMS[device.platform]}")
    if wanted is None:
        chosen = fitting_lists(device, lists_dir)
        if not chosen:
            raise Refused(f"no list in {lists_dir} fits this box. Name one: plan <list>")
        print("Lists:    " + ", ".join(f"{found.path.name} ({why})" for found, why in chosen))
        if device.platform == "other":
            print(UNKNOWN_NOTE)
        wanted = packages_in(found for found, _ in chosen)
    for found in lists or ():
        if (found.platforms or found.matches) and not found.fit(device):
            print(f"Note:     {found.path.name} is meant for {found.meant_for()}, not this box.")
    homes = set(device.launchers)
    if real_home(device.home):
        homes.add(device.home.split("/")[0])
    wifi = box.wifi_creators()
    todo, skipped = plan(wanted, installed, disabled, guarded=box.guarded(), wifi=wifi, homes=homes)
    for pkg, why in skipped:
        print(f"  skip      {pkg}  ({why})")
    for pkg in todo:
        print(f"  disable   {pkg}")
    if not todo:
        print("Nothing to disable.")
        return 0
    if wifi is None:
        print("Note: this firmware doesn't say which app created each saved Wi-Fi network. Disabling the app "
              "that created one deletes it, so check the box is still online after apply.")
    if not apply:
        print(f"\n{len(todo)} to disable. `apply` does it, and `restore` undoes it.")
        return 0
    if not confirm(f"Disable {len(todo)} package(s)?", assume_yes):
        return 1
    failed = disable_all(box, state, todo)
    print(f"Done: {len(todo) - len(failed)} disabled. Record: {state.path}")
    return 1 if failed else 0


def cmd_set_home(box: Box, state: State, assume_yes: bool, wifi_checked: bool, settle: float = 2.0,
                 lists_dir: Path = LISTS_DIR) -> int:
    installed, disabled = box.packages(), box.packages("-d")
    if ZAPPARR not in installed:
        raise Refused("Zapparr isn't installed. Install it first: adb install -r app-release.apk")
    device = detect(box, installed, disabled)
    for pkg in device.launchers:
        if pkg in HOME_LOCKED:
            raise Refused(HOME_LOCKED[pkg])
    launchers = [pkg for pkg in device.launchers if pkg not in disabled]
    if launchers and not device.launcher_known:
        # Found through the Home intent, not by name, so it may also be what opens Settings or the inputs.
        guarded = box.guarded()
        for pkg in launchers:
            if pkg in guarded:
                print(f"Leaving {pkg} enabled: it {guarded[pkg]}.")
        launchers = [pkg for pkg in launchers if pkg not in guarded]
    # The setup wizard goes first: on Google TV it re-enables the stock launcher, and its recovery
    # screen takes Home once the launcher is gone. Both go in the same step.
    wizards = wizards_to_disable(device, installed, disabled)
    targets = wizards + launchers
    if wizards:
        owned = box.wifi_owned_by(wizards)
        if owned or (owned is None and not wifi_checked):
            raise Refused(wifi_problem(owned))
    problem = boot_problem(device, targets) if launchers else ""
    if problem:
        raise Refused(f"set-home won't disable {', '.join(launchers)}: {problem}. Nothing was changed.")
    # A launcher found only through the Home intent may not outrank Zapparr, so try without disabling it.
    try_first = bool(launchers) and not device.launcher_known and not wizards

    home_before = device.home
    print(f"Home now: {home_before or 'unknown'}")
    for note in list_notes(device, lists_dir):
        print(f"Note:     {note}")
    if try_first:
        print(f"Will check Zapparr starts, make it Home and press Home. Only if {', '.join(launchers)} still "
              "takes Home, disable it and press Home again, undoing it all if Home doesn't land on Zapparr.")
    else:
        print(f"Will check Zapparr starts, make it Home, disable {', '.join(targets) or 'nothing'}, "
              "then press Home, undoing it all if Home doesn't land on Zapparr.")
    if not confirm("Go ahead?", assume_yes):
        return 1
    if not box.launches(ZAPPARR_HOME, settle):
        raise Refused("Zapparr didn't start, so nothing was changed.")
    # Close that test launch, or it lingers as a second Zapparr beside the one Home starts.
    box.sh(f"am force-stop {ZAPPARR}")
    recorded_home = False
    if real_home(home_before) and not home_before.startswith(ZAPPARR + "/") and not state.home_before:
        state.home_before = home_before
        state.save()
        recorded_home = True

    box.set_home(ZAPPARR_HOME)
    if try_first and box.shows_after_home(settle).startswith(ZAPPARR + "/"):
        print(f"Zapparr is Home, and {', '.join(launchers)} stays enabled. `restore` gives Home back.")
        return 0
    # Google TV's launcher keeps answering Home while it is enabled, whatever the preferred Home is,
    # so the real check can only come after it is disabled.
    for pkg in launchers:
        if pkg not in STOCK_LAUNCHERS and pkg not in state.launchers:
            state.launchers.append(pkg)
    failed = disable_all(box, state, targets)
    # Android drops the Home setting whenever the set of Home apps changes, so set it again.
    box.set_home(ZAPPARR_HOME)
    if box.shows_after_home(settle).startswith(ZAPPARR + "/"):
        print("Zapparr is Home. `restore` gives Home back to the stock launcher.")
        return 1 if failed else 0

    for pkg in reversed(targets):
        if pkg not in failed and box.enable(pkg):
            state.forget(pkg)
    if real_home(home_before):
        box.set_home(home_before)
    if recorded_home:
        state.home_before = None
        state.save()
    raise Refused("Home didn't land on Zapparr, so everything was re-enabled and Home handed back.")


def cmd_restore(box: Box, state: State, assume_yes: bool) -> int:
    if not state.disabled and not state.home_before:
        print("Nothing recorded for this box, so nothing to restore.")
        return 0
    # Launchers first so Home always has somewhere to go, then the rest, newest first.
    order = [pkg for pkg in state.disabled if pkg in STOCK_LAUNCHERS or pkg in state.launchers]
    order += [pkg for pkg in reversed(state.disabled) if pkg not in order]
    if order:
        print(f"Will re-enable: {', '.join(order)}")
    if state.home_before:
        print(f"Will hand Home back to {state.home_before}")
    if not confirm("Go ahead?", assume_yes):
        return 1

    failed = []
    for pkg in order:
        if box.enable(pkg):
            state.forget(pkg)
            print(f"  enabled   {pkg}")
        else:
            failed.append(pkg)
            print(f"  FAILED    {pkg}")
    home = state.home_before
    if home and real_home(home):
        box.set_home(home)
        print(f"Home is {home} again.")
    state.home_before = None
    state.save()
    print("Zapparr is still installed; remove it in Settings > Apps if you want it gone.")
    return 1 if failed else 0


def run(command: list[str]) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=90)
    except subprocess.TimeoutExpired as e:
        raise Refused(f"adb timed out: {' '.join(command[1:])}") from e
    return result.stdout + result.stderr


def connect(adb: str, host: str | None, serial: str | None) -> tuple[Box, str | None]:
    if host:
        target = host if ":" in host else f"{host}:5555"
        out = run([adb, "connect", target])
        if "connected to" not in out and "authenticate" not in out:
            raise Refused(f"couldn't reach {target}: {out.strip()}\nIs USB debugging on "
                          "(Settings > System > Developer options), and is this computer on the same network?")
        serial = target
    base = [adb] + (["-s", serial] if serial else [])
    deadline = time.monotonic() + 60
    asked = False
    while (status := run(base + ["get-state"]).strip()) != "device":
        if "unauthorized" in status and not asked:
            print('Accept "Allow USB debugging?" on the TV (check "Always allow from this computer").')
            asked = True
        if time.monotonic() > deadline:
            raise Refused(f"the box didn't become ready: {status or 'no answer'}")
        time.sleep(2)
    return Box(lambda command: run(base + ["shell", command])), serial


def state_for(box: Box, serial: str | None) -> State:
    ident = box.prop("ro.serialno") or serial or "default"
    return State(STATE_DIR / (re.sub(r"[^A-Za-z0-9._-]", "_", ident) + ".json"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="debloat.py", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", help="the box's IP address; port 5555 unless you add :PORT")
    parser.add_argument("--serial", help="an adb serial to use instead of --host")
    parser.add_argument("--adb", help="path to adb (default: adb on PATH)")
    parser.add_argument("-y", "--yes", action="store_true", help="don't ask before changing anything")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("probe", help="show what the box is, the lists that fit it, what answers Home, "
                                      "and who owns the saved Wi-Fi")
    for name, text in (("plan", "show what apply would disable; changes nothing"),
                       ("apply", "disable the packages in the lists")):
        commands.add_parser(name, help=text).add_argument(
            "lists", nargs="*", type=Path,
            help="list files to use instead of the ones probe names, e.g. tools/lists/onn-4k-2023.txt")
    home = commands.add_parser("set-home", help="make Zapparr Home, then disable the stock launcher and setup wizard")
    home.add_argument("--wifi-checked", action="store_true",
                      help="every saved Wi-Fi network has been re-joined in Settings; only needed when probe can't tell")
    commands.add_parser("restore", help="re-enable everything this tool disabled and give Home back")
    args = parser.parse_args(argv)

    try:
        named = [parse_list(path) for path in getattr(args, "lists", None) or ()]
        if args.command in ("plan", "apply") and not named:
            read_lists(LISTS_DIR)  # a broken list stops here, before anything reaches the box
        adb = args.adb or shutil.which("adb")
        if not adb:
            raise Refused("adb not found. Install Android platform-tools (Windows: winget install "
                          "Google.PlatformTools; macOS: brew install android-platform-tools; "
                          "Debian/Ubuntu: sudo apt install adb), or pass --adb.")
        box, serial = connect(adb, args.host, args.serial)
        state = state_for(box, serial)
        if args.command == "probe":
            return cmd_probe(box, state)
        if args.command in ("plan", "apply"):
            return cmd_plan(box, state, packages_in(named) if named else None, args.command == "apply",
                            args.yes, lists=named)
        if args.command == "set-home":
            return cmd_set_home(box, state, args.yes, args.wifi_checked)
        return cmd_restore(box, state, args.yes)
    except Refused as e:
        print(f"Stopped: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
