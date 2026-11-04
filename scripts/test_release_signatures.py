"""使用一次性测试密钥验证签名门禁。 / Tests signature gates using a disposable synthetic key."""

import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("release_signatures", Path(__file__).with_name("verify-release-signatures.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


@unittest.skipUnless(shutil.which("gpg") and shutil.which("gpgconf"), "GPG tools are required by release CI")
class ReleaseSignatureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="prism-test-key-")
        cls.home = Path(cls.directory.name)
        cls.home.chmod(0o700)
        cls.base = ["gpg", "--homedir", str(cls.home), "--batch", "--no-tty"]
        subprocess.run(cls.base + ["--pinentry-mode", "loopback", "--passphrase", "", "--quick-generate-key",
                                  "Prism Synthetic CI <ci@example.invalid>", "ed25519", "sign", "0"],
                       capture_output=True, check=True, timeout=60)
        cls.key = subprocess.check_output(cls.base + ["--armor", "--export-secret-keys"], stderr=subprocess.DEVNULL).decode()

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["gpgconf", "--homedir", str(cls.home), "--kill", "gpg-agent"], capture_output=True, timeout=15)
        cls.directory.cleanup()

    def test_real_signature_is_verified_and_tampering_fails(self):
        with tempfile.TemporaryDirectory(prefix="prism-signatures-test-") as directory:
            root = Path(directory)
            repository = root / "repo"
            repository.mkdir()
            artifact = repository / "example.pom"
            artifact.write_text("<project/>")
            subprocess.run(self.base + ["--armor", "--detach-sign", "--output", str(artifact) + ".asc", str(artifact)],
                           capture_output=True, check=True, timeout=60)
            with patch.dict(os.environ, {"ORG_GRADLE_PROJECT_signingInMemoryKey": self.key}):
                public = root / "public.asc"
                self.assertEqual(MODULE.verify([repository], public), 1)
                self.assertTrue(public.read_text().startswith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
                artifact.write_text("tampered")
                with self.assertRaises(ValueError):
                    MODULE.verify([repository], root / "second-public.asc")
                self.assertFalse((root / "second-public.asc").exists())

    def test_protected_rsa_key_is_converted_without_private_import(self):
        with tempfile.TemporaryDirectory(prefix="prism-rsa-key-test-") as directory:
            root = Path(directory)
            home = root / "generator"
            home.mkdir(mode=0o700)
            base = ["gpg", "--homedir", str(home), "--batch", "--no-tty", "--pinentry-mode", "loopback", "--passphrase-fd", "0"]
            password = b"synthetic-test-passphrase"
            try:
                subprocess.run(base + ["--quick-generate-key", "Prism RSA Test <rsa@example.invalid>", "rsa2048", "sign", "0"],
                               input=password, capture_output=True, check=True, timeout=60)
                key = subprocess.run(base + ["--armor", "--export-secret-keys"], input=password,
                                     capture_output=True, check=True, timeout=60).stdout.decode()
                repository = root / "repo"
                repository.mkdir()
                artifact = repository / "rsa.pom"
                artifact.write_text("<project/>")
                subprocess.run(base + ["--armor", "--detach-sign", "--output", str(artifact) + ".asc", str(artifact)],
                               input=password, capture_output=True, check=True, timeout=60)
                with patch.dict(os.environ, {"ORG_GRADLE_PROJECT_signingInMemoryKey": key}):
                    self.assertEqual(MODULE.verify([repository], root / "rsa-public.asc"), 1)
            finally:
                subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"], capture_output=True, timeout=15)

    def test_missing_key_is_rejected_without_output(self):
        with tempfile.TemporaryDirectory(prefix="prism-signatures-test-") as directory:
            root = Path(directory)
            with patch.dict(os.environ, {"ORG_GRADLE_PROJECT_signingInMemoryKey": ""}):
                with self.assertRaises(ValueError):
                    MODULE.verify([root], root / "public.asc")
            self.assertFalse((root / "public.asc").exists())


if __name__ == "__main__":
    unittest.main()
