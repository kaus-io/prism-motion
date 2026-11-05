"""离线验证手动网页发布说明，禁止网络和子进程。 / Tests manual web-release metadata offline with networking and subprocesses forbidden."""

import ast
import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("github_release_metadata", Path(__file__).with_name("prepare-github-release.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
VERSION = "1.0.0-alpha01"


class ReleaseMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="prism-release-metadata-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / "project"
        self.project.mkdir()
        (self.project / "gradle.properties").write_text(f"version={VERSION}\n", encoding="utf-8")
        self.distribution = self.root / "dist"
        self.distribution.mkdir()
        for name in MODULE.FILES - {"SHA256SUMS"}:
            (self.distribution / name).write_bytes((name + " synthetic payload").encode())
        self.manifest = self.distribution / "SHA256SUMS"
        self.manifest.write_text("".join(hashlib.sha256((self.distribution / name).read_bytes()).hexdigest()
            + "  " + name + "\n" for name in sorted(MODULE.FILES - {"SHA256SUMS"})), encoding="ascii")
        self.output = self.root / "github-release-metadata"
        root_patch = mock.patch.object(MODULE, "ROOT", self.project)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.forbidden = []
        for target in ("urllib.request.urlopen", "socket.create_connection", "socket.socket",
                       "subprocess.run", "subprocess.Popen", "subprocess.call"):
            patch = mock.patch(target, side_effect=AssertionError("Metadata preparation must remain offline"))
            self.forbidden.append(patch.start())
            self.addCleanup(patch.stop)

    def tearDown(self):
        for operation in self.forbidden:
            operation.assert_not_called()

    def snapshot(self):
        return {entry.name: (entry.read_bytes(), entry.stat().st_mtime_ns)
                for entry in self.distribution.iterdir() if entry.is_file() and not entry.is_symlink()}

    def rejected(self):
        before = self.snapshot()
        with self.assertRaises((OSError, ValueError)):
            MODULE.prepare(self.distribution, self.output)
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(self.output.exists())

    def test_exact_version_title_and_only_two_new_metadata_files(self):
        before = self.snapshot()
        url = MODULE.prepare(self.distribution, self.output)
        self.assertEqual({path.name for path in self.output.iterdir()}, {"TITLE.txt", "RELEASE_NOTES.md"})
        self.assertEqual((self.output / "TITLE.txt").read_text(), VERSION + "\n")
        self.assertEqual((self.output / "RELEASE_NOTES.md").read_text(), MODULE.release_notes(VERSION))
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(url, f"https://github.com/kaus-io/prism-motion/releases/new?tag={VERSION}&title={VERSION}")

    def test_bilingual_notes_have_identical_supported_platforms_and_limits(self):
        notes = MODULE.release_notes(VERSION)
        self.assertLess(notes.index("## 中文"), notes.index("## English"))
        self.assertIn("Linux CLI 支持打包和检查", notes)
        self.assertIn("Linux CLI supports authoring and inspection", notes)
        self.assertIn("Windows CLI 目前仅支持检查，不支持打包", notes)
        self.assertIn("Windows CLI currently supports inspection only, not authoring", notes)
        self.assertIn("本版不提供 macOS CLI", notes)
        self.assertIn("does not include a macOS CLI", notes)
        self.assertEqual(notes.count("com.kaus-io"), 2)
        self.assertEqual(notes.count(VERSION), 4)

    def test_manifest_is_the_only_version_authority(self):
        version = "2.1.0-rc03"
        (self.project / "gradle.properties").write_text(f"# version=ignored\nversion = {version}\n")
        MODULE.prepare(self.distribution, self.output)
        self.assertEqual((self.output / "TITLE.txt").read_text(), version + "\n")
        self.assertIn(version, (self.output / "RELEASE_NOTES.md").read_text())

    def test_invalid_duplicate_or_missing_manifest_version_fails_before_output(self):
        for text in ("", "version=1.0.0alpha01\n", "version=1.0.0-alpha1\n", "version=1.0.0-alpha00\n",
                     "version=01.0.0\n", "version=1.0.0-SNAPSHOT\n", f"version={VERSION}\nversion={VERSION}\n"):
            (self.project / "gradle.properties").write_text(text)
            self.rejected()

    def test_valid_stable_alpha_beta_rc_versions(self):
        for version in ("1.0.0", "0.1.0-alpha01", "1.2.3-beta99", "2.0.0-rc03"):
            (self.project / "gradle.properties").write_text("version=" + version)
            self.assertEqual(MODULE.project_version(), version)

    def test_wrong_checksum_rejected_without_asset_changes(self):
        (self.distribution / "LICENSE.txt").write_bytes(b"changed before verification")
        self.rejected()

    def test_incomplete_manifest_rejected(self):
        self.manifest.write_text("\n".join(self.manifest.read_text().splitlines()[:-1]) + "\n")
        self.rejected()

    def test_duplicate_checksum_entry_rejected(self):
        self.manifest.write_text(self.manifest.read_text() + self.manifest.read_text().splitlines()[0] + "\n")
        self.rejected()

    def test_malformed_fingerprint_or_separator_rejected(self):
        original = self.manifest.read_text()
        first, rest = original.split("\n", 1)
        for line in (first.upper(), first.replace("  ", " ", 1), "0" * 63 + first[64:],
                     "not-a-digest  LICENSE.txt", "0" * 64 + "  ../outside"):
            self.manifest.write_text(line + "\n" + rest)
            self.rejected()

    def test_missing_asset_rejected(self):
        moved = self.root / "saved-license"
        (self.distribution / "LICENSE.txt").rename(moved)
        self.rejected()

    def test_missing_checksum_manifest_rejected(self):
        self.manifest.rename(self.root / "saved-checksums")
        self.rejected()

    def test_extra_asset_or_directory_rejected(self):
        (self.distribution / "unexpected.txt").write_text("not a release asset")
        self.rejected()

    def test_asset_directory_replacing_regular_file_rejected(self):
        original = self.distribution / "LICENSE.txt"
        original.rename(self.root / "original-license")
        original.mkdir()
        self.rejected()

    @unittest.skipIf(os.name == "nt", "Windows symlink creation needs elevated privileges")
    def test_asset_symlink_rejected(self):
        original = self.distribution / "LICENSE.txt"
        saved = self.root / "original-license"
        original.rename(saved)
        original.symlink_to(saved)
        self.rejected()

    @unittest.skipIf(os.name == "nt", "Windows symlink creation needs elevated privileges")
    def test_distribution_symlink_rejected(self):
        link = self.root / "linked-dist"
        link.symlink_to(self.distribution, target_is_directory=True)
        with self.assertRaises(ValueError):
            MODULE.prepare(link, self.output)
        self.assertFalse(self.output.exists())

    def test_existing_output_directory_is_never_overwritten(self):
        self.output.mkdir()
        sentinel = self.output / "TITLE.txt"
        sentinel.write_text("existing title")
        before = self.snapshot()
        with self.assertRaises(ValueError):
            MODULE.prepare(self.distribution, self.output)
        self.assertEqual(sentinel.read_text(), "existing title")
        self.assertEqual(self.snapshot(), before)
        self.assertEqual({path.name for path in self.output.iterdir()}, {"TITLE.txt"})

    @unittest.skipIf(os.name == "nt", "Windows symlink creation needs elevated privileges")
    def test_output_symlink_rejected(self):
        target = self.root / "existing-metadata"
        target.mkdir()
        self.output.symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            MODULE.prepare(self.distribution, self.output)
        self.assertEqual(list(target.iterdir()), [])

    def test_output_inside_distribution_is_rejected_without_new_input_entries(self):
        before = self.snapshot()
        with self.assertRaises(ValueError):
            MODULE.prepare(self.distribution, self.distribution / "metadata")
        self.assertEqual(self.snapshot(), before)
        self.assertEqual({entry.name for entry in self.distribution.iterdir()}, MODULE.FILES)

    def test_missing_output_parent_is_not_created(self):
        output = self.root / "missing-parent" / "metadata"
        with self.assertRaises(ValueError):
            MODULE.prepare(self.distribution, output)
        self.assertFalse(output.parent.exists())

    def test_cli_needs_no_token_and_prints_only_manual_link(self):
        output = io.StringIO()
        with mock.patch.dict(os.environ, {}, clear=True):
            with mock.patch.object(sys, "argv", ["prepare", "--distribution", str(self.distribution), "--output", str(self.output)]):
                with contextlib.redirect_stdout(output):
                    self.assertEqual(MODULE.main(), 0)
        self.assertEqual(output.getvalue().strip(),
                         f"https://github.com/kaus-io/prism-motion/releases/new?tag={VERSION}&title={VERSION}")

    def test_cli_cannot_override_version_or_accept_a_token(self):
        for option in ("--version", "--token"):
            with mock.patch.object(sys, "argv", ["prepare", "--distribution", str(self.distribution),
                                                 "--output", str(self.output), option, "not-accepted"]):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    MODULE.main()
            self.assertFalse(self.output.exists())

    def test_helper_imports_no_network_subprocess_authentication_or_writer_module(self):
        tree = ast.parse(Path(MODULE.__file__).read_text())
        names = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                names.add(node.module.split(".")[0])
        self.assertLessEqual(names, {"__future__", "argparse", "hashlib", "pathlib", "re", "sys"})


if __name__ == "__main__":
    unittest.main()
