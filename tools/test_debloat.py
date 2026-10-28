"""Tests for debloat.py against a pretend onn box. Run: python -m unittest discover -s tools"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import debloat as d  # noqa: E402

LAUNCHER = "com.google.android.apps.tv.launcherx"
LAUNCHER_HOME = f"{LAUNCHER}/{LAUNCHER}.home.HomeActivity"
FEEDBACK = "com.google.android.feedback"
GAMES = "com.google.android.play.games"


class FakeBox:
    """Answers the shell commands debloat.py sends, roughly as an onn 4K box does.

    Like the Google TV 14 emulator, the stock launcher answers Home while it is enabled, whatever
    the preferred Home activity is. [other_home] is any other activity that grabs Home when enabled.
    """

    def __init__(self, installed, wifi_creators=("com.android.tv.settings",), zapparr_starts=True,
                 other_home=None):
        self.installed = set(installed)
        self.disabled = set()
        self.home = LAUNCHER_HOME
        self.on_screen = ""
        self.wifi_creators = list(wifi_creators)
        self.zapparr_starts = zapparr_starts
        self.other_home = other_home
        if other_home:
            self.installed.add(other_home.split("/")[0])
        self.log = []

    def enabled(self, pkg):
        return pkg in self.installed and pkg not in self.disabled

    def answers_home(self):
        for component in (LAUNCHER_HOME, self.other_home):
            if component and self.enabled(component.split("/")[0]):
                return component
        pkg = self.home.split("/")[0]
        return self.home if self.enabled(pkg) and (pkg != d.ZAPPARR or self.zapparr_starts) else ""

    def shell(self, command):
        self.log.append(command)
        words = command.split()
        if command == "pm list packages":
            return "".join(f"package:{p}\n" for p in sorted(self.installed))
        if command == "pm list packages -d":
            return "".join(f"package:{p}\n" for p in sorted(self.disabled))
        if words[:2] == ["pm", "disable-user"]:
            self.disabled.add(words[-1])
            return f"Package {words[-1]} new state: disabled-user\n"
        if words[:2] == ["pm", "enable"]:
            self.disabled.discard(words[-1])
            return f"Package {words[-1]} new state: enabled\n"
        if command.startswith("cmd package resolve-activity"):
            return f"priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n{self.answers_home()}\n"
        if command.startswith("cmd package set-home-activity"):
            self.home = words[-1]
            return "Success\n"
        if words[:3] == ["am", "start", "-W"]:
            component = words[-1]
            if component.split("/")[0] == d.ZAPPARR and not self.zapparr_starts:
                return "Starting: Intent { cmp=tv.zapparr/.home.HomeActivity }\nError: Activity not started\n"
            self.on_screen = component
            return f"Status: ok\nLaunchState: COLD\nActivity: {component}\nComplete\n"
        if command == "input keyevent KEYCODE_HOME":
            self.on_screen = self.answers_home()
            return ""
        if command == "dumpsys activity activities":
            return f"  topResumedActivity=ActivityRecord{{5d2e1a u0 {self.on_screen} t7}}\n" if self.on_screen else ""
        if command == "dumpsys wifi":
            return "".join(f'ID: {i} SSID: "net{i}" cuid=1000 cname={c} luid=1000\n'
                           for i, c in enumerate(self.wifi_creators))
        if command == "cmd wifi list-networks":
            return "No networks\n"
        raise AssertionError(f"unexpected command: {command}")


class DebloatTest(unittest.TestCase):

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.state = d.State(self.dir / "box.json")

    def write_list(self, text):
        path = self.dir / "list.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_list_drops_comments_and_repeats(self):
        path = self.write_list(f"# heading\n{FEEDBACK}   # telemetry\n\n{GAMES}\n{FEEDBACK}\n")
        self.assertEqual([FEEDBACK, GAMES], d.read_list(path))

    def test_list_lets_nothing_but_package_names_reach_the_shell(self):
        with self.assertRaises(d.Refused):
            d.read_list(self.write_list("com.example.app; reboot\n"))

    def test_plan_leaves_protected_launcher_missing_and_disabled_packages_alone(self):
        wanted = ["com.google.android.gms", "com.droidlogic.tvinput", LAUNCHER, d.SETUP_WIZARD,
                  "com.example.gone", GAMES, FEEDBACK]
        todo, skipped = d.plan(wanted, installed={LAUNCHER, d.SETUP_WIZARD, GAMES, FEEDBACK,
                                                   "com.google.android.gms", "com.droidlogic.tvinput"},
                               disabled={GAMES})
        self.assertEqual([FEEDBACK], todo)
        self.assertEqual(wanted[:-1], [pkg for pkg, _ in skipped])

    def test_apply_records_each_package_and_restore_undoes_it(self):
        box = FakeBox([LAUNCHER, FEEDBACK, GAMES])
        wanted = d.read_list(self.write_list(f"{FEEDBACK}\n{GAMES}\n"))

        self.assertEqual(0, d.cmd_plan(d.Box(box.shell), self.state, wanted, apply=True, assume_yes=True))
        self.assertEqual({FEEDBACK, GAMES}, box.disabled)
        self.assertEqual([FEEDBACK, GAMES], d.State(self.state.path).disabled)

        self.assertEqual(0, d.cmd_restore(d.Box(box.shell), d.State(self.state.path), assume_yes=True))
        self.assertEqual(set(), box.disabled)
        self.assertEqual([], d.State(self.state.path).disabled)

    def test_plan_changes_nothing(self):
        box = FakeBox([FEEDBACK])
        d.cmd_plan(d.Box(box.shell), self.state, [FEEDBACK], apply=False, assume_yes=True)
        self.assertEqual(set(), box.disabled)
        self.assertFalse(self.state.path.exists())

    def test_set_home_refuses_while_the_setup_wizard_owns_a_saved_network(self):
        box = FakeBox([LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR],
                      wifi_creators=["com.android.tv.settings", d.SETUP_WIZARD])
        with self.assertRaises(d.Refused):
            d.cmd_set_home(d.Box(box.shell), self.state, assume_yes=True, wifi_checked=False, settle=0)
        self.assertEqual(set(), box.disabled)
        self.assertEqual(LAUNCHER_HOME, box.home)

    def test_set_home_switches_home_then_disables_the_wizard_and_launcher(self):
        box = FakeBox([LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR])

        self.assertEqual(0, d.cmd_set_home(d.Box(box.shell), self.state, True, False, settle=0))
        self.assertEqual(d.ZAPPARR_HOME, box.home)
        self.assertEqual({LAUNCHER, d.SETUP_WIZARD}, box.disabled)
        disables = [c.split()[-1] for c in box.log if c.startswith("pm disable-user")]
        self.assertEqual([d.SETUP_WIZARD, LAUNCHER], disables)
        first_launch = next(i for i, c in enumerate(box.log) if c.startswith("am start -W"))
        first_disable = next(i for i, c in enumerate(box.log) if c.startswith("pm disable-user"))
        self.assertLess(first_launch, first_disable, "Zapparr must be seen running before anything is disabled")

        self.assertEqual(0, d.cmd_restore(d.Box(box.shell), d.State(self.state.path), assume_yes=True))
        self.assertEqual(set(), box.disabled)
        self.assertEqual(LAUNCHER_HOME, box.home)

    def test_set_home_changes_nothing_when_zapparr_will_not_start(self):
        box = FakeBox([LAUNCHER, d.ZAPPARR], zapparr_starts=False)
        with self.assertRaises(d.Refused):
            d.cmd_set_home(d.Box(box.shell), self.state, True, False, settle=0)
        self.assertEqual(LAUNCHER_HOME, box.home)
        self.assertEqual(set(), box.disabled)
        self.assertFalse(any(c.startswith("cmd package set-home-activity") for c in box.log))

    def test_set_home_undoes_everything_when_home_lands_somewhere_else(self):
        other = "com.example.vendorhome/.Home"
        box = FakeBox([LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR], other_home=other)

        with self.assertRaises(d.Refused):
            d.cmd_set_home(d.Box(box.shell), self.state, True, False, settle=0)
        self.assertEqual(set(), box.disabled)
        self.assertEqual(LAUNCHER_HOME, box.answers_home())
        reloaded = d.State(self.state.path)
        self.assertEqual([], reloaded.disabled)
        self.assertIsNone(reloaded.home_before)

    def test_set_home_needs_zapparr_installed(self):
        box = FakeBox([LAUNCHER])
        with self.assertRaises(d.Refused):
            d.cmd_set_home(d.Box(box.shell), self.state, True, False, settle=0)
        self.assertEqual(LAUNCHER_HOME, box.home)


if __name__ == "__main__":
    unittest.main()
