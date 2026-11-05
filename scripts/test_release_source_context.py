"""本地验证发布工具与标签源码分离时的提交证明。 / Locally tests source commit proof when publishing tools and tagged source are separate checkouts."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("source_context", Path(__file__).with_name("verify-release-source.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SourceContextTest(unittest.TestCase):
    def test_source_commit_is_read_from_the_tag_checkout_not_the_tooling_checkout(self):
        with tempfile.TemporaryDirectory(prefix="prism-tag-source-") as directory:
            calls = []
            def runner(arguments, **kwargs):
                calls.append(kwargs)
                return subprocess.CompletedProcess(arguments, 0, "a" * 40 + "\n", "")
            environment = {"GITHUB_SHA": "a" * 40, "RELEASE_SOURCE_DIRECTORY": directory}
            self.assertEqual(MODULE.checkout_commit(environment, runner), "a" * 40)
            self.assertEqual(calls[0]["cwd"], Path(directory))
            with self.assertRaises(ValueError):
                MODULE.checkout_commit({**environment, "GITHUB_SHA": "b" * 40}, runner)

    def test_missing_or_symlinked_source_is_rejected_before_git_runs(self):
        with tempfile.TemporaryDirectory(prefix="prism-source-path-") as directory:
            root = Path(directory)
            (root / "source").mkdir()
            (root / "link").symlink_to(root / "source", target_is_directory=True)
            for path in (root / "missing", root / "link"):
                with self.subTest(path=path), self.assertRaises(ValueError):
                    MODULE.checkout_commit({"GITHUB_SHA": "a" * 40, "RELEASE_SOURCE_DIRECTORY": str(path)},
                                           lambda *args, **kwargs: self.fail("Git must not run"))


if __name__ == "__main__":
    unittest.main()
