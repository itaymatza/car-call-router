import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[2]
SCRIPT = ROOT / "tools" / "capture_device_run.sh"


FAKE_ADB = r'''#!/usr/bin/env python3
import os
import sys
from pathlib import Path

args = sys.argv[1:]
if args[:2] == ["-s", "FAKE"]:
    args = args[2:]

if args == ["get-state"]:
    print("device")
elif args[:3] == ["shell", "pm", "path"]:
    print("package:/data/app/base.apk")
elif args[:4] == ["shell", "cmd", "appops", "get"]:
    print("MANAGE_ONGOING_CALLS: allow")
elif args[:3] == ["exec-out", "run-as", "org.carcallrouter.companion"] and args[-1] == "id":
    print("uid=10000")
elif args[:4] == ["exec-out", "run-as", "org.carcallrouter.companion", "cat"]:
    if args[-1].endswith("router.previous.log"):
        sys.exit(1)
    state = Path(os.environ["FAKE_ADB_STATE"])
    count = int(state.read_text() or "0") if state.exists() else 0
    state.write_text(str(count + 1))
    def event(session, seq, elapsed, name, fields=""):
        suffix = " " + fields if fields else ""
        print(f"2026-09-19T12:00:00Z +1ms ROUTING_TRACE schema=1 session={session} seq={seq} elapsed_ms={elapsed} event={name}{suffix}")
    event("old", 1, 0, "SESSION_STARTED", "mode=manual trigger=route_now")
    event("old", 2, 1, "TELECOM_ENDPOINT_CONFIRMED")
    event("old", 3, 2, "TARGET_HFP_AUDIO_CONFIRMED")
    event("old", 4, 3, "SESSION_FINISHED", "phase=SUCCEEDED reason=TARGET_VERIFIED termination=call_removed best_confirmation=TARGET_HFP_AUDIO")
    if count > 0:
        event("new", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
        event("new", 2, 20, "REQUEST_SUBMITTED", "attempt=1")
        event("new", 3, 40, "TELECOM_ENDPOINT_CONFIRMED")
        event("new", 4, 60, "TARGET_HFP_AUDIO_CONFIRMED")
        event("new", 5, 70, "SESSION_FINISHED", "phase=SUCCEEDED reason=TARGET_VERIFIED termination=call_removed best_confirmation=TARGET_HFP_AUDIO")
elif args[:3] == ["shell", "getprop", "ro.product.manufacturer"]:
    print("Samsung")
elif args[:3] == ["shell", "getprop", "ro.product.model"]:
    print("TestModel")
elif args[:3] == ["shell", "getprop", "ro.build.version.sdk"]:
    print("37")
elif args[:3] == ["shell", "getprop", "ro.build.fingerprint"]:
    print("samsung/test/test:17/TEST/1:user/release-keys")
elif args[:3] == ["shell", "dumpsys", "package"]:
    print("versionCode=5 minSdk=34")
    print("versionName=0.3.0-beta.3")
else:
    print("unexpected fake adb arguments: " + repr(args), file=sys.stderr)
    sys.exit(2)
'''


class CaptureDeviceRunTest(unittest.TestCase):
    def test_pass_record_contains_only_new_session(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            adb = temporary / "adb"
            adb.write_text(textwrap.dedent(FAKE_ADB), encoding="utf-8")
            adb.chmod(0o755)
            output = temporary / "run"
            environment = os.environ.copy()
            environment["PATH"] = f"{temporary}:{environment['PATH']}"
            environment["FAKE_ADB_STATE"] = str(temporary / "state")
            completed = subprocess.run(
                ["bash", str(SCRIPT), "--serial", "FAKE", "--scenario", "outgoing",
                 "--output", str(output)],
                input="\n\ny\ny\ny\n",
                text=True,
                capture_output=True,
                env=environment,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            trace = (output / "trace.log").read_text(encoding="utf-8")
            self.assertIn("session=new", trace)
            self.assertNotIn("session=old", trace)
            self.assertEqual("verdict=PASS", (output / "verdict.txt").read_text().splitlines()[0])
            device = (output / "device.txt").read_text(encoding="utf-8")
            self.assertIn("build_fingerprint=samsung/test/test:17/TEST/1:user/release-keys", device)


if __name__ == "__main__":
    unittest.main()
