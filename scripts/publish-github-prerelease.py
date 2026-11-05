#!/usr/bin/env python3
"""只补齐同一批校验产物的 draft，不覆盖已发布文件。 / Completes drafts from identical validated assets without replacing published files."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

SOURCE_SPEC = importlib.util.spec_from_file_location("github_release_source", Path(__file__).with_name("verify-release-source.py"))
assert SOURCE_SPEC is not None and SOURCE_SPEC.loader is not None
SOURCE = importlib.util.module_from_spec(SOURCE_SPEC)
SOURCE_SPEC.loader.exec_module(SOURCE)
ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "kaus-io/prism-motion"
FILES = {"prism-motion-linux-x86_64", "prism-motion-windows-x86_64.exe", "LICENSE.txt",
         "THIRD_PARTY_NOTICES.txt", "SIGNING_PUBLIC_KEY.asc", "SHA256SUMS"}


def require(condition: bool, message: str) -> None:
    """拒绝不确定的远端状态，不输出认证信息。 / Rejects ambiguous remote state without printing authentication material."""
    if not condition:
        raise ValueError(message)


def distribution(path: Path) -> dict[str, Path]:
    """约束下载清单和文件摘要，不接受额外文件或软链接。 / Enforces the exact download manifest and rejects extra files or symlinks."""
    files = {entry.name: entry for entry in path.iterdir()}
    require(set(files) == FILES and all(entry.is_file() and not entry.is_symlink() for entry in files.values()),
            "Unexpected distribution files")
    expected = {}
    for line in files["SHA256SUMS"].read_text().splitlines():
        digest, name = line.split("  ", 1)
        require(re.fullmatch(r"[0-9a-f]{64}", digest) is not None and name in FILES - {"SHA256SUMS"}
                and name not in expected, "Invalid distribution checksum manifest")
        expected[name] = digest
    require(set(expected) == FILES - {"SHA256SUMS"}, "Incomplete distribution checksum manifest")
    for name, digest in expected.items():
        require(hashlib.sha256(files[name].read_bytes()).hexdigest() == digest, "Distribution checksum mismatch")
    return files


def gh(arguments: list[str]) -> subprocess.CompletedProcess:
    """让官方 CLI 使用环境令牌，不把原始 stderr 回显到日志。 / Uses the official CLI's environment token without replaying raw stderr."""
    return subprocess.run(["gh", *arguments], capture_output=True, text=True, timeout=180)


def publish(path: Path, version: str, commit: str, runner=gh, *, tag_resolver=None) -> str:
    """安全续发相同 draft；已公开产物必须逐字节一致。 / Resumes identical drafts and requires byte equality for already public assets."""
    files = distribution(path)
    resolver = tag_resolver or SOURCE.remote_tag_commit
    SOURCE.verify_tag(version, commit, resolver)
    tag = f"v{version}"
    response = runner(["api", f"repos/{REPOSITORY}/releases/tags/{tag}"])
    if response.returncode:
        require("HTTP 404" in response.stderr, "Cannot determine whether the GitHub release exists")
        SOURCE.verify_tag(version, commit, resolver)
        created = runner(["release", "create", tag, "--verify-tag", "--draft", "--prerelease", "--latest=false",
                          "--target", commit, "--title", f"Prism Motion {version}", "--notes",
                          "Prism preview: Maven libraries use com.kaus-io and this exact version. Linux CLI supports authoring and inspection; Windows currently supports inspection. macOS is not included."])
        require(created.returncode == 0, "Cannot create the GitHub draft; no assets were replaced")
        release = {"draft": True, "tag_name": tag, "prerelease": True, "assets": []}
    else:
        release = json.loads(response.stdout)
    require(release.get("tag_name") == tag and release.get("prerelease") is True,
            "Existing release does not match the requested prerelease")
    names = [asset["name"] for asset in release.get("assets", [])]
    require(len(names) == len(set(names)) and set(names) <= FILES, "Existing release has unexpected assets")
    require(isinstance(release.get("draft"), bool), "Existing release has no draft state")
    if names:
        with tempfile.TemporaryDirectory(prefix="prism-existing-release-") as directory:
            downloaded = runner(["release", "download", tag, "--dir", directory, "--pattern", "*"])
            require(downloaded.returncode == 0, "Cannot verify existing release assets")
            for name in names:
                existing = Path(directory) / name
                require(existing.is_file() and not existing.is_symlink() and existing.read_bytes() == files[name].read_bytes(),
                        "Existing asset differs; refusing to overwrite")
    missing = FILES - set(names)
    if not release["draft"]:
        require(not missing, "Published release is incomplete; refusing to alter it")
        SOURCE.verify_tag(version, commit, resolver)
        return f"https://github.com/{REPOSITORY}/releases/tag/{tag}"
    for name in sorted(missing):
        SOURCE.verify_tag(version, commit, resolver)
        uploaded = runner(["release", "upload", tag, str(files[name])])
        require(uploaded.returncode == 0, "Draft upload failed; identical existing assets can be resumed")
    SOURCE.verify_tag(version, commit, resolver)
    completed = runner(["release", "edit", tag, "--draft=false", "--prerelease", "--latest=false"])
    require(completed.returncode == 0, "Draft remains unpublished; rerun the same validated assets to resume")
    SOURCE.verify_tag(version, commit, resolver)
    return f"https://github.com/{REPOSITORY}/releases/tag/{tag}"


def main() -> int:
    """仅允许匹配清单的官方标签运行。 / Allows only canonical tag runs matching the manifest."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, required=True)
    args = parser.parse_args()
    try:
        properties = dict(line.strip().split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line)
        version = properties["version"]
        require(re.fullmatch(r"1\.0\.0-alpha(?:0[1-9]|[1-9][0-9])", version) is not None, "Unsupported prerelease version")
        require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY and os.environ.get("GITHUB_REF") == f"refs/tags/v{version}"
                and os.environ.get("GH_TOKEN"), "GitHub publication requires an authorized canonical tag run")
        commit = SOURCE.checkout_commit(os.environ)
        url = publish(args.distribution.resolve(strict=True), version, commit)
        if output := os.environ.get("GITHUB_OUTPUT"):
            with open(output, "a", encoding="utf-8") as handle:
                handle.write(f"release_url={url}\n")
        print(url)
    except (OSError, KeyError, ValueError, subprocess.SubprocessError):
        print("GitHub prerelease failed safely; no existing asset was replaced", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
