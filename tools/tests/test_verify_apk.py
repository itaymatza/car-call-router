import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[2]
SCRIPT = ROOT / "tools" / "verify-apk.sh"


AAPT = r'''#!/usr/bin/env bash
set -eu
case "$2" in
  badging)
    cat <<'EOF'
package: name='org.carcallrouter.companion' versionCode='5' versionName='0.3.0-beta.3'
sdkVersion:'34'
targetSdkVersion:'36'
application-debuggable
EOF
    ;;
  permissions)
    echo "uses-permission: name='android.permission.BLUETOOTH_CONNECT'"
    echo "uses-permission: name='android.permission.READ_PHONE_NUMBERS'"
    echo "uses-permission: name='android.permission.MANAGE_ONGOING_CALLS'"
    ;;
  xmltree)
    echo 'A: android:name="org.carcallrouter.companion.telecom.RouterInCallService"'
    echo 'A: android:permission="android.permission.BIND_INCALL_SERVICE"'
    echo 'A: android:name="android.telecom.InCallService"'
    ;;
  *) exit 2 ;;
esac
'''


APKSIGNER = r'''#!/usr/bin/env bash
set -eu
echo 'Verifies'
echo 'Signer #1 certificate SHA-256 digest: 001122'
'''


class VerifyApkTest(unittest.TestCase):
    def run_verifier(self, package="org.carcallrouter.companion"):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        apk = root / "app.apk"
        apk.write_bytes(b"synthetic-apk-boundary")
        for name, content in (("aapt", AAPT), ("apksigner", APKSIGNER)):
            tool = root / name
            tool.write_text(textwrap.dedent(content), encoding="utf-8")
            tool.chmod(0o755)
        report = root / "report.txt"
        environment = os.environ.copy()
        environment["ANDROID_BUILD_TOOLS"] = str(root)
        environment["REPORT_FILE"] = str(report)
        result = subprocess.run(
            ["bash", str(SCRIPT), str(apk), package, "5", "0.3.0-beta.3", "34", "36", "true"],
            text=True,
            capture_output=True,
            env=environment,
            check=False,
        )
        return result, report

    def test_verified_metadata_and_signature_emit_report(self):
        result, report = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stderr)
        content = report.read_text(encoding="utf-8")
        self.assertIn("verified=true", content)
        self.assertIn("certificate_sha256=001122", content)
        self.assertRegex(content, r"apk_sha256=[0-9a-f]{64}")

    def test_identity_mismatch_fails_closed(self):
        result, _ = self.run_verifier(package="example.wrong")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected example.wrong", result.stderr)


if __name__ == "__main__":
    unittest.main()
