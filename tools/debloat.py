#!/usr/bin/env python3
"""Debloat an Android TV or Google TV box over ADB, reversibly, and make Zapparr its Home.

Nothing is uninstalled. Packages are switched off with `pm disable-user`, the same switch as
Settings > Apps > Disable, and each change is written down before it is made, so `restore`
puts the box back the way it was.

  python tools/debloat.py --host 192.168.1.50 probe
  python tools/debloat.py --host 192.168.1.50 plan tools/lists/onn-4k-2023.txt
  python tools/debloat.py --host 192.168.1.50 apply tools/lists/onn-4k-2023.txt
  python tools/debloat.py --host 192.168.1.50 set-home
  python tools/debloat.py --host 192.168.1.50 restore

Needs adb from Android platform-tools on PATH (or --adb). See docs/debloat.md.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ZAPPARR = "tv.zapparr"
ZAPPARR_HOME = "tv.zapparr/.home.HomeActivity"
SETUP_WIZARD = "com.google.android.tungsten.setupwraith"
STOCK_LAUNCHERS = (
    "com.google.android.apps.tv.launcherx",  # Google TV
    "com.google.android.tvlauncher",  # Android TV
    "com.google.android.leanbacklauncher",  # older Android TV
)
# Never disabled, whatever a list says: the box needs these to boot, install apps, take input
# from the remote, or reach Settings.
PROTECTED = frozenset({
    ZAPPARR,
    "android",
    "com.android.bluetooth",
    "com.android.inputdevices",
    "com.android.packageinstaller",
    "com.android.permissioncontroller",
    "com.android.providers.settings",
    "com.android.providers.tv",  # TvProvider: channels and Watch Next rows
    "com.android.settings",
    "com.android.shell",
    "com.android.systemui",
    "com.android.tv.settings",
    "com.android.vending",  # Play Store
    "com.google.android.gms",  # Play Services
    "com.google.android.gsf",
    "com.google.android.inputmethod.latin",  # Gboard
    "com.google.android.leanback.ime",
    "com.google.android.packageinstaller",
    "com.google.android.permissioncontroller",
    "com.google.android.tv.dfuservice",  # remote firmware updates
    "com.google.android.tv.remote.service",  # phone remote
    "com.gretzky.GlobalKey",  # onn: the remote's app buttons and mic
    "com.onn.bluetoothconnect",
})
PROTECTED_PREFIXES = ("com.droidlogic.", "com.amlogic.")  # Amlogic vendor: HDMI, picture, remote pairing
STATE_DIR = Path.home() / ".zapparr" / "debloat"

PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
COMPONENT = re.compile(r"^[A-Za-z][\w.]*/[\w.$]+$")


class Refused(Exception):
    """A step the tool won't take; the message says why and what to do instead."""


def is_protected(pkg: str) -> bool:
    return pkg in PROTECTED or pkg.startswith(PROTECTED_PREFIXES)


def read_list(path: Path) -> list[str]:
    """Package names from a list file: one per line, and '#' starts a comment."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as e:
        raise Refused(f"can't read {path}: {e.strerror}") from e
    packages: list[str] = []
    for number, line in enumerate(lines, 1):
        name = line.split("#", 1)[0].strip()
        if not name:
            continue
        # Names go into adb shell commands, so nothing but a package name gets through.
        if not PACKAGE.match(name):
            raise Refused(f"{path}:{number}: {name!r} is not a package name")
        if name not in packages:
            packages.append(name)
    return packages


def plan(wanted: list[str], installed: set[str], disabled: set[str]) -> tuple[list[str], list[tuple[str, str]]]:
    """Splits a list into packages to disable now and (package, reason) pairs left alone."""
    todo: list[str] = []
    skipped: list[tuple[str, str]] = []
    for pkg in wanted:
        if is_protected(pkg):
            skipped.append((pkg, "protected: the box needs it"))
        elif pkg in STOCK_LAUNCHERS or pkg == SETUP_WIZARD:
            skipped.append((pkg, "left to set-home, once Zapparr is Home"))
        elif pkg not in installed:
            skipped.append((pkg, "not installed"))
        elif pkg in disabled:
            skipped.append((pkg, "already disabled"))
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
        out = self.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        found = [line.strip() for line in out.splitlines() if COMPONENT.match(line.strip())]
        return found[-1] if found else ""

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

    def wifi_owned_by_setup(self) -> int | None:
        """How many saved Wi-Fi networks the setup wizard created, or None if the firmware won't say.

        Disabling the setup wizard deletes the networks it created, so those must be re-joined first.
        """
        creators = re.findall(r"\bcname=(\S+)", self.sh("dumpsys wifi"))
        if creators:
            return sum(1 for creator in creators if creator.startswith(SETUP_WIZARD))
        listed = self.sh("cmd wifi list-networks")
        return 0 if not listed.strip() or "no networks" in listed.lower() else None

    def prop(self, name: str) -> str:
        return self.sh(f"getprop {name}").strip()


class State:
    """What this tool changed on one box, saved before each change so restore can undo it."""

    def __init__(self, path: Path):
        self.path = path
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        self.disabled: list[str] = data.get("disabled", [])
        self.home_before: str | None = data.get("home_before")

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        payload = {"disabled": self.disabled, "home_before": self.home_before}
        tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        tmp.replace(self.path)

    def about_to_disable(self, pkg: str) -> None:
        if pkg not in self.disabled:
            self.disabled.append(pkg)
            self.save()

    def forget(self, pkg: str) -> None:
        if pkg in self.disabled:
            self.disabled.remove(pkg)
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


def cmd_probe(box: Box, state: State) -> int:
    installed, disabled = box.packages(), box.packages("-d")
    print(f"Box:      {box.prop('ro.product.manufacturer')} {box.prop('ro.product.model')} "
          f"({box.prop('ro.product.device')})")
    print(f"Android:  {box.prop('ro.build.version.release')} (SDK {box.prop('ro.build.version.sdk')})")
    print(f"Home:     {box.home() or 'unknown'}")
    for pkg in STOCK_LAUNCHERS:
        if pkg in installed:
            print(f"Stock:    {pkg} ({'disabled' if pkg in disabled else 'enabled'})")
    print(f"Zapparr:  {'installed' if ZAPPARR in installed else 'not installed'}")
    if SETUP_WIZARD in installed and SETUP_WIZARD not in disabled:
        owned = box.wifi_owned_by_setup()
        print("Wi-Fi:    " + ("no saved network belongs to the setup wizard" if owned == 0 else wifi_problem(owned)))
    if state.disabled:
        print(f"Disabled by this tool: {', '.join(state.disabled)}")
    return 0


def cmd_plan(box: Box, state: State, wanted: list[str], apply: bool, assume_yes: bool) -> int:
    todo, skipped = plan(wanted, box.packages(), box.packages("-d"))
    for pkg, why in skipped:
        print(f"  skip      {pkg}  ({why})")
    for pkg in todo:
        print(f"  disable   {pkg}")
    if not todo:
        print("Nothing to disable.")
        return 0
    if not apply:
        print(f"\n{len(todo)} to disable. `apply` does it, and `restore` undoes it.")
        return 0
    if not confirm(f"Disable {len(todo)} package(s)?", assume_yes):
        return 1
    failed = disable_all(box, state, todo)
    print(f"Done: {len(todo) - len(failed)} disabled. Record: {state.path}")
    return 1 if failed else 0


def cmd_set_home(box: Box, state: State, assume_yes: bool, wifi_checked: bool, settle: float = 2.0) -> int:
    installed, disabled = box.packages(), box.packages("-d")
    if ZAPPARR not in installed:
        raise Refused("Zapparr isn't installed. Install it first: adb install -r app-release.apk")
    # The setup wizard goes first: on Google TV it re-enables the stock launcher, and its recovery
    # screen takes Home once the launcher is gone. Both go in the same step.
    targets = [SETUP_WIZARD] if SETUP_WIZARD in installed and SETUP_WIZARD not in disabled else []
    targets += [pkg for pkg in STOCK_LAUNCHERS if pkg in installed and pkg not in disabled]
    if SETUP_WIZARD in targets:
        owned = box.wifi_owned_by_setup()
        if owned or (owned is None and not wifi_checked):
            raise Refused(wifi_problem(owned))

    home_before = box.home()
    print(f"Home now: {home_before or 'unknown'}")
    print(f"Will check Zapparr starts, make it Home, disable {', '.join(targets) or 'nothing'}, "
          "then press Home, undoing it all if Home doesn't land on Zapparr.")
    if not confirm("Go ahead?", assume_yes):
        return 1
    if not box.launches(ZAPPARR_HOME, settle):
        raise Refused("Zapparr didn't start, so nothing was changed.")
    recorded_home = False
    if home_before and not home_before.startswith(ZAPPARR + "/") and not state.home_before:
        state.home_before = home_before
        state.save()
        recorded_home = True

    box.set_home(ZAPPARR_HOME)
    # Google TV's launcher keeps answering Home while it is enabled, whatever the preferred Home is,
    # so the real check can only come after it is disabled.
    failed = disable_all(box, state, targets)
    if box.shows_after_home(settle).startswith(ZAPPARR + "/"):
        print("Zapparr is Home. `restore` gives Home back to the stock launcher.")
        return 1 if failed else 0

    for pkg in reversed(targets):
        if pkg not in failed and box.enable(pkg):
            state.forget(pkg)
    if COMPONENT.match(home_before) and "ResolverActivity" not in home_before:
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
    order = [pkg for pkg in state.disabled if pkg in STOCK_LAUNCHERS]
    order += [pkg for pkg in reversed(state.disabled) if pkg not in STOCK_LAUNCHERS]
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
    if home and COMPONENT.match(home) and "ResolverActivity" not in home:
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
    commands.add_parser("probe", help="show the box, what answers Home, and who owns the saved Wi-Fi")
    for name, text in (("plan", "show what apply would disable; changes nothing"),
                       ("apply", "disable the packages in a list")):
        commands.add_parser(name, help=text).add_argument("list", type=Path, help="e.g. tools/lists/onn-4k-2023.txt")
    home = commands.add_parser("set-home", help="make Zapparr Home, then disable the stock launcher and setup wizard")
    home.add_argument("--wifi-checked", action="store_true",
                      help="every saved Wi-Fi network has been re-joined in Settings; only needed when probe can't tell")
    commands.add_parser("restore", help="re-enable everything this tool disabled and give Home back")
    args = parser.parse_args(argv)

    try:
        wanted = read_list(args.list) if args.command in ("plan", "apply") else []
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
            return cmd_plan(box, state, wanted, args.command == "apply", args.yes)
        if args.command == "set-home":
            return cmd_set_home(box, state, args.yes, args.wifi_checked)
        return cmd_restore(box, state, args.yes)
    except Refused as e:
        print(f"Stopped: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
