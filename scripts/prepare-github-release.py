#!/usr/bin/env python3
"""只生成供本人网页发布的说明和标题，不调用任何远端服务。 / Prepares only title and notes for human web publication without contacting any remote service."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "kaus-io/prism-motion"
FILES = {"prism-motion-linux-x86_64", "prism-motion-windows-x86_64.exe", "LICENSE.txt",
         "THIRD_PARTY_NOTICES.txt", "SIGNING_PUBLIC_KEY.asc", "SHA256SUMS"}
VERSION_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)(?:0[1-9]|[1-9][0-9]))?")


def require(condition: bool, message: str) -> None:
    """只拒绝本地输入，不触发认证或远端操作。 / Rejects local input without triggering authentication or remote operations."""
    if not condition:
        raise ValueError(message)


def project_version() -> str:
    """版本只读取根清单，并拒绝重复或非规范版本。 / Reads the version only from the root manifest and rejects duplicates or noncanonical versions."""
    versions = []
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "version":
            versions.append(value.strip())
    require(len(versions) == 1 and VERSION_PATTERN.fullmatch(versions[0]) is not None,
            "Root version is missing, ambiguous, or invalid")
    return versions[0]


def safe_path(path: Path) -> Path:
    """拒绝路径及父路径中的软链接，不覆盖其指向的内容。 / Rejects symlinks in the path and its parents instead of modifying their targets."""
    absolute = path.absolute()
    require(not any(part.is_symlink() for part in (absolute, *absolute.parents)), "Symlinks are forbidden")
    return absolute.resolve()


def distribution(path: Path) -> dict[str, Path]:
    """只核对六个既有文件和五个摘要，不复制或修改制品。 / Verifies the existing six files and five digests without copying or modifying artifacts."""
    path = safe_path(path)
    require(path.is_dir(), "Distribution directory is missing")
    files = {entry.name: entry for entry in path.iterdir()}
    require(set(files) == FILES and all(entry.is_file() and not entry.is_symlink() for entry in files.values()),
            "Unexpected distribution files")
    expected = {}
    for line in files["SHA256SUMS"].read_text(encoding="ascii").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        require(match is not None, "Invalid distribution checksum manifest")
        digest, name = match.groups()
        require(name in FILES - {"SHA256SUMS"} and name not in expected, "Invalid distribution checksum entry")
        expected[name] = digest
    require(set(expected) == FILES - {"SHA256SUMS"}, "Incomplete distribution checksum manifest")
    for name, digest in expected.items():
        require(hashlib.sha256(files[name].read_bytes()).hexdigest() == digest, "Distribution checksum mismatch")
    return files


def release_notes(version: str) -> str:
    """说明中文在前、英文在后，两种语言保持相同平台边界。 / Places Chinese before English and describes identical platform limits in both languages."""
    return f"""## 中文

Prism Motion {version} 预览版。

- Maven Central 库使用 `com.kaus-io` 命名空间，版本为 `{version}`。
- Linux CLI 支持打包和检查。
- Windows CLI 目前仅支持检查，不支持打包。
- 本版不提供 macOS CLI。

## English

Prism Motion {version} preview.

- Maven Central libraries use the `com.kaus-io` namespace and version `{version}`.
- The Linux CLI supports authoring and inspection.
- The Windows CLI currently supports inspection only, not authoring.
- This release does not include a macOS CLI.
"""


def prepare(path: Path, output: Path) -> str:
    """新建仅含标题和说明的目录，网页上传仍使用原六个文件。 / Creates a title-and-notes-only directory while leaving the original six files for manual web upload."""
    version = project_version()
    files = distribution(path)
    source = files["SHA256SUMS"].parent
    output = safe_path(output)
    require(not output.exists() and output.parent.is_dir() and not output.is_relative_to(source),
            "Metadata output must be a new directory outside the distribution")
    output.mkdir()
    with (output / "TITLE.txt").open("x", encoding="utf-8", newline="\n") as title:
        title.write(version + "\n")
    with (output / "RELEASE_NOTES.md").open("x", encoding="utf-8", newline="\n") as notes:
        notes.write(release_notes(version))
    return f"https://github.com/{REPOSITORY}/releases/new?tag=v{version}&title={version}"


def main() -> int:
    """命令只接受本地分发目录和新说明目录，不接受令牌或版本覆盖。 / Accepts only a local distribution and new metadata directory, never tokens or version overrides."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(prepare(args.distribution, args.output))
        return 0
    except (OSError, ValueError):
        print("GitHub metadata preparation failed; no remote publication was attempted", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
