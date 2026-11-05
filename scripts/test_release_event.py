"""只使用合成 HTTP 和系统临时目录的 Release 发布测试。 / Release publication tests using only synthetic HTTP and OS temporary directories."""

from __future__ import annotations

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import urllib.request
import zipfile

SPEC = importlib.util.spec_from_file_location("release_event_publisher", Path(__file__).with_name("publish-release-to-central.py"))
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
VERSION = release.core.project_version()
DEPLOYMENT = "28570f16-da32-4c14-bd2e-c1acc0782365"


def _pom(version=VERSION, group="com.kaus-io", artifact="prism-motion-core"):
    return (f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{group}</groupId>'
            f"<artifactId>{artifact}</artifactId><version>{version}</version></project>").encode()


class FakeFetch:
    """按显式 URL 返回有限合成字节，从不访问网络。 / Returns finite synthetic bytes for explicit URLs without network access."""

    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def __call__(self, url, max_bytes):
        self.calls.append((url, max_bytes))
        result = self.responses[url]
        if isinstance(result, Exception):
            raise result
        code, data = result
        return code, (data[offset:offset + 7] for offset in range(0, len(data), 7))


class ReleaseEventFixture(unittest.TestCase):
    """默认封锁真实 HTTP 和 Portal，并显式注入上传器。 / Blocks real HTTP and Portal access by default and explicitly injects publishers."""

    def setUp(self):
        """每个用例仅建立合成环境和系统临时目录。 / Creates only synthetic environments and OS temporary directories for each test."""
        temporary = tempfile.TemporaryDirectory(prefix="prism-release-event-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.event_path = self.root / "event.json"
        self.env = {
            "GITHUB_EVENT_NAME": "release", "GITHUB_REF": f"refs/tags/{VERSION}",
            "GITHUB_REF_TYPE": "tag", "GITHUB_REPOSITORY": "kaus-io/prism-motion",
            "GITHUB_SHA": "a" * 40, "GITHUB_EVENT_PATH": str(self.event_path),
        }
        self.event = {"action": "published", "repository": {"full_name": "kaus-io/prism-motion"},
                      "release": {"draft": False, "tag_name": VERSION, "assets": []}}
        self.pom_url = f"{release.PUBLIC}{VERSION}/prism-motion-core-{VERSION}.pom"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("synthetic-signed-fixture", b"unchanged fixture bytes")
        self.bundle = archive.getvalue()
        self.manifest = f"{hashlib.sha256(self.bundle).hexdigest()}  {release.BUNDLE}\n".encode()
        self.publisher = mock.Mock(return_value=DEPLOYMENT)
        self.http = self._patch(release.urllib.request, "build_opener", side_effect=AssertionError("Real HTTP forbidden"))
        self.portal = self._patch(release.core, "PortalClient", side_effect=AssertionError("Real Portal forbidden"))
        self.core_publish = self._patch(release.core, "publish", side_effect=AssertionError("Real upload forbidden"))
        self.guard = self._patch(release.core, "production_guard")
        self._write_event()

    def _patch(self, target, name, **options):
        patcher = mock.patch.object(target, name, **options)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def _write_event(self):
        self.event_path.write_text(json.dumps(self.event), encoding="utf-8")

    def _asset(self, name, data):
        return {"name": name, "state": "uploaded", "size": len(data),
                "browser_download_url": f"{release.DOWNLOAD}{VERSION}/{name}"}

    def _ready(self):
        self.event["release"]["assets"] = [self._asset(release.BUNDLE, self.bundle),
                                             self._asset(release.MANIFEST, self.manifest)]
        self._write_event()
        return FakeFetch({self.pom_url: (404, b""),
                          f"{release.DOWNLOAD}{VERSION}/{release.MANIFEST}": (200, self.manifest),
                          f"{release.DOWNLOAD}{VERSION}/{release.BUNDLE}": (200, self.bundle)})

    def _run(self, fetcher):
        with contextlib.redirect_stdout(io.StringIO()):
            return release.publish_release(self.env, fetcher=fetcher, publisher=self.publisher)

    def _fails(self, fetcher, exception=Exception):
        with self.assertRaises(exception):
            self._run(fetcher)
        self.publisher.assert_not_called()
        self.core_publish.assert_not_called()
        self.portal.assert_not_called()


class ReleaseEventTest(ReleaseEventFixture):
    """独立验证探测、资产下载和原样转发。 / Independently validates probing, asset downloads and unchanged forwarding."""

    def test_existing_version_without_credentials_assets_or_uploader(self):
        """准确已存在版本不要求密钥、制品或上传器。 / An exact existing version needs no secrets, assets or uploader."""
        fetcher = FakeFetch({self.pom_url: (200, _pom())})
        self.assertEqual(self._run(fetcher), "ALREADY_PUBLISHED")
        self.guard.assert_called_once_with(self.env, VERSION)
        self.assertEqual(fetcher.calls, [(self.pom_url, 1024 * 1024)])
        self.publisher.assert_not_called()
        self.core_publish.assert_not_called()
        self.portal.assert_not_called()
        self.assertFalse(any("MAVEN" in key for key in self.env))

    def test_existing_version_does_not_reread_event_assets(self):
        """保护器之后的已存在路径不再读取制品事件文件。 / The existing-version path does not reread the asset event after its guard."""
        self.env["GITHUB_EVENT_PATH"] = str(self.root / "not-present")
        self.assertEqual(self._run(FakeFetch({self.pom_url: (200, _pom())})), "ALREADY_PUBLISHED")

    def test_guard_and_root_version_precede_any_http(self):
        """根版本与保护器失败时禁止任何请求。 / Root-version and guard failures forbid all requests."""
        fetcher = FakeFetch({})
        with mock.patch.object(release.core, "release_version", side_effect=ValueError("version")):
            self._fails(fetcher)
        self.guard.side_effect = ValueError("guard")
        self._fails(fetcher)
        self.assertEqual(fetcher.calls, [])

    def test_only_404_means_absent(self):
        """认证、限流和服务器错误均失败而不继续。 / Authentication, rate-limit and server errors fail without proceeding."""
        for code in (201, 301, 401, 403, 429, 500, 503):
            with self.subTest(code=code):
                fetcher = FakeFetch({self.pom_url: (code, b"sensitive HTTP body")})
                self._fails(fetcher)
                self.assertEqual(len(fetcher.calls), 1)

    def test_probe_timeout_and_network_errors_propagate(self):
        """超时与网络失败保持失败状态。 / Timeouts and network failures remain failures."""
        for error in (TimeoutError("sensitive"), urllib.error.URLError("sensitive")):
            with self.subTest(error=type(error).__name__):
                self._fails(FakeFetch({self.pom_url: error}), type(error))

    def test_malformed_and_unsafe_xml_fail(self):
        """损坏或含实体声明的 POM 不得视作已存在。 / Malformed POMs and entity declarations never prove existence."""
        for body in (b"<project", b"not XML", b'<!DOCTYPE project><project/>',
                     b'<!ENTITY x "secret"><project/>'):
            with self.subTest(body=body):
                self._fails(FakeFetch({self.pom_url: (200, body)}))

    def test_wrong_or_ambiguous_coordinates_fail(self):
        """坐标必须准确、唯一并处于 project 根元素。 / Coordinates must be exact, unique and under a project root."""
        for body in (_pom(version=VERSION + "-wrong"), _pom(group="foreign"), _pom(artifact="other"),
                     _pom().replace(b"<groupId>", b"<groupId>duplicate</groupId><groupId>"),
                     _pom().replace(b"project", b"foreign")):
            with self.subTest(body=body):
                self._fails(FakeFetch({self.pom_url: (200, body)}))

    def test_missing_asset_fails_after_single_public_probe(self):
        """缺失任一精确名称制品时不下载和上传。 / Missing either exact-name asset prevents downloads and upload."""
        for names in ([], [release.BUNDLE], [release.MANIFEST]):
            self.event["release"]["assets"] = [self._asset(name, b"x") for name in names]
            self._write_event()
            fetcher = FakeFetch({self.pom_url: (404, b"")})
            self._fails(fetcher)
            self.assertEqual(fetcher.calls, [(self.pom_url, 1024 * 1024)])

    def test_duplicate_asset_fails_before_asset_download(self):
        """重复名称一律拒绝。 / Duplicate asset names are always rejected."""
        fetcher = self._ready()
        self.event["release"]["assets"].append(copy.deepcopy(self.event["release"]["assets"][0]))
        self._write_event()
        self._fails(fetcher)
        self.assertEqual(len(fetcher.calls), 1)

    def test_noncanonical_asset_urls_fail_before_fetch(self):
        """拒绝任意主机、转义路径、查询和认证 URL。 / Rejects arbitrary hosts, escaped paths, queries and authenticated URLs."""
        for url in ("https://example.invalid/bundle.zip", f"{release.DOWNLOAD}v{VERSION}/{release.BUNDLE}",
                    f"{release.DOWNLOAD}{VERSION}/../{release.BUNDLE}",
                    f"{release.DOWNLOAD}{VERSION}/{release.BUNDLE}?token=secret",
                    f"http://github.com/kaus-io/prism-motion/releases/download/{VERSION}/{release.BUNDLE}",
                    f"https://user:secret@github.com/kaus-io/prism-motion/releases/download/{VERSION}/{release.BUNDLE}"):
            with self.subTest(url=url):
                fetcher = self._ready()
                self.event["release"]["assets"][0]["browser_download_url"] = url
                self._write_event()
                self._fails(fetcher)
                self.assertEqual(len(fetcher.calls), 1)

    def test_unsafe_asset_metadata_fails(self):
        """拒绝未上传、超限、符号链接和非整数大小。 / Rejects pending, oversized, symlink and noninteger asset metadata."""
        for key, value in (("state", "new"), ("size", 0), ("size", True), ("size", "1"),
                           ("size", release.core.MAX_BUNDLE + 1), ("type", "symlink")):
            with self.subTest(key=key, value=value):
                fetcher = self._ready()
                self.event["release"]["assets"][0][key] = value
                self._write_event()
                self._fails(fetcher)
                self.assertEqual(len(fetcher.calls), 1)

    def test_symlink_event_path_fails(self):
        """下载前拒绝事件文件符号链接。 / Rejects symlinked event files before downloads."""
        fetcher = self._ready()
        link = self.root / "event-link.json"
        link.symlink_to(self.event_path)
        self.env["GITHUB_EVENT_PATH"] = str(link)
        self._fails(fetcher)
        self.assertEqual(len(fetcher.calls), 1)

    def test_invalid_manifest_formats_fail_before_zip_download(self):
        """校验行只接受小写摘要、双空格和可选单换行。 / The checksum line allows only lowercase hex, two spaces and one optional newline."""
        valid = self.manifest
        for bad in (valid.upper(), valid.replace(b"  ", b" "), valid + b"\n", valid.replace(b"\n", b"\r\n"),
                    valid.replace(release.BUNDLE.encode(), b"other.zip"), b"x" * 1025):
            with self.subTest(manifest=bad):
                self.manifest = bad
                fetcher = self._ready()
                self._fails(fetcher)
                self.assertLessEqual(len(fetcher.calls), 2)
        self.manifest = valid

    def test_checksum_mismatch_never_uploads(self):
        """摘要不符时绝不调用上传器。 / A checksum mismatch never invokes the uploader."""
        self.manifest = ("0" * 64 + f"  {release.BUNDLE}\n").encode()
        self._fails(self._ready())

    def test_asset_download_errors_and_truncation_fail(self):
        """制品缺失、HTTP 错误和截断均失败。 / Missing assets, HTTP errors and truncation all fail."""
        for name in (release.BUNDLE, release.MANIFEST):
            for code in (404, 401, 403, 500):
                with self.subTest(name=name, code=code):
                    fetcher = self._ready()
                    fetcher.responses[f"{release.DOWNLOAD}{VERSION}/{name}"] = (code, b"sensitive")
                    self._fails(fetcher)
            fetcher = self._ready()
            fetcher.responses[f"{release.DOWNLOAD}{VERSION}/{name}"] = (200, b"truncated")
            self._fails(fetcher)

    def test_asset_network_failures_never_upload(self):
        """制品下载超时和网络故障不得被忽略。 / Asset timeouts and network failures must never be ignored."""
        for name in (release.BUNDLE, release.MANIFEST):
            for error in (TimeoutError("sensitive"), urllib.error.URLError("sensitive")):
                with self.subTest(name=name, error=type(error).__name__):
                    fetcher = self._ready()
                    fetcher.responses[f"{release.DOWNLOAD}{VERSION}/{name}"] = error
                    self._fails(fetcher, type(error))

    def test_download_bounds_apply_even_to_injected_clients(self):
        """注入客户端也不能绕过字节上限。 / Injected clients cannot bypass byte limits."""
        self._fails(FakeFetch({self.pom_url: (200, b"x" * (1024 * 1024 + 1))}))
        fetcher = self._ready()
        fetcher.responses[f"{release.DOWNLOAD}{VERSION}/{release.MANIFEST}"] = (200, b"x" * 1025)
        self._fails(fetcher)
        fetcher = self._ready()
        with mock.patch.object(release.core, "MAX_BUNDLE", len(self.bundle)):
            fetcher.responses[f"{release.DOWNLOAD}{VERSION}/{release.BUNDLE}"] = (200, self.bundle + b"x")
            self._fails(fetcher)

    def test_good_zip_and_sidecar_are_forwarded_byte_identically(self):
        """ZIP 和校验行原样传递，只提供合成 Maven 凭据。 / Forwards ZIP and checksum bytes unchanged with only synthetic Maven credentials."""
        self.env.update(MAVEN_CENTRAL_USERNAME="synthetic-user", MAVEN_CENTRAL_PASSWORD="synthetic-password")
        saved = []

        def inspect(bundle, manifest, version, environment):
            self.assertEqual(bundle.read_bytes(), self.bundle)
            self.assertEqual(manifest.read_bytes(), self.manifest)
            self.assertEqual(version, VERSION)
            self.assertIs(environment, self.env)
            self.assertFalse(bundle.is_relative_to(Path(__file__).resolve().parents[1]))
            self.assertFalse(bundle.is_symlink())
            self.assertEqual(bundle.name, release.BUNDLE)
            saved.extend([bundle, manifest])
            return DEPLOYMENT

        self.publisher.side_effect = inspect
        fetcher = self._ready()
        self.assertEqual(self._run(fetcher), DEPLOYMENT)
        self.publisher.assert_called_once()
        self.assertTrue(all(not path.exists() for path in saved))
        self.assertEqual([limit for _, limit in fetcher.calls], [1024 * 1024, 1024, release.core.MAX_BUNDLE])
        self.portal.assert_not_called()

    def test_optional_manifest_newline_is_preserved(self):
        """可选尾换行不得由包装器改写。 / The wrapper never rewrites the optional trailing newline."""
        self.manifest = self.manifest.removesuffix(b"\n")
        self.publisher.side_effect = lambda bundle, manifest, *_: (self.assertEqual(manifest.read_bytes(), self.manifest) or DEPLOYMENT)
        self.assertEqual(self._run(self._ready()), DEPLOYMENT)

    def test_default_publisher_is_existing_core_only(self):
        """未注入时只调用现有受保护发布函数。 / Without injection, invokes only the existing guarded publisher."""
        self.core_publish.side_effect = None
        self.core_publish.return_value = DEPLOYMENT
        self.assertEqual(release.publish_release(self.env, fetcher=self._ready()), DEPLOYMENT)
        self.core_publish.assert_called_once()
        self.publisher.assert_not_called()
        self.portal.assert_not_called()

    def test_publisher_failure_is_not_swallowed(self):
        """上传器失败不会转换为成功或已存在。 / Publisher failures never become success or already-published results."""
        self.publisher.side_effect = PermissionError("sensitive")
        with self.assertRaises(PermissionError):
            self._run(self._ready())

    def _cli_failure(self, error):
        output = io.StringIO()
        with mock.patch.object(release, "publish_release", side_effect=error), mock.patch.object(sys, "argv", ["publisher"]), contextlib.redirect_stderr(output):
            self.assertEqual(release.main(), 1)
        self.assertNotIn("SENSITIVE", output.getvalue())
        self.assertNotIn("Traceback", output.getvalue())
        return output.getvalue()

    def test_cli_unknown_errors_remain_fixed_and_redacted(self):
        """未知异常不泄漏消息、凭据和本地路径。 / Unknown failures never expose messages, credentials or local paths."""
        for error in (ValueError("SENSITIVE_PASSWORD"), PermissionError("/SENSITIVE_LOCAL_PATH"),
                      RuntimeError("SENSITIVE_RESPONSE")):
            self.assertEqual(self._cli_failure(error), "Release publication failed; local and HTTP details suppressed.\n")

    def test_cli_trusted_release_error_exposes_safe_reason(self):
        """受信任的固定保护消息可用于定位失败。 / Trusted fixed guard messages help diagnose failures."""
        self.assertEqual(self._cli_failure(release.core.ReleaseError("Bundle checksum mismatch")),
                         "Release publication failed: Bundle checksum mismatch\n")

    def test_cli_http_errors_expose_only_numeric_status(self):
        """HTTP 失败只输出状态码，不输出地址、正文或响应头。 / HTTP failures expose status codes but never URLs, bodies or response headers."""
        for code in (401, 403, 500):
            body = io.BytesIO(b"SENSITIVE_RESPONSE_BODY")
            self.addCleanup(body.close)
            error = urllib.error.HTTPError("https://SENSITIVE_USER:SENSITIVE_PASSWORD@example.invalid/SENSITIVE_PATH",
                                           code, "SENSITIVE_REASON", {"Authorization": "SENSITIVE_HEADER"}, body)
            self.assertEqual(self._cli_failure(error),
                             f"Release publication failed: HTTP status {code}; response details suppressed.\n")
            self.assertTrue(body.closed)

    def test_cli_network_errors_expose_only_safe_category(self):
        """超时和网络故障只输出固定类别。 / Timeouts and network failures expose only fixed categories."""
        for error, category in ((TimeoutError("SENSITIVE_TIMEOUT"), "network timeout (TimeoutError)"),
                                (urllib.error.URLError("SENSITIVE_URL"), "network request error (URLError)"),
                                (urllib.error.URLError(TimeoutError("SENSITIVE_TIMEOUT")), "network request error (URLError)")):
            self.assertEqual(self._cli_failure(error),
                             f"Release publication failed: {category}; details suppressed.\n")

    def test_cli_only_exact_known_source_error_is_reported(self):
        """仅准确的来源保护常量可以展示，其它值错误仍脱敏。 / Only the exact source-guard constant is reportable; other value errors remain redacted."""
        self.assertEqual(self._cli_failure(ValueError(release.core.SOURCE.ERROR)),
                         f"Release publication failed: {release.core.SOURCE.ERROR}\n")
        self.assertEqual(self._cli_failure(ValueError(release.core.SOURCE.ERROR + " SENSITIVE_TRAILER")),
                         "Release publication failed; local and HTTP details suppressed.\n")

    def test_cli_no_argument_success(self):
        """入口直接转交真实进程环境，成功返回零。 / The entry point forwards the process environment directly and returns zero on success."""
        with mock.patch.object(release, "publish_release", return_value="ALREADY_PUBLISHED") as publish, mock.patch.object(sys, "argv", ["publisher"]):
            self.assertEqual(release.main(), 0)
        publish.assert_called_once_with(release.os.environ)

    def test_cli_refuses_all_arguments_without_echo(self):
        """禁止参数并避免打印令牌类参数。 / Forbids arguments without echoing token-like values."""
        output = io.StringIO()
        with mock.patch.object(sys, "argv", ["publisher", "SENSITIVE_TOKEN"]), contextlib.redirect_stderr(output):
            self.assertEqual(release.main(), 1)
        self.assertNotIn("SENSITIVE", output.getvalue())
        self.http.assert_not_called()

    def test_public_fetch_is_get_only_without_authorization(self):
        """默认 HTTP 客户端只发 GET 且不携带认证头。 / The default HTTP client sends only GET without authorization headers."""
        response = io.BytesIO(b"synthetic")
        response.status = 200
        self.http.side_effect = None
        self.http.return_value.open.return_value = response
        code, chunks = release.fetch(self.pom_url, 32)
        self.assertEqual((code, b"".join(chunks)), (200, b"synthetic"))
        request = self.http.return_value.open.call_args.args[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.data)
        self.assertFalse(any("authorization" in key.lower() for key in request.headers))
        self.assertTrue(response.closed)

    def test_public_fetch_only_converts_404_and_preserves_http_error_type(self):
        """仅将 404 转为缺失，其它 HTTP 异常原样失败。 / Converts only 404 to absence and preserves all other HTTP failures."""
        for code in (404, 401, 403, 429, 500):
            body = io.BytesIO(b"SENSITIVE_BODY")
            error = urllib.error.HTTPError(self.pom_url, code, "sensitive", {}, body)
            self.http.side_effect = None
            self.http.return_value.open.side_effect = error
            if code == 404:
                self.assertEqual(release.fetch(self.pom_url, 100), (404, ()))
            else:
                with self.assertRaises(urllib.error.HTTPError) as raised:
                    release.fetch(self.pom_url, 100)
                self.assertIs(raised.exception, error)
            self.assertTrue(body.closed)

    def test_public_fetch_bounds_and_closes_realistic_stream(self):
        """真实形状响应超限时关闭流并失败。 / A realistic oversized response closes its stream and fails."""
        response = io.BytesIO(b"too large")
        response.status = 200
        self.http.side_effect = None
        self.http.return_value.open.return_value = response
        _, chunks = release.fetch(self.pom_url, 3)
        with self.assertRaises(release.core.ReleaseError):
            list(chunks)
        self.assertTrue(response.closed)

    def test_redirects_allow_https_cdn_but_never_plaintext_or_userinfo(self):
        """允许标准 HTTPS CDN 跳转，拒绝明文和认证地址。 / Allows standard HTTPS CDN redirects but rejects plaintext and user information."""
        request = urllib.request.Request(f"{release.DOWNLOAD}{VERSION}/{release.BUNDLE}", method="GET")
        handler = release._HttpsRedirect()
        redirected = handler.redirect_request(request, None, 302, "", {}, "https://release-assets.githubusercontent.com/synthetic")
        self.assertEqual(redirected.get_method(), "GET")
        self.assertFalse(any("authorization" in key.lower() for key in redirected.headers))
        for url in ("http://example.invalid/file", "https://user:secret@example.invalid/file"):
            with self.assertRaises(release.core.ReleaseError):
                handler.redirect_request(request, None, 302, "", {}, url)


class ReleaseGuardIntegrationTest(ReleaseEventFixture):
    """使用真实共享保护器验证事件，而 HTTP 与上传仍为离线替身。 / Uses the real shared event guard while HTTP and uploads remain offline doubles."""

    def setUp(self):
        """在独立实例中恢复真实保护器。 / Restores the real guard in an isolated test instance."""
        original = release.core.production_guard
        super().setUp()
        self.guard.side_effect = original

    def test_explicit_tag_source_manifest_overrides_tool_checkout_version(self):
        """标签源码根清单优先于已推进版本的工具检出目录。 / The tag source root manifest takes precedence over an advanced tooling checkout."""
        source = self.root / "tag-source"
        tooling = self.root / "publisher"
        source.mkdir()
        (tooling / "scripts").mkdir(parents=True)
        different = "1.0.0-alpha99" if VERSION != "1.0.0-alpha99" else "1.0.0-alpha98"
        (source / "gradle.properties").write_text(f"version={VERSION}\n", encoding="utf-8")
        (tooling / "gradle.properties").write_text(f"version={different}\n", encoding="utf-8")
        self.env["RELEASE_SOURCE_DIRECTORY"] = str(source)
        with mock.patch.object(release.core, "__file__", str(tooling / "scripts" / "publish-central-bundle.py")), mock.patch.dict(release.os.environ, {"RELEASE_SOURCE_DIRECTORY": str(tooling)}):
            self.assertEqual(release.core.project_version(), different)
            self.assertEqual(self._run(FakeFetch({self.pom_url: (200, _pom())})), "ALREADY_PUBLISHED")
        self.guard.assert_called_once_with(self.env, VERSION)
        self.publisher.assert_not_called()
        self.portal.assert_not_called()

    def test_real_release_published_event_allows_existing_version(self):
        """准确的 release.published 事件可直接确认已发布。 / An exact release.published event can directly confirm an existing version."""
        self.assertEqual(self._run(FakeFetch({self.pom_url: (200, _pom())})), "ALREADY_PUBLISHED")

    def test_real_guard_rejects_legacy_v_prefixed_tags(self):
        """旧 v 前缀无论在引用、事件或两处出现都不再接受。 / Rejects the legacy v prefix in the ref, event tag or both."""
        original_event = copy.deepcopy(self.event)
        original_env = dict(self.env)
        for legacy_ref, legacy_event in ((True, False), (False, True), (True, True)):
            with self.subTest(ref=legacy_ref, event=legacy_event):
                self.env = dict(original_env)
                self.event = copy.deepcopy(original_event)
                if legacy_ref:
                    self.env["GITHUB_REF"] = f"refs/tags/v{VERSION}"
                if legacy_event:
                    self.event["release"]["tag_name"] = f"v{VERSION}"
                self._write_event()
                fetcher = FakeFetch({})
                self._fails(fetcher)
                self.assertEqual(fetcher.calls, [])

    def test_cli_malformed_release_event_exposes_safe_guard_reason(self):
        """损坏事件报告有用的发布保护原因，不泄漏事件或密钥。 / A malformed event reports the useful publication-guard reason without leaking payloads or secrets."""
        self.event_path.write_text("{SENSITIVE_EVENT_BODY", encoding="utf-8")
        environment = {**self.env, "MAVEN_CENTRAL_USERNAME": "SENSITIVE_USER",
                       "MAVEN_CENTRAL_PASSWORD": "SENSITIVE_PASSWORD"}
        output = io.StringIO()
        with mock.patch.dict(release.os.environ, environment, clear=True), mock.patch.object(sys, "argv", ["publisher"]), contextlib.redirect_stderr(output):
            self.assertEqual(release.main(), 1)
        self.assertIn("Cannot validate the published Release event payload", output.getvalue())
        self.assertNotIn("SENSITIVE", output.getvalue())
        self.assertNotIn(str(self.root), output.getvalue())
        self.assertNotIn("Traceback", output.getvalue())
        self.http.assert_not_called()
        self.publisher.assert_not_called()
        self.portal.assert_not_called()

    def test_real_guard_rejects_wrong_action_tag_draft_and_repository(self):
        """真实保护器在任何 HTTP 前拒绝错误发布来源。 / The real guard rejects invalid release origins before any HTTP."""
        original = copy.deepcopy(self.event)
        changes = (("action", "edited"), ("tag_name", f"{VERSION}-wrong"),
                   ("draft", True), ("repository", "foreign/repository"))
        for field, value in changes:
            with self.subTest(field=field):
                self.event = copy.deepcopy(original)
                if field == "action":
                    self.event[field] = value
                elif field == "repository":
                    self.event[field]["full_name"] = value
                else:
                    self.event["release"][field] = value
                self._write_event()
                fetcher = FakeFetch({})
                self._fails(fetcher)
                self.assertEqual(fetcher.calls, [])

    def test_real_guard_rejects_nonrelease_environment(self):
        """手工触发、错误标签和非规范仓库一律拒绝。 / Rejects manual dispatch, wrong tags and noncanonical repositories."""
        for name, value in (("GITHUB_EVENT_NAME", "workflow_dispatch"), ("GITHUB_REF", "refs/heads/main"),
                            ("GITHUB_REF_TYPE", "branch"), ("GITHUB_REPOSITORY", "foreign/repository")):
            environment = dict(self.env)
            environment[name] = value
            fetcher = FakeFetch({})
            with self.subTest(name=name), self.assertRaises(release.core.ReleaseError):
                release.publish_release(environment, fetcher=fetcher, publisher=self.publisher)
            self.assertEqual(fetcher.calls, [])


if __name__ == "__main__":
    unittest.main()
