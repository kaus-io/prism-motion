"""离线验证 Release published 是唯一发布入口。 / Verifies offline that Release published is the sole publication entry point."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("release_request", Path(__file__).with_name("release-request.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
VERSION = "1.0.0-alpha01"


class ReleaseRequestTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="prism-release-request-test-")
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "event.json"
        self.payload = {"action": "published", "release": {"tag_name": VERSION, "draft": False},
                        "repository": {"full_name": MODULE.REPOSITORY}}
        self.write()
        self.environment = {"GITHUB_EVENT_NAME": "release", "GITHUB_REPOSITORY": MODULE.REPOSITORY,
                            "GITHUB_REF": "refs/tags/" + VERSION, "GITHUB_REF_TYPE": "tag",
                            "GITHUB_EVENT_PATH": str(self.path)}

    def write(self):
        self.path.write_text(json.dumps(self.payload), encoding="utf-8")

    def test_published_release_and_prerelease_are_both_accepted_without_tokens(self):
        for prerelease in (False, True):
            self.payload["release"]["prerelease"] = prerelease
            self.write()
            self.assertTrue(MODULE.publication_requested(self.environment, VERSION))
        self.assertNotIn("GH_TOKEN", self.environment)
        self.assertNotIn("GITHUB_TOKEN", self.environment)

    def test_non_release_events_never_publish_even_with_legacy_manual_flags(self):
        for event in ("push", "workflow_dispatch", "pull_request", "pull_request_target", "", "schedule"):
            environment = {**self.environment, "GITHUB_EVENT_NAME": event,
                           "RELEASE_REQUESTED": "true", "RELEASE_CONFIRMATION": VERSION}
            self.assertFalse(MODULE.publication_requested(environment, VERSION))

    def test_other_release_actions_do_not_publish(self):
        for action in ("created", "edited", "released", "prereleased", "deleted", "unpublished"):
            self.payload["action"] = action
            self.write()
            self.assertFalse(MODULE.publication_requested(self.environment, VERSION))

    def test_wrong_environment_repository_tag_ref_type_or_head_ref_is_rejected(self):
        for key, value in (("GITHUB_REPOSITORY", "other/repository"), ("GITHUB_REF", "refs/heads/main"),
                           ("GITHUB_REF", "refs/tags/1.0.0-alpha02"), ("GITHUB_REF", ""),
                           ("GITHUB_REF_TYPE", "branch"), ("GITHUB_REF_TYPE", ""), ("GITHUB_HEAD_REF", "feature")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                MODULE.publication_requested({**self.environment, key: value}, VERSION)

    def test_v_prefixed_tag_is_not_the_manifest_version(self):
        environment = {**self.environment, "GITHUB_REF": "refs/tags/v" + VERSION}
        with self.assertRaises(ValueError):
            MODULE.publication_requested(environment, VERSION)
        self.payload["release"]["tag_name"] = "v" + VERSION
        self.write()
        with self.assertRaises(ValueError):
            MODULE.publication_requested(self.environment, VERSION)

    def test_missing_or_unreadable_event_path_is_rejected(self):
        for value in ("", str(self.path.parent / "missing.json"), str(self.path.parent)):
            with self.subTest(value=value), self.assertRaises(ValueError):
                MODULE.publication_requested({**self.environment, "GITHUB_EVENT_PATH": value}, VERSION)
        environment = dict(self.environment)
        environment.pop("GITHUB_EVENT_PATH")
        with self.assertRaises(ValueError):
            MODULE.publication_requested(environment, VERSION)

    def test_malformed_json_wrong_top_level_or_missing_action_is_rejected(self):
        for contents in ("not json", "null", "[]", "{}", '{"action": null}', '{"action": 1}'):
            self.path.write_text(contents)
            with self.subTest(contents=contents), self.assertRaises(ValueError):
                MODULE.publication_requested(self.environment, VERSION)

    def test_missing_mismatched_or_draft_release_is_rejected(self):
        for release in (None, {}, {"tag_name": VERSION}, {"tag_name": VERSION, "draft": True},
                        {"tag_name": VERSION, "draft": "false"}, {"tag_name": VERSION, "draft": 0},
                        {"tag_name": "1.0.0-alpha02", "draft": False}):
            self.payload["release"] = release
            self.write()
            with self.subTest(release=release), self.assertRaises(ValueError):
                MODULE.publication_requested(self.environment, VERSION)

    def test_optional_payload_repository_must_match_when_present(self):
        self.payload.pop("repository")
        self.write()
        self.assertTrue(MODULE.publication_requested(self.environment, VERSION))
        for repository in (None, {}, {"full_name": "other/repository"}, "kaus-io/prism-motion"):
            self.payload["repository"] = repository
            self.write()
            with self.subTest(repository=repository), self.assertRaises(ValueError):
                MODULE.publication_requested(self.environment, VERSION)

    def test_actor_and_legacy_owner_or_token_settings_do_not_gate_release_events(self):
        for actor in ("someone", "WY7XY", "github-actions[bot]"):
            environment = {**self.environment, "GITHUB_ACTOR": actor, "GITHUB_TRIGGERING_ACTOR": actor,
                           "RELEASE_REQUESTED": "false", "RELEASE_CONFIRMATION": "ignored"}
            self.assertTrue(MODULE.publication_requested(environment, VERSION))


if __name__ == "__main__":
    unittest.main()
