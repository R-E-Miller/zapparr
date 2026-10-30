"""Tests for debloat.py against pretend boxes. Run: python -m unittest discover -s tools"""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import debloat as d  # noqa: E402

LAUNCHER = "com.google.android.apps.tv.launcherx"
LAUNCHER_HOME = f"{LAUNCHER}/{LAUNCHER}.home.HomeActivity"
TV_LAUNCHER = "com.google.android.tvlauncher"
TV_LAUNCHER_HOME = f"{TV_LAUNCHER}/.MainActivity"
LEANBACK = "com.google.android.leanbacklauncher"
VENDOR = "com.cvte.tv.launcher.space"
VENDOR_HOME = f"{VENDOR}/.MainActivity"
RECOVERY = f"{d.SETUP_WIZARD}/.RecoveryActivity"
SETTINGS = "com.android.tv.settings"
FALLBACK = f"{SETTINGS}/.system.FallbackHome"
CHOOSER = "android/com.android.internal.app.ResolverActivity"
FEEDBACK = "com.google.android.feedback"
GAMES = "com.google.android.play.games"
PRINT = "com.android.printspooler"

ONN = {"ro.product.manufacturer": "onn", "ro.product.brand": "onn", "ro.product.model": "4K Streaming Box",
       "ro.product.device": "YOC", "ro.product.name": "onn_4k_gtv", "ro.build.version.release": "14",
       "ro.build.version.sdk": "34", "ro.crypto.type": "file"}
CHROMECAST = {"ro.product.manufacturer": "Google", "ro.product.brand": "google", "ro.product.model": "Chromecast",
              "ro.product.device": "sabrina", "ro.build.version.release": "14", "ro.crypto.type": "file"}
SHIELD = {"ro.product.manufacturer": "NVIDIA", "ro.product.brand": "NVIDIA", "ro.product.model": "SHIELD Android TV",
          "ro.product.device": "mdarcy", "ro.build.version.release": "11", "ro.crypto.type": "file"}
SONY = {"ro.product.manufacturer": "Sony", "ro.product.brand": "Sony", "ro.product.model": "BRAVIA 4K VH2",
        "ro.product.device": "BRAVIA_VH2", "ro.build.version.release": "12", "ro.crypto.type": "file"}
UNKNOWN = {"ro.product.manufacturer": "Acme", "ro.product.brand": "Acme", "ro.product.model": "TV Box 9",
           "ro.product.device": "acme9", "ro.build.version.release": "13", "ro.crypto.type": "file"}
FIRE = {"ro.product.manufacturer": "Amazon", "ro.product.model": "AFTMM", "ro.product.device": "mantis"}


class FakeBox:
    """Answers the shell commands debloat.py sends, roughly as an onn 4K box does unless told otherwise.

    Home goes to the enabled activity with the highest Home priority, and among equals to the preferred
    one. So, like the Google TV 14 emulator, the stock launcher (priority 2) answers Home while it is
    enabled, whatever the preferred Home activity is; the setup wizard's recovery screen and
    [other_home] (priority 1) come next, then Zapparr (0) and Settings' FallbackHome (-1000).
    """

    def __init__(self, installed, wifi_creators=(SETTINGS,), zapparr_starts=True, other_home=None,
                 launcher_home=LAUNCHER_HOME, launcher_priority=2, wizard_answers_home=True, fallback=True,
                 props=None, settings_app=SETTINGS, inputs=(), keyboards=("com.google.android.inputmethod.latin",)):
        self.installed = set(installed) | {SETTINGS}
        self.disabled = set()
        self.home = launcher_home
        self.on_screen = ""
        self.wifi_creators = list(wifi_creators)
        self.zapparr_starts = zapparr_starts
        self.props = dict(ONN if props is None else props)
        self.settings_app = settings_app
        self.inputs = list(inputs)
        self.keyboards = list(keyboards)
        self.homes = [(launcher_home, launcher_priority)]
        if wizard_answers_home:
            self.homes.append((RECOVERY, 1))
        if other_home:
            self.homes.append((other_home, 1))
            self.installed.add(other_home.split("/")[0])
        self.homes.append((d.ZAPPARR_HOME, 0))
        if fallback:
            self.homes.append((FALLBACK, -1000))
        self.log = []

    def enabled(self, pkg):
        return pkg in self.installed and pkg not in self.disabled

    def claimants(self):
        return [(component, priority) for component, priority in self.homes
                if self.enabled(component.split("/")[0])
                and (component.split("/")[0] != d.ZAPPARR or self.zapparr_starts)]

    def answers_home(self):
        found = self.claimants()
        if not found:
            return ""
        top = max(priority for _, priority in found)
        best = [component for component, priority in found if priority == top]
        if len(best) == 1:
            return best[0]
        return self.home if self.home in best else CHOOSER

    @staticmethod
    def brief(components):
        return "".join(f"  #{i}:\n    priority={priority} preferredOrder=0 match=0x108000 specificIndex=-1\n"
                       f"    {component}\n" for i, (component, priority) in enumerate(components))

    def shell(self, command):
        self.log.append(command)
        words = command.split()
        guards = [guard for guard, _ in d.GUARDS]
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
        if command == f"cmd package query-activities --brief {d.HOME_INTENT}":
            return f"{len(self.claimants())} activities found:\n" + self.brief(self.claimants())
        if command == guards[0]:  # who opens Settings
            return self.brief([(f"{self.settings_app}/.MainSettings", 0)]) if self.enabled(self.settings_app) else ""
        if command == guards[1]:  # TV inputs
            return self.brief([(c, 0) for c in self.inputs if self.enabled(c.split("/")[0])])
        if command == guards[2]:  # channels and inputs
            return "No activities found\n"
        if command == guards[3]:  # keyboards
            return "".join(f"{pkg}/.Ime\n" for pkg in self.keyboards)
        if command.startswith("cmd package set-home-activity"):
            self.home = words[-1]
            return "Success\n"
        if words[:3] == ["am", "start", "-W"]:
            component = words[-1]
            if component.split("/")[0] == d.ZAPPARR and not self.zapparr_starts:
                return "Starting: Intent { cmp=tv.zapparr/.home.HomeActivity }\nError: Activity not started\n"
            self.on_screen = component
            return f"Status: ok\nLaunchState: COLD\nActivity: {component}\nComplete\n"
        if words[:2] == ["am", "force-stop"]:
            if self.on_screen.startswith(words[-1] + "/"):
                self.on_screen = ""
            return ""
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
        if command == "getprop":
            return "".join(f"[{name}]: [{value}]\n" for name, value in sorted(self.props.items()))
        raise AssertionError(f"unexpected command: {command}")


class Case(unittest.TestCase):

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.state = d.State(self.dir / "box.json")
        self.lists = self.dir / "lists"
        self.lists.mkdir()

    def write_list(self, text, name="list.txt"):
        path = self.dir / name
        path.write_text(text, encoding="utf-8")
        return path

    def add_list(self, name, text):
        (self.lists / name).write_text(text, encoding="utf-8")

    def add_generic_lists(self):
        self.add_list("google-tv.txt", f"# platform: google-tv\n{FEEDBACK}\n{GAMES}\n")
        self.add_list("android-tv.txt", f"# platform: android-tv\n{FEEDBACK}\n{GAMES}\n")
        self.add_list("other.txt", f"# platform: other\n{FEEDBACK}\n{PRINT}\n")

    def quietly(self, call, *args, **kwargs):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = call(*args, **kwargs)
        return result, out.getvalue()


class DebloatTest(Case):

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


class DetectionTest(unittest.TestCase):

    def test_platform_comes_from_the_launcher_installed(self):
        self.assertEqual("google-tv", d.platform_of({LAUNCHER, TV_LAUNCHER}))
        self.assertEqual("android-tv", d.platform_of({TV_LAUNCHER}))
        self.assertEqual("android-tv", d.platform_of({LEANBACK}))
        self.assertEqual("other", d.platform_of({VENDOR, FEEDBACK}))

    def test_properties_come_from_one_getprop(self):
        box = FakeBox([TV_LAUNCHER], props=SHIELD)
        props = d.Box(box.shell).props()
        self.assertEqual("NVIDIA", props["ro.product.manufacturer"])
        self.assertEqual("SHIELD Android TV", props["ro.product.model"])
        self.assertEqual(["getprop"], box.log)

    def test_detect_reads_the_box(self):
        box = FakeBox([TV_LAUNCHER, d.SETUP_WIZARD], launcher_home=TV_LAUNCHER_HOME, wizard_answers_home=False,
                      props=SHIELD)
        device = d.detect(d.Box(box.shell), box.installed, set())
        self.assertEqual("android-tv", device.platform)
        self.assertEqual(([TV_LAUNCHER], True), (device.launchers, device.launcher_known))
        self.assertEqual(TV_LAUNCHER_HOME, device.home)
        self.assertEqual([(TV_LAUNCHER_HOME, 2), (FALLBACK, -1000)], device.claimants)
        self.assertEqual("NVIDIA SHIELD Android TV (mdarcy)", device.name())

    def test_a_known_launcher_counts_only_when_it_can_answer_home(self):
        # On a Sony set, leanbacklauncher is installed but has no Home activity (CleanYourTV).
        claimants = [(TV_LAUNCHER_HOME, 2), (FALLBACK, -1000)]
        self.assertEqual(([TV_LAUNCHER], True),
                         d.stock_launchers({TV_LAUNCHER, LEANBACK}, set(), TV_LAUNCHER_HOME, claimants))
        # Already disabled by set-home: still the stock launcher, for probe and restore.
        self.assertEqual(([LAUNCHER], True),
                         d.stock_launchers({LAUNCHER}, {LAUNCHER}, d.ZAPPARR_HOME, [(d.ZAPPARR_HOME, 0)]))
        # A box that won't list what answers Home: every installed one counts, as before.
        self.assertEqual(([LAUNCHER], True), d.stock_launchers({LAUNCHER}, set(), "", []))

    def test_the_stock_launcher_falls_back_to_the_home_intent(self):
        claimants = [(VENDOR_HOME, 2), (RECOVERY, 1), (d.ZAPPARR_HOME, 0), (FALLBACK, -1000)]
        self.assertEqual(([VENDOR], False), d.stock_launchers(set(), set(), VENDOR_HOME, claimants))
        # Home on a setup wizard's recovery screen, FallbackHome or the chooser is no launcher, so
        # the only launcher that can answer Home is the one.
        for home in (RECOVERY, FALLBACK, CHOOSER, d.ZAPPARR_HOME, ""):
            with self.subTest(home=home):
                self.assertEqual(([VENDOR], False), d.stock_launchers(set(), set(), home, claimants))
        # Two launchers and no default: no guessing.
        both = claimants + [("com.example.home/.Home", 0)]
        self.assertEqual(([], False), d.stock_launchers(set(), set(), CHOOSER, both))

    def test_factory_screens_and_protected_homes_are_not_launchers_to_disable(self):
        self.assertTrue(d.launcher_candidate(VENDOR_HOME))
        self.assertFalse(d.launcher_candidate("com.duokan.factorytest/.FactoryLauncher"))
        self.assertFalse(d.launcher_candidate("org.droidtv.homeintentresolver/.HomeActivity"))
        self.assertFalse(d.launcher_candidate("com.tcl.keycustomfunctionservice/.FallbackHome"))
        self.assertTrue(d.real_home(d.ZAPPARR_HOME))
        self.assertFalse(d.real_home(CHOOSER))
        self.assertFalse(d.real_home(RECOVERY))

    def test_a_setup_wizard_goes_with_the_launcher_only_where_it_takes_home(self):
        installed = {LAUNCHER, TV_LAUNCHER, d.SETUP_WIZARD, "com.android.provision"}
        quiet = [(TV_LAUNCHER_HOME, 2), (FALLBACK, -1000)]
        android_tv = d.Device({}, "android-tv", [TV_LAUNCHER], True, TV_LAUNCHER_HOME, quiet)
        self.assertEqual([], d.wizards_to_disable(android_tv, installed, set()))
        android_tv.claimants = quiet + [(RECOVERY, 1), ("com.android.provision/.DefaultActivity", 3)]
        self.assertEqual([d.SETUP_WIZARD, "com.android.provision"], d.wizards_to_disable(android_tv, installed, set()))
        # Google TV keeps the old rule: Google's wizard turns launcherx back on, so it always goes.
        google_tv = d.Device({}, "google-tv", [LAUNCHER], True, LAUNCHER_HOME, [(LAUNCHER_HOME, 2)])
        self.assertEqual([d.SETUP_WIZARD], d.wizards_to_disable(google_tv, installed, set()))
        self.assertEqual([], d.wizards_to_disable(google_tv, installed, {d.SETUP_WIZARD}))

    def test_boot_needs_a_fallback_home_on_a_file_encrypted_box(self):
        claimants = [(VENDOR_HOME, 2), (d.ZAPPARR_HOME, 0)]
        box = d.Device({"ro.crypto.type": "file"}, "other", [VENDOR], False, VENDOR_HOME, claimants)
        self.assertTrue(d.boot_problem(box, [VENDOR]))
        box.claimants = claimants + [(FALLBACK, -1000)]
        self.assertEqual("", d.boot_problem(box, [VENDOR]))
        box.claimants = []
        self.assertTrue(d.boot_problem(box, [VENDOR]), "a box that won't list its Home apps can't be checked")
        box.props = {"ro.crypto.type": "block"}
        self.assertEqual("", d.boot_problem(box, [VENDOR]))


class ListsTest(Case):

    def chosen(self, props, installed, folder=None):
        device = d.Device(props, d.platform_of(installed), [], True)
        return [(found.path.name, why) for found, why in d.fitting_lists(device, folder or self.lists)]

    def test_the_generic_list_for_the_platform_comes_first_then_matching_vendor_lists(self):
        self.add_generic_lists()
        self.add_list("onn.txt", "# match: brand=onn*\n# match: device=YOC\ncom.example.onn\n")
        self.add_list("shield.txt", "# match: manufacturer=NVIDIA model=SHIELD*\ncom.nvidia.stats\n")
        self.add_list("google.txt", "# platform: google-tv\n# match: manufacturer=Google\ncom.example.cast\n")
        self.add_list("mine.txt", "com.example.mine\n")  # says no box: used only when named

        self.assertEqual([("google-tv.txt", "Google TV"), ("onn.txt", "brand=onn*")], self.chosen(ONN, {LAUNCHER}))
        self.assertEqual([("google-tv.txt", "Google TV"), ("onn.txt", "device=YOC")],
                         self.chosen({"ro.product.brand": "Walmart", "ro.product.device": "YOC"}, {LAUNCHER}))
        self.assertEqual([("android-tv.txt", "Android TV"), ("shield.txt", "manufacturer=NVIDIA model=SHIELD*")],
                         self.chosen(SHIELD, {TV_LAUNCHER}))
        # Case doesn't matter, and every key on a match line has to fit.
        lower = {"ro.product.manufacturer": "nvidia", "ro.product.model": "shield android tv"}
        self.assertEqual(["android-tv.txt", "shield.txt"], [n for n, _ in self.chosen(lower, {TV_LAUNCHER})])
        jetson = {"ro.product.manufacturer": "NVIDIA", "ro.product.model": "Jetson"}
        self.assertEqual(["android-tv.txt"], [n for n, _ in self.chosen(jetson, {TV_LAUNCHER})])
        # A vendor list with a platform line needs both.
        self.assertEqual(["google-tv.txt", "google.txt"], [n for n, _ in self.chosen(CHROMECAST, {LAUNCHER})])
        self.assertEqual(["android-tv.txt"], [n for n, _ in self.chosen(CHROMECAST, {TV_LAUNCHER})])
        # A box nobody knows still gets the conservative generic list, and nothing else.
        self.assertEqual([("other.txt", "unknown platform")], self.chosen(UNKNOWN, {VENDOR}))

    def test_layered_lists_keep_their_order_without_repeats(self):
        first = d.ListFile(Path("a.txt"), ["x.a", "x.b"])
        second = d.ListFile(Path("b.txt"), ["x.b", "x.c"])
        self.assertEqual(["x.a", "x.b", "x.c"], d.packages_in([first, second]))

    def test_directives_and_notes_are_not_packages(self):
        found = d.parse_list(self.write_list(
            "﻿# platform: google-tv android-tv\n# match: brand=onn*  device=YOC\n# note: casting stops\n"
            "# Note: just prose\n# com.example.kept   # commented out\ncom.example.a  # why\n"))
        self.assertEqual(["com.example.a"], found.packages)
        self.assertEqual(["google-tv", "android-tv"], found.platforms)
        self.assertEqual([{"brand": "onn*", "device": "YOC"}], found.matches)
        self.assertEqual(["casting stops"], found.notes)

    def test_a_bad_directive_is_refused(self):
        for text in ("# match: colour=red\n", "# match: brand\n", "# match: brand=\n", "# match:\n",
                     "# platform: fire-os\n"):
            with self.subTest(text=text), self.assertRaises(d.Refused):
                d.parse_list(self.write_list(text))

    def test_shipped_lists_say_which_boxes_they_are_for_and_never_name_what_the_box_needs(self):
        lists = d.read_lists(d.LISTS_DIR)
        self.assertLessEqual({"google-tv.txt", "android-tv.txt", "other.txt", "onn-4k-2023.txt"},
                             {found.path.name for found in lists})
        for found in lists:
            with self.subTest(list=found.path.name):
                self.assertTrue(found.platforms or found.matches)
                self.assertIn("https://", found.path.read_text(encoding="utf-8"), "a shipped list cites its sources")
                for pkg in found.packages:
                    self.assertFalse(d.is_protected(pkg), pkg)
                    self.assertNotIn(pkg, d.STOCK_LAUNCHERS + d.SETUP_WIZARDS)

    def test_shipped_lists_layer_for_known_and_unknown_boxes(self):
        def names(props, installed):
            return [name for name, _ in self.chosen(props, installed, d.LISTS_DIR)]

        self.assertEqual(["google-tv.txt", "onn-4k-2023.txt"], names(ONN, {LAUNCHER}))
        self.assertEqual(["google-tv.txt", "google-chromecast.txt"], names(CHROMECAST, {LAUNCHER}))
        self.assertEqual(["android-tv.txt", "nvidia-shield.txt"], names(SHIELD, {TV_LAUNCHER}))
        self.assertEqual(["google-tv.txt", "sony.txt"], names(SONY, {LAUNCHER}))
        self.assertEqual(["other.txt"], names(UNKNOWN, {VENDOR}))


class GuardTest(Case):

    def test_plan_keeps_what_opens_settings_plays_inputs_owns_wifi_or_is_home(self):
        wanted = ["com.vendor.settings", "com.vendor.tvinput", "com.vendor.fte", FEEDBACK, "com.vendor.home"]
        guarded = {"com.vendor.settings": "opens Settings", "com.vendor.tvinput": "plays the inputs"}
        todo, skipped = d.plan(wanted, set(wanted), set(), guarded=guarded,
                               wifi=Counter({"com.vendor.fte": 1, "android.uid.system": 2}), homes={"com.vendor.home"})
        self.assertEqual([FEEDBACK], todo)
        self.assertEqual(wanted[:3] + ["com.vendor.home"], [pkg for pkg, _ in skipped])

    def test_apply_asks_the_box_what_it_needs_whatever_the_name(self):
        tvinput, keyboard, fte = "com.vendor.tvinput", "com.vendor.keyboard", "com.vendor.fte"
        box = FakeBox([LAUNCHER, FEEDBACK, tvinput, keyboard, fte], inputs=[f"{tvinput}/.HdmiInputService"],
                      keyboards=[keyboard], wifi_creators=["android.uid.system:1000", f"{fte}:10061"])
        wanted = [FEEDBACK, tvinput, keyboard, fte]
        self.assertEqual(0, self.quietly(d.cmd_plan, d.Box(box.shell), self.state, wanted, apply=True,
                                         assume_yes=True)[0])
        self.assertEqual({FEEDBACK}, box.disabled)

    def test_apply_stops_when_the_box_wont_say_what_opens_settings(self):
        box = FakeBox([LAUNCHER, FEEDBACK], settings_app="com.vendor.missing")
        with self.assertRaises(d.Refused):
            self.quietly(d.cmd_plan, d.Box(box.shell), self.state, [FEEDBACK], apply=True, assume_yes=True)
        self.assertEqual(set(), box.disabled)

    def test_plan_on_its_own_uses_the_lists_that_fit_the_box(self):
        self.add_generic_lists()
        self.add_list("onn.txt", f"# match: brand=onn*\n{PRINT}\n{LAUNCHER}\n")
        box = FakeBox([LAUNCHER, d.SETUP_WIZARD, FEEDBACK, GAMES, PRINT])
        _, out = self.quietly(d.cmd_plan, d.Box(box.shell), self.state, None, apply=True, assume_yes=True,
                              lists_dir=self.lists)
        self.assertEqual({FEEDBACK, GAMES, PRINT}, box.disabled)
        self.assertIn("google-tv.txt (Google TV), onn.txt (brand=onn*)", out)

    def test_an_unknown_box_gets_the_conservative_list_and_a_warning(self):
        self.add_generic_lists()
        box = FakeBox([VENDOR, FEEDBACK, GAMES, PRINT], launcher_home=VENDOR_HOME, wizard_answers_home=False,
                      props=UNKNOWN)
        _, out = self.quietly(d.cmd_plan, d.Box(box.shell), self.state, None, apply=True, assume_yes=True,
                              lists_dir=self.lists)
        self.assertEqual({FEEDBACK, PRINT}, box.disabled)
        self.assertIn("other.txt (unknown platform)", out)
        self.assertIn("Unknown:", out)

    def test_a_named_list_for_another_box_gets_a_note(self):
        shield = d.parse_list(self.write_list(f"# match: manufacturer=NVIDIA\n{FEEDBACK}\n", "shield.txt"))
        box = FakeBox([LAUNCHER, FEEDBACK])
        _, out = self.quietly(d.cmd_plan, d.Box(box.shell), self.state, shield.packages, apply=False,
                              assume_yes=True, lists=[shield])
        self.assertIn("shield.txt is meant for manufacturer=NVIDIA, not this box", out)


class SetHomeTest(Case):

    def set_home(self, box, **kwargs):
        return self.quietly(d.cmd_set_home, d.Box(box.shell), self.state, True, False, settle=0,
                            lists_dir=self.lists, **kwargs)

    def restore(self, box):
        return self.quietly(d.cmd_restore, d.Box(box.shell), d.State(self.state.path), assume_yes=True)

    def test_android_tv_leaves_a_setup_wizard_that_does_not_answer_home(self):
        # As on a SHIELD: the wizard owns the saved Wi-Fi but never answers Home, so it stays.
        box = FakeBox([TV_LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR], launcher_home=TV_LAUNCHER_HOME,
                      wizard_answers_home=False, wifi_creators=[d.SETUP_WIZARD], props=SHIELD)
        self.assertEqual(0, self.set_home(box)[0])
        self.assertEqual({TV_LAUNCHER}, box.disabled)
        self.assertEqual(d.ZAPPARR_HOME, box.answers_home())

        self.assertEqual(0, self.restore(box)[0])
        self.assertEqual(set(), box.disabled)
        self.assertEqual(TV_LAUNCHER_HOME, box.answers_home())

    def test_android_tv_takes_a_setup_wizard_that_answers_home_but_not_its_wifi(self):
        box = FakeBox([TV_LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR], launcher_home=TV_LAUNCHER_HOME,
                      wifi_creators=[d.SETUP_WIZARD], props=SHIELD)
        with self.assertRaises(d.Refused):
            self.set_home(box)
        self.assertEqual(set(), box.disabled)

        box.wifi_creators = ["android.uid.system:1000"]
        self.assertEqual(0, self.set_home(box)[0])
        disables = [c.split()[-1] for c in box.log if c.startswith("pm disable-user")]
        self.assertEqual([d.SETUP_WIZARD, TV_LAUNCHER], disables)

    def test_a_launcher_found_through_the_home_intent_is_tried_without_disabling_it(self):
        box = FakeBox([VENDOR, d.ZAPPARR], launcher_home=VENDOR_HOME, launcher_priority=0,
                      wizard_answers_home=False, props=UNKNOWN)
        self.assertEqual(0, self.set_home(box)[0])
        self.assertEqual(set(), box.disabled)
        self.assertEqual(d.ZAPPARR_HOME, box.answers_home())

        self.assertEqual(0, self.restore(box)[0])
        self.assertEqual(VENDOR_HOME, box.answers_home())

    def test_a_found_launcher_that_outranks_zapparr_is_disabled_and_restored_first(self):
        box = FakeBox([VENDOR, FEEDBACK, d.ZAPPARR], launcher_home=VENDOR_HOME, wizard_answers_home=False,
                      props=UNKNOWN)
        self.quietly(d.cmd_plan, d.Box(box.shell), self.state, [FEEDBACK], apply=True, assume_yes=True)
        self.assertEqual(0, self.set_home(box)[0])
        self.assertEqual({VENDOR, FEEDBACK}, box.disabled)
        self.assertEqual([VENDOR], d.State(self.state.path).launchers)

        box.log.clear()
        self.assertEqual(0, self.restore(box)[0])
        self.assertEqual([VENDOR, FEEDBACK], [c.split()[-1] for c in box.log if c.startswith("pm enable")])
        self.assertEqual(VENDOR_HOME, box.answers_home())
        self.assertEqual([], d.State(self.state.path).launchers)

    def test_a_found_launcher_that_also_opens_settings_stays_enabled(self):
        box = FakeBox([VENDOR, d.ZAPPARR], launcher_home=VENDOR_HOME, wizard_answers_home=False,
                      props=UNKNOWN, settings_app=VENDOR)
        with self.assertRaises(d.Refused):
            self.set_home(box)
        self.assertEqual(set(), box.disabled)
        self.assertEqual(VENDOR_HOME, box.answers_home())
        self.assertIsNone(d.State(self.state.path).home_before)

    def test_no_launcher_is_disabled_where_the_box_could_not_boot_without_it(self):
        # A found launcher: the preference is tried, then handed back.
        box = FakeBox([VENDOR, d.ZAPPARR], launcher_home=VENDOR_HOME, wizard_answers_home=False,
                      fallback=False, props=UNKNOWN)
        with self.assertRaises(d.Refused):
            self.set_home(box)
        self.assertEqual(set(), box.disabled)
        self.assertEqual(VENDOR_HOME, box.home)
        self.assertIsNone(d.State(self.state.path).home_before)
        # A known one: nothing is touched at all.
        box = FakeBox([LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR], fallback=False)
        with self.assertRaises(d.Refused):
            self.set_home(box)
        self.assertEqual(set(), box.disabled)
        self.assertFalse(any(c.startswith(("cmd package set-home-activity", "am start")) for c in box.log))

    def test_fire_os_is_refused_before_anything_changes(self):
        box = FakeBox(["com.amazon.tv.launcher", d.ZAPPARR], launcher_home="com.amazon.tv.launcher/.ui.HomeActivity_vNext",
                      launcher_priority=950, wizard_answers_home=False, props=FIRE)
        with self.assertRaises(d.Refused) as stopped:
            self.set_home(box)
        self.assertIn("Fire OS", str(stopped.exception))
        self.assertFalse(any(c.startswith(("cmd package set-home-activity", "pm disable-user", "am start"))
                             for c in box.log))

    def test_set_home_shows_the_notes_of_the_lists_that_fit(self):
        self.add_list("google.txt", "# match: manufacturer=Google\n# note: Casting stops without the wizard.\n")
        box = FakeBox([LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR], props=CHROMECAST)
        _, out = self.set_home(box)
        self.assertIn("Note:     Casting stops without the wizard.", out)

    def test_an_old_record_without_launchers_still_restores(self):
        self.state.path.write_text(json.dumps({"disabled": [FEEDBACK], "home_before": None}), encoding="utf-8")
        box = FakeBox([LAUNCHER, FEEDBACK])
        box.disabled.add(FEEDBACK)
        self.assertEqual(0, self.restore(box)[0])
        self.assertEqual(set(), box.disabled)


class ProbeTest(Case):

    def probe(self, box, lists_dir=None):
        return self.quietly(d.cmd_probe, d.Box(box.shell), self.state, lists_dir=lists_dir or self.lists)

    def test_probe_names_the_platform_lists_and_setup_wizard_and_changes_nothing(self):
        box = FakeBox([LAUNCHER, d.SETUP_WIZARD, d.ZAPPARR])
        result, out = self.probe(box, d.LISTS_DIR)
        self.assertEqual(0, result)
        self.assertIn("Platform: Google TV", out)
        self.assertIn("google-tv.txt (Google TV), onn-4k-2023.txt (brand=onn*)", out)
        self.assertIn(f"Setup:    {d.SETUP_WIZARD}: set-home disables it with the launcher", out)
        self.assertIn("no saved network belongs to the setup wizard", out)
        self.assertFalse(any(c.startswith(("pm disable", "pm enable", "cmd package set-home", "am ", "input "))
                             for c in box.log))

    def test_probe_says_plainly_when_the_box_is_unknown(self):
        self.add_generic_lists()
        box = FakeBox([VENDOR, d.ZAPPARR], launcher_home=VENDOR_HOME, wizard_answers_home=False, props=UNKNOWN)
        _, out = self.probe(box)
        self.assertIn("Platform: unknown", out)
        self.assertIn(f"Stock:    {VENDOR} (enabled, found through the Home intent", out)
        self.assertIn("Lists:    other.txt (unknown platform)", out)
        self.assertIn("Unknown:", out)


if __name__ == "__main__":
    unittest.main()
