"""校验发行版检查器的协议边界。 / Tests release-checker protocol boundaries."""

import hashlib
import contextlib
import io
import importlib.util
import json
import os
import sys
from pathlib import Path
import struct
import tempfile
import unittest
import zlib
from unittest.mock import patch


def load(name, filename):
    """从仓库脚本加载测试目标，不安装包。 / Loads repository scripts without installing packages."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SMOKE = load("release_cli_smoke", "smoke-cli-release.py")
VERSION = load("release_cli_version", "release-version.py")


class ReleaseCliTest(unittest.TestCase):
    def test_chinese_json_does_not_use_the_windows_ansi_code_page(self):
        message = {"message": "旧外部格式 .ktx 已废弃，只接受 .prism"}
        payload = json.dumps(message, ensure_ascii=False).encode("utf-8")
        with self.assertRaises(UnicodeDecodeError):
            payload.decode("cp1252")
        with tempfile.TemporaryDirectory(prefix="prism-cli-encoding-test-") as directory:
            with patch("subprocess._text_encoding", return_value="cp1252"):
                result = SMOKE.capture_cli([sys.executable, "-c", f"import os; os.write(1, {payload!r})"],
                                           Path(directory), os.environ.copy())
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout), message)

    def test_ci_failure_annotation_is_portable_and_redacts_credentials(self):
        with tempfile.TemporaryDirectory(prefix="prism-cli-error-test-") as directory:
            binary = Path(directory) / "fake.exe"
            binary.write_bytes(b"test")
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(sys, "argv", ["smoke", "--binary", str(binary), "--version", "1.0.0-alpha01"]):
                with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "GH_TOKEN": "fake-release-token"}, clear=True):
                    with patch.object(SMOKE, "smoke", side_effect=ValueError("中文 fake-release-token")):
                        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                            self.assertEqual(SMOKE.main(), 1)
            self.assertIn("::error::", stdout.getvalue())
            stdout.getvalue().encode("ascii")
            self.assertNotIn("fake-release-token", stdout.getvalue() + stderr.getvalue())
            self.assertIn("[REDACTED]", stdout.getvalue())

    def test_cli_does_not_receive_release_credentials(self):
        environment = {"PATH": "safe-path", "GH_TOKEN": "fake", "NPM_TOKEN": "fake",
                       "GPG_KEY_CONTENTS": "fake", "ORG_GRADLE_PROJECT_signingInMemoryKey": "fake",
                       "ORG_GRADLE_PROJECT_mavenCentralPassword": "fake", "PRISM_MOTION_ASTCENC": "outside-tool"}
        with patch.dict("os.environ", environment, clear=True):
            child = SMOKE.child_environment(Path("cache"))
        self.assertEqual(child, {"PATH": "safe-path", "XDG_CACHE_HOME": "cache"})

    def test_embedded_tool_is_extracted_from_exact_footer(self):
        tool = b"native-test-tool"
        name = b"astcenc"
        data = struct.pack("<I", len(name)) + name + struct.pack("<Q", len(tool)) + tool
        executable = b"exe" + data + struct.pack("<8sQQ", b"PMTOOLS1", 3, len(data))
        self.assertEqual(SMOKE.embedded_tool(executable, "astcenc"), tool)
        for invalid in (b"bare-executable", executable[:-1], executable[:-24] + struct.pack("<8sQQ", b"PMTOOLS1", 4, len(data))):
            with self.assertRaises(ValueError):
                SMOKE.embedded_tool(invalid, "astcenc")
        with self.assertRaises(ValueError):
            SMOKE.embedded_tool(executable, "astcenc.exe")

    def test_duplicate_embedded_tool_is_rejected(self):
        entry = struct.pack("<I", 7) + b"astcenc" + struct.pack("<Q", 1) + b"x"
        executable = entry * 2 + struct.pack("<8sQQ", b"PMTOOLS1", 0, len(entry) * 2)
        with self.assertRaises(ValueError):
            SMOKE.embedded_tool(executable, "astcenc")

    def test_synthetic_png_has_valid_chunks_and_rgba_scanlines(self):
        png = SMOKE.png(32, 32, 1)
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
        position, compressed = 8, b""
        while position < len(png):
            size = struct.unpack_from(">I", png, position)[0]
            kind = png[position + 4:position + 8]
            body = png[position + 8:position + 8 + size]
            self.assertEqual(zlib.crc32(kind + body), struct.unpack_from(">I", png, position + 8 + size)[0])
            if kind == b"IDAT":
                compressed += body
            position += 12 + size
        self.assertEqual(len(zlib.decompress(compressed)), 32 * (1 + 32 * 4))

    def test_payload_hash_and_exact_bounds(self):
        data = b"native-payload"
        asset = bytearray(4096)
        asset[:8] = b"PRISM\0\0\0"
        struct.pack_into("<I", asset, 128, 2)
        struct.pack_into("<QQ", asset, 136, 4096, len(data))
        asset[152:184] = hashlib.sha256(data).digest()
        asset += data
        self.assertEqual(SMOKE.payload(asset), (2, data))
        asset[-1] ^= 1
        with self.assertRaises(ValueError):
            SMOKE.payload(asset)

    def test_tag_must_match_manifest_without_relabeling(self):
        with tempfile.TemporaryDirectory(prefix="prism-version-test-") as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text("version=1.0.0-alpha01\n")
            self.assertEqual(VERSION.release_version(root, "refs/tags/v1.0.0-alpha01"), "1.0.0-alpha01")
            self.assertEqual(VERSION.release_version(root, "refs/heads/main"), "1.0.0-alpha01")
            with self.assertRaises(ValueError):
                VERSION.release_version(root, "refs/tags/v1.0.0-alpha02")
            (root / "gradle.properties").write_text("version=1.0.0-alpha1\n")
            with self.assertRaises(ValueError):
                VERSION.release_version(root, "")


if __name__ == "__main__":
    unittest.main()
