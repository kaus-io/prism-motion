"""用临时 SDK 模拟发布 Runner 的初始化边界。 / Tests runner setup boundaries using a disposable fake SDK."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("setup-release-android.sh").resolve()


@unittest.skipIf(os.name == "nt", "Runner setup is a Linux release step")
class AndroidSetupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="prism-sdk-setup-test-")
        self.root = Path(self.temporary.name)
        self.sdk = self.root / "sdk"
        manager = self.sdk / "cmdline-tools/latest/bin/sdkmanager"
        manager.parent.mkdir(parents=True)
        manager.write_text('''#!/usr/bin/env bash
set -e
if [[ "$1" == '--version' ]]; then
  if [[ "${FAIL_PHASE:-}" == version ]]; then echo 'synthetic version failure'; exit 6; fi
  echo test-sdkmanager
elif [[ "$1" == '--licenses' ]]; then
  read -r answer
  [[ "$answer" == y ]]
  if [[ "${FAIL_PHASE:-}" == licenses ]]; then echo 'synthetic license failure'; exit 7; fi
else
  if [[ "${FAIL_PHASE:-}" == install ]]; then echo 'synthetic install failure'; exit 8; fi
  [[ "$JAVA_HOME" == "$EXPECTED_JAVA" ]]
  [[ "$1" == 'platforms;android-37.0' ]]
fi
''')
        manager.chmod(0o700)
        self.java = self.root / "java17"
        (self.java / "bin").mkdir(parents=True)
        executable = self.java / "bin/java"
        executable.write_text("#!/bin/sh\nexit 0\n")
        executable.chmod(0o700)
        self.environment = os.environ.copy()
        self.environment.update(ANDROID_HOME=str(self.sdk), JAVA_HOME_17_X64=str(self.java), EXPECTED_JAVA=str(self.java), TMPDIR=str(self.root))
        self.environment.pop("GITHUB_ENV", None)

    def tearDown(self):
        self.temporary.cleanup()

    def run_setup(self, *args):
        return subprocess.run(["bash", str(SCRIPT), *args], env=self.environment,
                              capture_output=True, text=True, timeout=20)

    def test_full_setup_accepts_yes_pipe_and_uses_java17(self):
        self.assertEqual(self.run_setup().returncode, 0)

    def test_check_only_does_not_install(self):
        self.environment["FAIL_PHASE"] = "install"
        self.assertEqual(self.run_setup("--check-only").returncode, 0)

    def test_sdk_failures_have_public_annotations(self):
        for phase in ("version", "licenses", "install"):
            self.environment["FAIL_PHASE"] = phase
            result = self.run_setup()
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("::error::Android SDK setup failed:", result.stdout)

    def test_missing_sdk_fails_before_tools(self):
        self.environment["ANDROID_HOME"] = str(self.root / "missing")
        result = self.run_setup()
        self.assertEqual(result.returncode, 1)
        self.assertIn("::error::", result.stdout)


if __name__ == "__main__":
    unittest.main()
