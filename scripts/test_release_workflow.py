"""仅在本地检查 CI 的单一发布职责。 / Checks the workflow's publication-only responsibility locally."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseWorkflowTest(unittest.TestCase):
    def test_only_published_release_can_trigger_the_workflow(self):
        text = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        trigger = text.split("on:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(trigger.strip(), "release:\n    types: [published]")
        self.assertEqual(re.findall(r"^  ([a-z][a-z-]*):$", text.split("jobs:\n", 1)[1], re.MULTILINE), ["publish"])

    def test_workflow_uploads_only_and_does_not_create_github_releases(self):
        text = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertEqual(re.findall(r"^\s+run: (.+)$", text, re.MULTILINE),
                         ["python3 -B publisher/scripts/publish-release-to-central.py"])
        self.assertIn("contents: read", text)
        self.assertIn("ref: ${{ github.event.repository.default_branch }}", text)
        self.assertIn("ref: ${{ github.event.release.tag_name }}", text)
        self.assertIn("RELEASE_SOURCE_DIRECTORY: ${{ github.workspace }}/tag-source", text)
        for forbidden in ("contents: write", "GH_RELEASE_TOKEN", "GH_TOKEN:", "github.token", "gh release",
                          "gradlew", "setup-java", "upload-artifact", "continue-on-error", "|| true", "set +e"):
            self.assertNotIn(forbidden, text)
        self.assertEqual(set(re.findall(r"secrets\.([A-Z_]+)", text)),
                         {"MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD"})

    def test_no_extra_local_workflows_or_github_writer_entrypoints(self):
        workflows = ROOT / ".github/workflows"
        self.assertEqual({path.name for path in workflows.iterdir() if path.suffix in (".yml", ".yaml")}, {"release.yml"})
        for obsolete in ("publish-github-prerelease.py", "recreate-github-release.py", "release-identity.py"):
            self.assertFalse((ROOT / "scripts" / obsolete).exists())


if __name__ == "__main__":
    unittest.main()
