"""无网络地测试 GitHub draft 续发及不可覆盖约束。 / Tests draft resume and no-overwrite gates without network calls."""

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("github_prerelease", Path(__file__).with_name("publish-github-prerelease.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeGh:
    """仅在内存模拟公开制品，不调用 gh。 / Simulates public assets in memory without invoking gh."""
    def __init__(self, state=None, assets=None, denied=False):
        self.state = state
        self.assets = dict(assets or {})
        self.denied = denied
        self.calls = []

    def __call__(self, args):
        self.calls.append(args)
        if args[0] == "api":
            if self.denied:
                return subprocess.CompletedProcess(args, 1, "", "HTTP 403")
            if self.state is None:
                return subprocess.CompletedProcess(args, 1, "", "HTTP 404")
            return subprocess.CompletedProcess(args, 0, json.dumps({"draft": self.state, "prerelease": True,
                "tag_name": "v1.0.0-alpha01", "assets": [{"name": name} for name in self.assets]}), "")
        action = args[1]
        if action == "create":
            self.state = True
        elif action == "download":
            directory = Path(args[args.index("--dir") + 1])
            for name, data in self.assets.items():
                (directory / name).write_bytes(data)
        elif action == "upload":
            source = Path(args[3])
            if source.name in self.assets or "--clobber" in args:
                raise AssertionError("Existing assets must not be replaced")
            self.assets[source.name] = source.read_bytes()
        elif action == "edit":
            self.state = False
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(args, 0, "", "")


class GitHubReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="prism-gh-release-test-")
        self.root = Path(self.directory.name)
        for name in MODULE.FILES - {"SHA256SUMS"}:
            (self.root / name).write_bytes(name.encode())
        (self.root / "SHA256SUMS").write_text("".join(hashlib.sha256((self.root / name).read_bytes()).hexdigest()
            + "  " + name + "\n" for name in sorted(MODULE.FILES - {"SHA256SUMS"})))
        self.assets = {path.name: path.read_bytes() for path in self.root.iterdir()}

    def tearDown(self):
        self.directory.cleanup()

    def test_new_prerelease_is_draft_until_every_asset_uploaded(self):
        client = FakeGh()
        MODULE.publish(self.root, "1.0.0-alpha01", "a" * 40, client)
        self.assertFalse(client.state)
        self.assertEqual(client.assets, self.assets)
        self.assertIn("--draft", client.calls[1])
        self.assertEqual(client.calls[-1][1], "edit")

    def test_partial_draft_resumes_only_missing_assets(self):
        existing = dict(list(self.assets.items())[:2])
        client = FakeGh(True, existing)
        MODULE.publish(self.root, "1.0.0-alpha01", "a" * 40, client)
        uploaded = [Path(call[3]).name for call in client.calls if call[:2] == ["release", "upload"]]
        self.assertEqual(set(uploaded), MODULE.FILES - existing.keys())
        self.assertEqual(client.assets, self.assets)
        self.assertFalse(client.state)

    def test_identical_published_release_never_mutates(self):
        client = FakeGh(False, self.assets)
        MODULE.publish(self.root, "1.0.0-alpha01", "a" * 40, client)
        self.assertEqual([call[1] for call in client.calls if call[0] == "release"], ["download"])

    def test_changed_asset_or_incomplete_public_release_fails(self):
        changed = dict(self.assets)
        changed["LICENSE.txt"] = b"unexpected"
        for client in (FakeGh(True, changed), FakeGh(False, {"LICENSE.txt": self.assets["LICENSE.txt"]})):
            with self.assertRaises(ValueError):
                MODULE.publish(self.root, "1.0.0-alpha01", "a" * 40, client)
            self.assertFalse(any(call[1] in ("upload", "edit") for call in client.calls if call[0] == "release"))

    def test_ambiguous_api_failure_does_not_create(self):
        client = FakeGh(denied=True)
        with self.assertRaises(ValueError):
            MODULE.publish(self.root, "1.0.0-alpha01", "a" * 40, client)
        self.assertEqual(len(client.calls), 1)

    def test_invalid_distribution_prevents_all_remote_calls(self):
        (self.root / "LICENSE.txt").write_text("tampered")
        client = FakeGh()
        with self.assertRaises(ValueError):
            MODULE.publish(self.root, "1.0.0-alpha01", "a" * 40, client)
        self.assertFalse(client.calls)


if __name__ == "__main__":
    unittest.main()
