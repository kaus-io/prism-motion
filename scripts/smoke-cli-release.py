#!/usr/bin/env python3
"""在全新缓存与独立进程验证下载版 CLI。 / Verifies a distributable CLI using fresh caches and processes."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import zlib


def require(condition: bool, message: str) -> None:
    """给出不包含发布凭据的失败。 / Reports failures without release credentials."""
    if not condition:
        raise ValueError(message)


def embedded_tool(binary: bytes, name: str) -> bytes:
    """检查末尾工具索引，不能把 embed 的跳过当成功。 / Checks the tool footer rather than treating a skipped embed task as success."""
    require(len(binary) >= 24, "CLI has no embedded-tools footer")
    magic, offset, length = struct.unpack_from("<8sQQ", binary, len(binary) - 24)
    require(magic == b"PMTOOLS1" and offset + length == len(binary) - 24, "CLI embedded-tools footer is invalid")
    position, end = offset, offset + length
    found = None
    while position < end:
        require(end - position >= 4, "Truncated tool name length")
        size = struct.unpack_from("<I", binary, position)[0]
        position += 4
        require(0 < size <= end - position, "Invalid tool name")
        entry = binary[position:position + size].decode("utf-8")
        position += size
        require(end - position >= 8, "Truncated tool data length")
        size = struct.unpack_from("<Q", binary, position)[0]
        position += 8
        require(0 < size <= end - position, "Invalid tool data")
        if entry == name:
            require(found is None, "Duplicate embedded tool")
            found = binary[position:position + size]
        position += size
    require(found is not None, f"Missing embedded {name}")
    return found


def png(width: int, height: int, index: int) -> bytes:
    """生成不含私有素材的合成 PNG。 / Generates a synthetic PNG with no private assets."""
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            rows.extend((x * 255 // width, y * 255 // height, index * 60, (x + y + index * 13) % 256))
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def payload(asset: bytes) -> tuple[int, bytes]:
    """独立检查统一容器的索引与原生载荷摘要。 / Independently checks a container index and its native payload digest."""
    require(len(asset) > 4096 and asset[:8] == b"PRISM\0\0\0", "Smoke asset is not Prism")
    kind = struct.unpack_from("<I", asset, 128)[0]
    offset, length = struct.unpack_from("<QQ", asset, 136)
    require(kind in (1, 2) and offset == 4096 and offset + length == len(asset), "Invalid smoke asset region")
    data = asset[offset:]
    require(hashlib.sha256(data).digest() == asset[152:184], "Smoke asset payload digest mismatch")
    return kind, data


def child_environment(cache: Path) -> dict[str, str]:
    """命令行及第三方工具不继承发布凭据。 / Keeps publishing credentials out of the CLI and third-party tool processes."""
    sensitive = {"GPG_KEY_CONTENTS", "MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD", "SIGNING_KEY_ID",
                 "SIGNING_PASSWORD", "GH_TOKEN", "GITHUB_TOKEN", "NPM_TOKEN", "ACTIONS_RUNTIME_TOKEN",
                 "ACTIONS_ID_TOKEN_REQUEST_TOKEN", "GITHUB_ENV", "GITHUB_OUTPUT", "GITHUB_STATE",
                 "GITHUB_PATH", "GITHUB_STEP_SUMMARY", "SSH_AUTH_SOCK"}
    environment = {key: value for key, value in os.environ.items()
                   if key not in sensitive and not key.startswith("ORG_GRADLE_PROJECT_signing")
                   and not key.startswith("ORG_GRADLE_PROJECT_mavenCentral")}
    environment["XDG_CACHE_HOME"] = str(cache)
    environment.pop("PRISM_MOTION_ASTCENC", None)
    return environment


def capture_cli(arguments: list[str], directory: Path, environment: dict[str, str]) -> subprocess.CompletedProcess:
    """JSON 统一使用 UTF-8，不依赖 Windows ANSI 代码页。 / Reads JSON as UTF-8 regardless of the Windows ANSI code page."""
    return subprocess.run(arguments, cwd=directory, env=environment, capture_output=True,
                          text=True, encoding="utf-8", timeout=120)


def smoke(binary: Path, version: str, fixtures: Path | None = None, export: Path | None = None) -> None:
    """复制发布文件到无工具邻居的新目录，并走真实调用。 / Runs the actual distributable in a fresh directory without neighboring tools."""
    name = "astcenc.exe" if os.name == "nt" else "astcenc"
    expected_tool = embedded_tool(binary.read_bytes(), name)
    with tempfile.TemporaryDirectory(prefix="prism-release-smoke-") as directory:
        root = Path(directory)
        executable = root / ("prism-motion.exe" if os.name == "nt" else "prism-motion")
        shutil.copy2(binary, executable)
        executable.chmod(executable.stat().st_mode | 0o111)
        cache = root / "cache"
        environment = child_environment(cache)

        def run(*arguments: str, code: int = 0, as_json: bool = False):
            result = capture_cli([str(executable), *arguments], root, environment)
            require(result.returncode == code,
                    f"CLI {arguments[0]} returned {result.returncode}, expected {code}: {result.stderr[-1000:]}")
            if as_json:
                data = json.loads(result.stdout)
                require(data.get("ok") == (code == 0), "CLI JSON status disagrees with exit status")
                return data
            return result.stdout.strip()

        actual_version = run("version")
        require(actual_version == version, f"CLI version {actual_version!r} disagrees with release version {version}")
        engines = run("engines", "--json", as_json=True)
        require({item["suffix"] for item in engines["engines"]} == {".prism"}, "CLI advertises obsolete formats")
        tool = Path(engines["tools"]["astcenc"]).resolve()
        require(tool.is_relative_to(cache.resolve()) and tool.is_file(), "CLI did not extract its embedded tool into the fresh cache")
        require(tool.read_bytes() == expected_tool, "Extracted tool differs from release payload")
        result = subprocess.run([str(tool), "-version"], cwd=root, env=environment, capture_output=True, timeout=30)
        require(result.returncode == 0, "Embedded tool cannot execute on the target platform")
        assets = root / "assets"
        assets.mkdir()
        if fixtures is not None:
            candidates = sorted(fixtures.iterdir())
            require(len(candidates) == 3 and all(path.is_file() and path.suffix == ".prism" for path in candidates), "Expected three synthetic Prism fixtures")
            for source in candidates:
                shutil.copy2(source, assets / source.name)
        else:
            require(os.name != "nt", "Windows PNG/H.264 authoring is not implemented")
            frames = root / "frames"
            frames.mkdir()
            for index in range(3):
                (frames / f"f{index:03}.png").write_bytes(png(32, 32, index))
            for backend, kind, arguments in (
                ("kap", "rgba", ("--format", "rgba8")),
                ("kap", "astc", ("--format", "astc-8x8")),
                ("vap", "video", ("--bitrate", "3000")),
            ):
                run("pack", backend, "--frames", str(frames), "--out", str(assets / f"{kind}.prism"),
                    "--fps", "30", *arguments, "--json", as_json=True)
        run("check", str(assets), "--json", as_json=True)
        seen = set()
        for asset in sorted(assets.iterdir()):
            run("probe", str(asset), "--json", as_json=True)
            kind, native = payload(asset.read_bytes())
            seen.add(kind)
            obsolete = root / ("obsolete.mp4" if kind == 1 else "obsolete.ktx")
            obsolete.write_bytes(native)
            run("probe", str(obsolete), "--json", code=1, as_json=True)
            corrupt = bytearray(asset.read_bytes())
            corrupt[-1] ^= 1
            bad = root / "corrupt.prism"
            bad.write_bytes(corrupt)
            run("probe", str(bad), "--json", code=1, as_json=True)
        require(seen == {1, 2}, "Smoke fixtures do not cover both payload families")
        if export is not None:
            require(not export.exists(), "Fixture export destination already exists")
            export.mkdir(parents=True)
            for asset in assets.iterdir():
                shutil.copy2(asset, export / asset.name)
    print("CLI release smoke passed: version, embedded tool, both payload families, obsolete input and corruption rejection")


def main() -> int:
    """Linux 可打包，Windows 复用同一合成语料验证读取。 / Linux authors fixtures; Windows verifies the same synthetic corpus."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--export-fixtures", type=Path)
    args = parser.parse_args()
    try:
        smoke(args.binary.resolve(strict=True), args.version, args.fixtures, args.export_fixtures)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        message = f"CLI release smoke failed: {error}"
        for name, value in os.environ.items():
            if value and (name.startswith("ORG_GRADLE_PROJECT_signing") or name.startswith("ORG_GRADLE_PROJECT_mavenCentral")
                          or name in {"GPG_KEY_CONTENTS", "MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD", "SIGNING_PASSWORD", "GH_TOKEN", "GITHUB_TOKEN", "NPM_TOKEN"}):
                message = message.replace(value, "[REDACTED]")
        print(message, file=__import__("sys").stderr)
        if os.environ.get("GITHUB_ACTIONS") == "true":
            portable = message[-3000:].encode("ascii", "backslashreplace").decode("ascii")
            encoded = portable.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::error::{encoded}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
