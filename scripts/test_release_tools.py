"""发布工具的合成、离线测试。 / Synthetic, offline tests for release tools."""

from __future__ import annotations

import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import zipfile


def load(name: str, filename: str):
    """仅加载本测试负责的新工具。 / Loads only the new tools owned by these tests."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


signing = load("release_signing", "prepare-release-signing.py")
publisher = load("central_bundle", "publish-central-bundle.py")
VERSION = "1.0.0-alpha01"
DEPLOYMENT = "28570f16-da32-4c14-bd2e-c1acc0782365"
KEY = "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nc3ludGhldGljLW5vdC1hLXJlYWwta2V5\n-----END PGP PRIVATE KEY BLOCK-----\n"


def environment() -> dict[str, str]:
    """永远不复制宿主环境或读取真实密钥。 / Never copies the host environment or reads real secrets."""
    return {
        "PATH": os.defpath, "PYTHONDONTWRITEBYTECODE": "1",
        "GPG_KEY_CONTENTS": KEY, "SIGNING_KEY_ID": "0x0123456789abcdef",
        "SIGNING_PASSWORD": "synthetic-signing-password",
        "MAVEN_CENTRAL_USERNAME": "synthetic-central-user",
        "MAVEN_CENTRAL_PASSWORD": "synthetic-central-password",
        "GITHUB_REPOSITORY": "kaus-io/prism-motion",
        "GITHUB_REF": f"refs/tags/{VERSION}", "GITHUB_REF_TYPE": "tag",
        "GITHUB_EVENT_NAME": "release", "GITHUB_SHA": "a" * 40,
    }


def fixture(repository: Path, artifact: str = "prism-motion-core", version: str = VERSION) -> Path:
    """生成带合成签名的最小 GAV。 / Generates a minimal GAV with synthetic signatures."""
    directory = repository / "com" / "kaus-io" / artifact / version
    directory.mkdir(parents=True)
    stem = f"{artifact}-{version}"
    pom = (f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>com.kaus-io</groupId>'
           f"<artifactId>{artifact}</artifactId><version>{version}</version></project>").encode()
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("Example.kt", "// 合成测试。 / Synthetic fixture.\n")
    for suffix, contents in (("pom", pom), ("jar", archive.getvalue()), ("module", b"{}")):
        path = directory / f"{stem}.{suffix}"
        path.write_bytes(contents)
        Path(str(path) + ".asc").write_text("synthetic-signature", encoding="ascii")
        for algorithm in publisher.CHECKSUMS:
            Path(str(path) + "." + algorithm).write_text(hashlib.new(algorithm, contents).hexdigest(), encoding="ascii")
    return directory


class TemporaryTest(unittest.TestCase):
    """仅在标准临时目录中生成和清理测试产物。 / Creates and cleans fixtures only in an OS temporary directory."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="prism-release-tools-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.env = environment()
        self.event = self.root / "release-event.json"
        self.event.write_text(json.dumps({"action": "published", "release": {"tag_name": VERSION, "draft": False},
                                         "repository": {"full_name": "kaus-io/prism-motion"}}), encoding="utf-8")
        self.env["GITHUB_EVENT_PATH"] = str(self.event)

    def run_script(self, filename: str, *arguments: str):
        """子进程仅接收显式合成环境。 / Subprocesses receive only an explicit synthetic environment."""
        return subprocess.run([sys.executable, "-B", str(Path(__file__).with_name(filename)), *arguments],
                              env=self.env, capture_output=True, text=True, check=False)


class SigningTest(TemporaryTest):
    """覆盖密钥规范化、输出注入防护和错误脱敏。 / Covers key normalization, output-injection prevention and error redaction."""

    def test_ascii_crlf_and_base64_key(self):
        for value in (KEY, KEY.replace("\n", "\r\n"), base64.b64encode(KEY.encode()).decode()):
            self.assertEqual(signing.normalize_key(value), KEY)
        for value, expected in (("abcdef01", "ABCDEF01"), ("0x0123456789abcdef", "89ABCDEF"),
                                ("a" * 40, "A" * 8)):
            self.assertEqual(signing.normalize_key_id(value), expected)

    def test_invalid_keys_and_ids_are_generic(self):
        for value in ("SENSITIVE_BAD_KEY", KEY.replace("PRIVATE", "PUBLIC"), KEY + "SENSITIVE_TRAILER",
                      KEY.replace("c3ludGhldGljLW5vdC1hLXJlYWwta2V5", "SENSITIVE!"), KEY.replace("\n\n", "\n")):
            with self.assertRaises(signing.SigningError) as raised:
                signing.normalize_key(value)
            self.assertNotIn("SENSITIVE", str(raised.exception))
        for value in ("1234", "SENSITIVE_KEY_ID", "a" * 32):
            with self.assertRaises(signing.SigningError):
                signing.normalize_key_id(value)

    def test_runner_env_is_only_secret_file_and_uses_unique_delimiters(self):
        path = self.root / "runner-env"
        path.touch()
        self.env["GITHUB_ENV"] = str(path)
        self.env["SIGNING_PASSWORD"] = "synthetic\nINJECTED=bad\nPRISM_SECRET_fixed\r\n%"
        output = io.StringIO()
        signing.prepare(self.env, output)
        self.assertEqual(output.getvalue(), "")
        text = path.read_text()
        self.assertEqual(text.count("<<PRISM_SECRET_"), 5)
        self.assertEqual(len(set(line.split("<<")[1] for line in text.splitlines() if "<<PRISM_SECRET_" in line)), 5)
        self.assertIn("ORG_GRADLE_PROJECT_signingInMemoryKey<<", text)
        self.assertIn(KEY, text)
        self.assertIn("ORG_GRADLE_PROJECT_signingInMemoryKeyId<<", text)
        self.assertIn("89ABCDEF", text)
        self.assertIn("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword<<", text)
        self.assertIn("ORG_GRADLE_PROJECT_mavenCentralUsername<<", text)
        self.assertIn("ORG_GRADLE_PROJECT_mavenCentralPassword<<", text)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual({item.name for item in self.root.iterdir()}, {"runner-env", "release-event.json"})

    def test_mask_commands_escape_control_sequences(self):
        output = io.StringIO()
        signing.mask("synthetic%\r\n::error::not-a-command", output)
        self.assertEqual(output.getvalue(), "::add-mask::synthetic%25%0D%0A::error::not-a-command\n")

    def test_github_masks_both_original_and_normalized_key(self):
        path = self.root / "runner-env"
        path.touch()
        self.env.update(GITHUB_ENV=str(path), GITHUB_ACTIONS="true",
                        GPG_KEY_CONTENTS=base64.b64encode(KEY.encode()).decode())
        output = io.StringIO()
        signing.prepare(self.env, output)
        lines = output.getvalue().splitlines()
        self.assertTrue(all(line.startswith("::add-mask::") for line in lines))
        self.assertTrue(any("%0A" in line for line in lines))
        self.assertTrue(any(self.env["GPG_KEY_CONTENTS"] in line for line in lines))

    def test_presence_failure_and_subprocess_redaction(self):
        for name in signing.SECRET_NAMES:
            with self.subTest(name=name):
                altered = {key: value for key, value in self.env.items() if key != name}
                with self.assertRaises(signing.SigningError):
                    signing.prepare(altered, io.StringIO())
        self.env["GPG_KEY_CONTENTS"] = "DO_NOT_PRINT_THIS_SYNTHETIC_KEY"
        result = self.run_script("prepare-release-signing.py")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("DO_NOT_PRINT", result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_symlink_runner_file_rejected_without_writing(self):
        destination = self.root / "destination"
        destination.touch()
        link = self.root / "runner-env"
        link.symlink_to(destination)
        self.env["GITHUB_ENV"] = str(link)
        with self.assertRaises(signing.SigningError):
            signing.prepare(self.env, io.StringIO())
        self.assertEqual(destination.read_bytes(), b"")

    def test_successful_subprocess_has_no_unmasked_secret_output(self):
        path = self.root / "runner-env"
        path.touch()
        self.env["GITHUB_ENV"] = str(path)
        result = self.run_script("prepare-release-signing.py")
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in signing.SECRET_NAMES:
            self.assertNotIn(self.env[name], result.stdout + result.stderr)


class BundleTest(TemporaryTest):
    """验证暂存布局、原子合并和确定性 ZIP。 / Validates staging layout, atomic merging and deterministic ZIPs."""

    def setUp(self):
        super().setUp()
        self.repository = self.root / "staging"
        self.directory = fixture(self.repository)
        self.output = self.root / "bundle.zip"

    def create(self):
        """创建本测试的离线 ZIP。 / Creates this test's offline ZIP."""
        return publisher.bundle([self.repository], self.output, VERSION)

    def test_deterministic_zip_manifest_and_no_overwrite(self):
        manifest = self.create()
        second = self.root / "same.zip"
        for path in self.repository.rglob("*"):
            os.utime(path, (1600000000, 1600000000), follow_symlinks=False)
        publisher.bundle([self.repository], second, VERSION)
        self.assertEqual(self.output.read_bytes(), second.read_bytes())
        self.assertEqual(manifest.read_text(), hashlib.sha256(self.output.read_bytes()).hexdigest() + "  bundle.zip\n")
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), sorted(archive.namelist()))
            self.assertTrue(all(item.date_time == (1980, 1, 1, 0, 0, 0) for item in archive.infolist()))
        with self.assertRaises(publisher.ReleaseError):
            self.create()

    def test_all_59_fixed_coordinates_are_allowed_and_merge_separately(self):
        self.assertEqual(len(publisher.ALLOWED_ARTIFACTS), 59)
        other = self.root / "sdk"
        fixture(other, "kap-core")
        publisher.bundle([self.repository, other], self.output, VERSION)
        with zipfile.ZipFile(self.output) as archive:
            self.assertTrue(any("/kap-core/" in name for name in archive.namelist()))
        for artifact in publisher.ALLOWED_ARTIFACTS:
            publisher.artifact_path(f"com/kaus-io/{artifact}/{VERSION}/{artifact}-{VERSION}.pom", VERSION)

    def test_tooling_metadata_only_on_16_known_kmp_roots(self):
        self.assertEqual(len(publisher.TOOLING_METADATA_ROOTS), 16)
        for artifact in publisher.ALLOWED_ARTIFACTS:
            filename = f"{artifact}-{VERSION}-kotlin-tooling-metadata.json"
            for suffix in ("", ".asc", ".md5", ".sha1", ".sha256", ".sha512", ".asc.sha256"):
                name = f"com/kaus-io/{artifact}/{VERSION}/{filename}{suffix}"
                if artifact in publisher.TOOLING_METADATA_ROOTS:
                    self.assertEqual(publisher.artifact_path(name, VERSION), (artifact, filename + suffix, "json"))
                else:
                    with self.assertRaises(publisher.ReleaseError):
                        publisher.artifact_path(name, VERSION)
        for filename in (f"prism-motion-core-{VERSION}.json", f"prism-motion-core-{VERSION}-other.json",
                         f"prism-motion-core-{VERSION}-kotlin-tooling-metadata.json.asc.asc"):
            with self.assertRaises(publisher.ReleaseError):
                publisher.artifact_path(f"com/kaus-io/prism-motion-core/{VERSION}/{filename}", VERSION)

    def test_signed_tooling_metadata_roundtrip_and_missing_signature(self):
        path = self.directory / f"prism-motion-core-{VERSION}-kotlin-tooling-metadata.json"
        path.write_text('{"schemaVersion":"1.0"}', encoding="ascii")
        with self.assertRaisesRegex(publisher.ReleaseError, "signature"):
            self.create()
        Path(str(path) + ".asc").write_text("synthetic-signature", encoding="ascii")
        for algorithm in publisher.CHECKSUMS:
            Path(str(path) + "." + algorithm).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())
        manifest = self.create()
        with self.output.open("rb") as stream:
            self.assertEqual(len(publisher.verify_bundle(stream, self.output.name, manifest, VERSION)), 1)
        with zipfile.ZipFile(self.output) as archive:
            self.assertIn(path.relative_to(self.repository).as_posix(), archive.namelist())

    def test_tooling_metadata_requires_json_and_no_private_material(self):
        name = f"com/kaus-io/prism-motion-core/{VERSION}/prism-motion-core-{VERSION}-kotlin-tooling-metadata.json"
        for contents in (b"invalid JSON", b"[]", KEY.encode()):
            entries = {name: contents, name + ".asc": b"synthetic-signature"}
            with self.assertRaises(publisher.ReleaseError):
                publisher.validate_entries(entries, entries.__getitem__, VERSION)

    def test_duplicate_gav_rejected(self):
        other = self.root / "other"
        fixture(other)
        with self.assertRaisesRegex(publisher.ReleaseError, "Duplicate GAV"):
            publisher.bundle([self.repository, other], self.output, VERSION)

    def test_bad_layout_cli_foreign_namespace_version_and_extension(self):
        for name in ("outside.jar", f"org/kaus-io/prism-motion-core/{VERSION}/x.jar",
                     f"com/kaus-io/prism-motion-cli/{VERSION}/prism-motion-cli-{VERSION}.jar",
                     f"com/kaus-io/kap-arbitrary/{VERSION}/kap-arbitrary-{VERSION}.pom",
                     "com/kaus-io/prism-motion-core/1.0.0-alpha02/prism-motion-core-1.0.0-alpha02.jar",
                     f"com/kaus-io/prism-motion-core/{VERSION}/prism-motion-core-{VERSION}.key",
                     f"com/kaus-io/prism-motion-core/{VERSION}/prism-motion-core-{VERSION}-linux.tar.gz",
                     f"com/kaus-io/../{VERSION}/x.jar"):
            with self.subTest(name=name), self.assertRaises(publisher.ReleaseError):
                publisher.artifact_path(name, VERSION)

    def test_foreign_empty_directory_and_symlink_rejected(self):
        foreign = self.repository / "foreign"
        foreign.mkdir()
        with self.assertRaisesRegex(publisher.ReleaseError, "directory"):
            self.create()
        foreign.rmdir()
        target = self.root / "outside"
        target.write_text("synthetic")
        link = self.directory / "escape.jar"
        link.symlink_to(target)
        with self.assertRaisesRegex(publisher.ReleaseError, "Symlinks"):
            self.create()

    def test_missing_signature_or_pom_is_rejected(self):
        signature = self.directory / f"prism-motion-core-{VERSION}.jar.asc"
        signature.unlink()
        with self.assertRaisesRegex(publisher.ReleaseError, "signature"):
            self.create()
        signature.write_text("synthetic")
        pom = self.directory / f"prism-motion-core-{VERSION}.pom"
        pom.unlink()
        with self.assertRaises(publisher.ReleaseError):
            self.create()

    def test_checksum_and_pom_coordinate_mismatch(self):
        checksum = self.directory / f"prism-motion-core-{VERSION}.jar.sha256"
        checksum.write_text("0" * 64)
        with self.assertRaisesRegex(publisher.ReleaseError, "checksum"):
            self.create()
        pom_name = f"com/kaus-io/prism-motion-core/{VERSION}/prism-motion-core-{VERSION}.pom"
        entries = {pom_name: b"<project><groupId>foreign</groupId></project>", pom_name + ".asc": b"synthetic"}
        with self.assertRaisesRegex(publisher.ReleaseError, "POM coordinates"):
            publisher.validate_entries(entries, entries.__getitem__, VERSION)

    def test_private_key_plain_and_nested_rejected(self):
        for contents in (KEY.encode(), b"-----BEGIN RSA PRIVATE KEY-----\nsynthetic"):
            with self.assertRaisesRegex(publisher.ReleaseError, "Private key"):
                publisher.reject_private_material(contents)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("hidden.txt", KEY)
        with self.assertRaisesRegex(publisher.ReleaseError, "Private key"):
            publisher.reject_private_material(archive.getvalue())

    def test_metadata_excluded_and_bundle_outside_staging_required(self):
        (self.repository / "maven-metadata.xml").write_text("excluded")
        (self.directory.parent / "maven-metadata.xml").write_text("excluded")
        self.create()
        with zipfile.ZipFile(self.output) as archive:
            self.assertFalse(any("maven-metadata" in name for name in archive.namelist()))
        with self.assertRaises(publisher.ReleaseError):
            publisher.bundle([self.repository], self.repository / "bad.zip", VERSION)

    def test_manifest_accepts_zero_or_one_newline_and_rejects_other_whitespace(self):
        manifest = self.create()
        line = f"{publisher.file_digest(self.output)}  {self.output.name}".encode("ascii")
        for value in (line, line + b"\n"):
            manifest.write_bytes(value)
            with self.output.open("rb") as stream:
                self.assertTrue(publisher.verify_bundle(stream, self.output.name, manifest, VERSION))
        for value in (line + b"\n\n", line + b"\r\n", b" " + line, line + b" ", line.replace(b"  ", b" ", 1)):
            manifest.write_bytes(value)
            with self.output.open("rb") as stream, self.assertRaises(publisher.ReleaseError):
                publisher.verify_bundle(stream, self.output.name, manifest, VERSION)

    def test_manifest_tampering_and_zip_symlink_rejected(self):
        manifest = self.create()
        with self.output.open("ab") as stream:
            stream.write(b"tampering")
        with self.output.open("rb") as stream, self.assertRaisesRegex(publisher.ReleaseError, "checksum"):
            publisher.verify_bundle(stream, self.output.name, manifest, VERSION)
        bad = self.root / "bad.zip"
        info = zipfile.ZipInfo(f"com/kaus-io/prism-motion-core/{VERSION}/prism-motion-core-{VERSION}.pom")
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(bad, "w") as archive:
            archive.writestr(info, "outside")
        bad_manifest = Path(str(bad) + ".sha256")
        bad_manifest.write_text(f"{publisher.file_digest(bad)}  {bad.name}\n")
        with bad.open("rb") as stream, self.assertRaisesRegex(publisher.ReleaseError, "symlinks"):
            publisher.verify_bundle(stream, bad.name, bad_manifest, VERSION)

    def test_subprocess_offline_bundle_and_guard_failure(self):
        result = self.run_script("publish-central-bundle.py", "bundle", "--repository", str(self.repository), "--output", str(self.output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.env["GITHUB_REF"] = "refs/heads/main"
        result = self.run_script("publish-central-bundle.py", "publish", "--bundle", str(self.output))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release tag must equal the manifest version exactly", result.stderr)
        self.assertNotIn(self.env["MAVEN_CENTRAL_PASSWORD"], result.stderr)


class FakePortal:
    """无网络的 Portal 响应队列。 / A network-free queue of Portal responses."""

    def __init__(self, states=("PUBLISHED",), preflight=404, upload=201, existing_code=200, existing_pom=None):
        self.states = iter(states)
        self.preflight = preflight
        self.upload = upload
        self.existing_code = existing_code
        self.existing_pom = existing_pom
        self.calls = []
        self.payload = b""

    def request(self, method, url, data=None, **kwargs):
        """记录合成 HTTP 请求，但绝不调用网络。 / Records synthetic HTTP requests without using a network."""
        self.calls.append((method, url, kwargs))
        if method == "HEAD":
            return self.preflight, b""
        if method == "GET":
            artifact, version = url.split("/")[-3:-1]
            pom = self.existing_pom
            if pom is None:
                pom = (f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>com.kaus-io</groupId>'
                       f'<artifactId>{artifact}</artifactId><version>{version}</version></project>').encode()
            return self.existing_code, pom
        if "/upload?" in url:
            self.payload = b"".join(data)
            self.headers = kwargs["headers"]
            return self.upload, DEPLOYMENT.encode()
        state = next(self.states)
        if isinstance(state, int):
            return state, b"SECRET_RESPONSE_BODY"
        return 200, json.dumps({"deploymentId": DEPLOYMENT, "deploymentState": state,
                                "errors": ["SECRET_RESPONSE_BODY"]}).encode()


class Clock:
    """不睡眠的可控时钟。 / A controlled clock that never sleeps."""

    def __init__(self):
        self.now = 0

    def clock(self):
        """返回合成时间。 / Returns synthetic time."""
        return self.now

    def sleep(self, seconds):
        """直接推进时间，不等待。 / Advances time directly without waiting."""
        self.now += seconds


class SourceProofTest(unittest.TestCase):
    """只模拟 Git 输出，绝不访问实际仓库或网络。 / Mocks Git output without accessing a real repository or network."""

    def test_lightweight_remote_tag(self):
        runner = mock.Mock(return_value=subprocess.CompletedProcess([], 0, "a" * 40 + f"\trefs/tags/{VERSION}\n", ""))
        self.assertEqual(publisher.SOURCE.remote_tag_commit(VERSION, runner), "a" * 40)
        arguments = runner.call_args.args[0]
        self.assertIn("https://github.com/kaus-io/prism-motion.git", arguments)
        self.assertEqual(arguments[-2:], [f"refs/tags/{VERSION}", f"refs/tags/{VERSION}^{{}}"])
        self.assertEqual(runner.call_args.kwargs["env"]["GIT_CONFIG_GLOBAL"], os.devnull)
        self.assertEqual(runner.call_args.kwargs["env"]["GIT_TERMINAL_PROMPT"], "0")
        self.assertNotIn("GH_TOKEN", runner.call_args.kwargs["env"])

    def test_annotated_and_nested_tag_use_server_peeled_commit(self):
        for tag_object in ("b" * 40, "c" * 40):
            output = tag_object + f"\trefs/tags/{VERSION}\n" + "a" * 40 + f"\trefs/tags/{VERSION}^{{}}\n"
            runner = mock.Mock(return_value=subprocess.CompletedProcess([], 0, output, ""))
            self.assertEqual(publisher.SOURCE.remote_tag_commit(VERSION, runner), "a" * 40)

    def test_missing_malformed_duplicate_or_foreign_remote_refs_fail_closed(self):
        reference = f"refs/tags/{VERSION}"
        for output in ("", "a" * 40 + f"\t{reference}^{{}}\n", "invalid\t" + reference,
                       "a" * 40 + "\trefs/heads/main\n", ("a" * 40 + "\t" + reference + "\n") * 2):
            runner = mock.Mock(return_value=subprocess.CompletedProcess([], 0, output, ""))
            with self.assertRaises(ValueError):
                publisher.SOURCE.remote_tag_commit(VERSION, runner)

    def test_unknown_network_failure_is_sanitized(self):
        for result in (subprocess.CompletedProcess([], 2, "", "SECRET_REMOTE_DETAIL"),
                       subprocess.TimeoutExpired("SECRET_COMMAND", 45)):
            runner = mock.Mock()
            if isinstance(result, Exception):
                runner.side_effect = result
            else:
                runner.return_value = result
            with self.assertRaises(ValueError) as error:
                publisher.SOURCE.remote_tag_commit(VERSION, runner)
            self.assertNotIn("SECRET", str(error.exception))

    def test_checkout_requires_canonical_event_sha(self):
        runner = mock.Mock(return_value=subprocess.CompletedProcess([], 0, "a" * 40 + "\n", ""))
        self.assertEqual(publisher.SOURCE.checkout_commit({"GITHUB_SHA": "a" * 40}, runner), "a" * 40)
        for environment in ({}, {"GITHUB_SHA": "b" * 40}):
            with self.assertRaises(ValueError):
                publisher.SOURCE.checkout_commit(environment, runner)


class PublishTest(TemporaryTest):
    """验证生产保护、单次上传、状态轮询和脱敏。 / Verifies production guards, single upload, polling and redaction."""

    def setUp(self):
        super().setUp()
        repository = self.root / "staging"
        fixture(repository)
        self.bundle = self.root / "bundle.zip"
        self.manifest = publisher.bundle([repository], self.bundle, VERSION)
        self.output = self.root / "runner-output"
        self.output.touch()
        self.env["GITHUB_OUTPUT"] = str(self.output)
        self.identifier = self.root / "deployment-id"

    def invoke(self, client, **kwargs):
        """用合成 Portal 客户端执行受保护上传。 / Executes guarded publication using a synthetic Portal client."""
        kwargs.setdefault("tag_resolver", lambda version: "a" * 40)
        kwargs.setdefault("commit_resolver", lambda environment: "a" * 40)
        with mock.patch.object(publisher, "PortalClient", return_value=client), contextlib.redirect_stdout(io.StringIO()):
            return publisher.publish(self.bundle, self.manifest, VERSION, self.env, self.identifier, **kwargs)

    def test_version_uses_release_tag_manifest_not_newer_tooling_checkout(self):
        tooling = self.root / "tooling"
        (tooling / "scripts").mkdir(parents=True)
        (tooling / "gradle.properties").write_text("version=1.0.0-alpha02\n")
        source = self.root / "tag-source"
        source.mkdir()
        (source / "gradle.properties").write_text("version=" + VERSION + "\n")
        with mock.patch.object(publisher, "__file__", str(tooling / "scripts/publish-central-bundle.py")):
            with mock.patch.dict(os.environ, {"RELEASE_SOURCE_DIRECTORY": str(source)}, clear=True):
                self.assertEqual(publisher.project_version(), VERSION)
                self.assertEqual(publisher.release_version(VERSION), VERSION)
                with self.assertRaises(publisher.ReleaseError):
                    publisher.release_version("1.0.0-alpha02")
            with mock.patch.dict(os.environ, {}, clear=True):
                self.assertEqual(publisher.project_version(), "1.0.0-alpha02")
            with mock.patch.dict(os.environ, {"RELEASE_SOURCE_DIRECTORY": str(tooling)}, clear=True):
                self.assertEqual(publisher.project_version(source_directory=source), VERSION)
                self.assertEqual(publisher.release_version(None, source_directory=str(source)), VERSION)
        self.assertEqual((source / "gradle.properties").read_text(), "version=" + VERSION + "\n")
        self.assertEqual((tooling / "gradle.properties").read_text(), "version=1.0.0-alpha02\n")

    def test_release_source_directory_and_manifest_symlinks_are_rejected(self):
        source = self.root / "tag-source"
        source.mkdir()
        manifest = source / "gradle.properties"
        manifest.write_text("version=" + VERSION + "\n")
        link = self.root / "source-link"
        link.symlink_to(source, target_is_directory=True)
        for directory in (link, self.root / "missing-source", manifest, ""):
            with mock.patch.dict(os.environ, {"RELEASE_SOURCE_DIRECTORY": str(directory)}, clear=True):
                with self.assertRaises(publisher.ReleaseError):
                    publisher.project_version()
        linked = self.root / "linked-manifest-source"
        linked.mkdir()
        (linked / "gradle.properties").symlink_to(manifest)
        with mock.patch.dict(os.environ, {"RELEASE_SOURCE_DIRECTORY": str(linked)}, clear=True):
            with self.assertRaises(publisher.ReleaseError):
                publisher.project_version()

    def test_production_guards_and_root_version(self):
        self.assertEqual(publisher.release_version(VERSION), VERSION)
        with self.assertRaises(publisher.ReleaseError):
            publisher.release_version("1.0.0-alpha02")
        for field, value in (("GITHUB_REPOSITORY", "attacker/prism-motion"), ("GITHUB_REF", "refs/heads/main"),
                             ("GITHUB_REF", "refs/tags/1.0.0-alpha02"), ("GITHUB_EVENT_NAME", "pull_request"),
                             ("GITHUB_EVENT_NAME", "pull_request_target"), ("GITHUB_HEAD_REF", "feature")):
            with self.subTest(field=field, value=value), self.assertRaises(publisher.ReleaseError):
                publisher.production_guard({**self.env, field: value}, VERSION)

    def test_release_payload_guard_rejects_other_action_missing_tag_draft_or_cross_repository(self):
        payloads = [
            {"action": "edited", "release": {"tag_name": VERSION, "draft": False}},
            {"action": "published", "release": {"draft": False}},
            {"action": "published", "release": {"tag_name": VERSION, "draft": True}},
            {"action": "published", "release": {"tag_name": VERSION, "draft": False},
             "repository": {"full_name": "other/repository"}},
        ]
        for payload in payloads:
            self.event.write_text(json.dumps(payload))
            with self.subTest(payload=payload), self.assertRaises(publisher.ReleaseError):
                publisher.production_guard(self.env, VERSION)
        self.env.pop("GITHUB_EVENT_PATH")
        with self.assertRaises(publisher.ReleaseError):
            publisher.production_guard(self.env, VERSION)

    def test_remote_tag_mismatch_prevents_all_central_calls(self):
        client = FakePortal()
        with self.assertRaises(ValueError):
            self.invoke(client, tag_resolver=lambda version: "b" * 40)
        self.assertEqual(client.calls, [])
        self.assertFalse(self.identifier.exists())

    def test_remote_tag_moving_during_preflight_blocks_upload(self):
        commits = iter(("a" * 40, "b" * 40))
        client = FakePortal()
        with self.assertRaises(ValueError):
            self.invoke(client, tag_resolver=lambda version: next(commits))
        self.assertTrue(client.calls)
        self.assertTrue(all(method == "HEAD" for method, _, _ in client.calls))

    def test_resume_cannot_claim_published_after_remote_tag_moves(self):
        commits = iter(("a" * 40, "b" * 40))
        client = FakePortal()
        with self.assertRaises(ValueError):
            self.invoke(client, deployment_id=DEPLOYMENT, tag_resolver=lambda version: next(commits))
        self.assertEqual(len(client.calls), 1)
        self.assertIn("/status?", client.calls[0][1])
        self.assertNotIn("deployment_state", self.output.read_text())

    def test_single_upload_exact_bundle_and_published_outputs(self):
        client = FakePortal()
        self.assertEqual(self.invoke(client), DEPLOYMENT)
        self.assertEqual(sum("/upload?" in url for _, url, _ in client.calls), 1)
        self.assertTrue(any(url.endswith("publishingType=AUTOMATIC") for _, url, _ in client.calls))
        self.assertIn(b'name="bundle"', client.payload)
        self.assertIn(self.bundle.read_bytes(), client.payload)
        self.assertEqual(int(client.headers["Content-Length"]), len(client.payload))
        self.assertEqual(self.identifier.read_text(), DEPLOYMENT + "\n")
        self.assertEqual(self.output.read_text(), f"deployment_id={DEPLOYMENT}\ndeployment_state=PUBLISHED\n")

    def test_failed_status_keeps_id_but_no_published_output(self):
        client = FakePortal(states=("FAILED",))
        with self.assertRaises(publisher.ReleaseError) as error:
            self.invoke(client)
        self.assertNotIn("SECRET_RESPONSE_BODY", str(error.exception))
        self.assertEqual(self.identifier.read_text(), DEPLOYMENT + "\n")
        self.assertNotIn("deployment_state", self.output.read_text())

    def test_existing_exact_version_succeeds_without_upload_poll_or_uuid_write(self):
        client = FakePortal(preflight=200)
        self.assertEqual(self.invoke(client), "ALREADY_PUBLISHED")
        self.assertEqual([method for method, _, _ in client.calls], ["HEAD", "GET"])
        self.assertEqual(client.calls[0][1], client.calls[1][1])
        self.assertTrue(client.calls[1][1].startswith(publisher.PUBLIC))
        self.assertFalse(self.identifier.exists())
        self.assertEqual(self.output.read_text(), "deployment_state=PUBLISHED\npublication_status=ALREADY_PUBLISHED\n")

    def test_preflight_reports_only_specific_confirmed_existing_version(self):
        pom = f"com/kaus-io/prism-motion-core/{VERSION}/prism-motion-core-{VERSION}.pom"
        with self.assertRaises(publisher.AlreadyPublished):
            publisher.preflight(FakePortal(preflight=200), [pom])
        for client in (FakePortal(preflight=401), FakePortal(preflight=403), FakePortal(preflight=500),
                       FakePortal(preflight=200, existing_pom=b"<html>not a POM</html>")):
            with self.assertRaises(publisher.ReleaseError) as error:
                publisher.preflight(client, [pom])
            self.assertNotIsInstance(error.exception, publisher.AlreadyPublished)

    def test_auth_server_or_invalid_existing_pom_errors_are_not_success(self):
        clients = [FakePortal(preflight=code) for code in (401, 403, 500)]
        clients += [FakePortal(preflight=200, existing_code=code) for code in (401, 403, 404, 500)]
        clients += [FakePortal(preflight=200, existing_pom=body) for body in
                    (b"", b"<html>not a POM</html>", b"<project><groupId>wrong</groupId></project>",
                     b'<!DOCTYPE project [<!ENTITY leak "forbidden">]><project/>')]
        for client in clients:
            with self.subTest(client=client), self.assertRaises(publisher.ReleaseError):
                self.invoke(client)
            self.assertTrue(all(method in ("HEAD", "GET") for method, _, _ in client.calls))
            self.assertEqual(self.output.read_text(), "")
            self.assertFalse(self.identifier.exists())

    def test_network_failure_is_not_already_published(self):
        client = FakePortal()
        with mock.patch.object(client, "request", side_effect=publisher.ReleaseError("Synthetic network failure")):
            with self.assertRaises(publisher.ReleaseError) as error:
                self.invoke(client)
        self.assertNotIsInstance(error.exception, publisher.AlreadyPublished)
        self.assertEqual(self.output.read_text(), "")
        self.assertFalse(self.identifier.exists())

    def test_existing_version_still_requires_unchanged_source_before_success(self):
        commits = iter(("a" * 40, "b" * 40))
        client = FakePortal(preflight=200)
        with self.assertRaises(ValueError):
            self.invoke(client, tag_resolver=lambda version: next(commits))
        self.assertEqual([method for method, _, _ in client.calls], ["HEAD", "GET"])
        self.assertEqual(self.output.read_text(), "")
        self.assertFalse(self.identifier.exists())

    def test_ambiguous_upload_is_never_retried(self):
        client = FakePortal(upload=503)
        with self.assertRaisesRegex(publisher.ReleaseError, "never blindly retry"):
            self.invoke(client)
        self.assertEqual(sum("/upload?" in url for _, url, _ in client.calls), 1)

    def test_resume_is_status_only_no_preflight_or_upload(self):
        client = FakePortal()
        self.assertEqual(self.invoke(client, deployment_id=DEPLOYMENT), DEPLOYMENT)
        self.assertEqual(len(client.calls), 1)
        self.assertIn("/status?id=", client.calls[0][1])
        self.assertEqual(self.identifier.read_text(), DEPLOYMENT + "\n")

    def test_transient_status_retry_without_sleeping(self):
        client = FakePortal(states=(503, "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED"))
        clock = Clock()
        with contextlib.redirect_stdout(io.StringIO()):
            publisher.poll(client, DEPLOYMENT, 10, 60, sleep=clock.sleep, clock=clock.clock)
        self.assertEqual(clock.now, 40)
        self.assertEqual(len(client.calls), 5)
        self.assertTrue(all("/status?" in url for _, url, _ in client.calls))

    def test_timeout_and_repeated_status_errors_are_bounded(self):
        for states in (("VALIDATED",) * 4, (503,) * 4):
            client = FakePortal(states=states)
            clock = Clock()
            with self.assertRaises(publisher.ReleaseError), contextlib.redirect_stdout(io.StringIO()):
                publisher.poll(client, DEPLOYMENT, 10, 30, sleep=clock.sleep, clock=clock.clock)
            self.assertLessEqual(len(client.calls), 4)

    def test_client_bearer_auth_endpoint_and_redirect_safety(self):
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 201
        response.read.return_value = DEPLOYMENT.encode()
        opener = mock.Mock()
        opener.open.return_value = response
        with mock.patch.object(publisher.urllib.request, "build_opener", return_value=opener):
            client = publisher.PortalClient(self.env)
            client.request("POST", publisher.PORTAL + "/upload?publishingType=AUTOMATIC", data=b"synthetic")
        request = opener.open.call_args.args[0]
        expected = "Bearer " + base64.b64encode(b"synthetic-central-user:synthetic-central-password").decode()
        self.assertEqual(request.get_header("Authorization"), expected)
        with self.assertRaises(publisher.ReleaseError):
            client.request("POST", "http://attacker.invalid/upload")
        self.assertIsNone(publisher.NoRedirect().redirect_request(None, None, 302, "", {}, "https://attacker.invalid"))

    def test_http_error_body_and_exception_are_not_exposed(self):
        opener = mock.Mock()
        opener.open.side_effect = urllib.error.HTTPError(publisher.PORTAL + "/status", 401,
                                                        "SECRET_HTTP_REASON", {}, io.BytesIO(b"SECRET_HTTP_BODY"))
        with mock.patch.object(publisher.urllib.request, "build_opener", return_value=opener):
            client = publisher.PortalClient(self.env)
            self.assertEqual(client.request("POST", publisher.PORTAL + "/status"), (401, b""))
        opener.open.side_effect = OSError("SECRET_EXCEPTION")
        with self.assertRaises(publisher.ReleaseError) as raised:
            client.request("POST", publisher.PORTAL + "/status")
        self.assertNotIn("SECRET", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
