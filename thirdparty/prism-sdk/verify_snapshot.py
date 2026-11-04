#!/usr/bin/env python3
"""离线核验冻结 SDK 的边界和逐文件哈希。 / Verifies frozen SDK boundaries and per-file hashes offline."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tomllib


GENERATED_DIRECTORIES = {"build", ".gradle", ".kotlin", ".cxx", "__pycache__"}
BINARY_SUFFIXES = {".jar", ".aar", ".so", ".spv", ".class", ".dex", ".apk", ".aab", ".zip"}


def require(condition: bool, message: str) -> None:
    """失败时给出可审计的原因。 / Reports an auditable reason on failure."""
    if not condition:
        raise ValueError(message)


def payload_paths(root: Path) -> set[str]:
    """列出受核验文件，不包含构建缓存。 / Lists verified files without generated build caches."""
    result = set()
    for directory, names, filenames in os.walk(root, followlinks=False):
        parent = Path(directory)
        for name in names:
            require(not (parent / name).is_symlink(), f"Symlink directory is forbidden: {name}")
            require(name != ".git", "Nested Git metadata is forbidden")
        names[:] = [name for name in names if name not in GENERATED_DIRECTORIES]
        for name in filenames:
            path = parent / name
            require(not path.is_symlink(), f"Symlink file is forbidden: {path.relative_to(root)}")
            relative = path.relative_to(root).as_posix()
            if relative != "PROVENANCE.json":
                result.add(relative)
    return result


def verify(root: Path) -> None:
    """仅使用快照内的清单核验源码、许可证和发布闭包。 / Verifies sources, licenses and release closure using only the snapshot manifest."""
    manifest = json.loads((root / "PROVENANCE.json").read_text(encoding="utf-8"))
    require(manifest["schemaVersion"] == 1, "Unsupported provenance schema")
    entries = manifest["files"]
    expected = set()
    for entry in entries:
        relative = entry["path"]
        normalized = PurePosixPath(relative)
        require(not normalized.is_absolute() and ".." not in normalized.parts, f"Unsafe path: {relative}")
        require(normalized.as_posix() == relative and "\\" not in relative, f"Noncanonical path: {relative}")
        require(relative not in expected, f"Duplicate manifest entry: {relative}")
        require(Path(relative).suffix.lower() not in BINARY_SUFFIXES, f"Binary payload is forbidden: {relative}")
        expected.add(relative)
        path = root / relative
        require(path.is_file() and not path.is_symlink(), f"Missing regular file: {relative}")
        payload = path.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        require(len(payload) == entry["bytes"], f"Size mismatch: {relative}")
        require(digest == entry["sha256"], f"SHA-256 mismatch: {relative}")
        if entry["handling"] == "copied-byte-for-byte":
            require(digest == entry["sourceSha256"], f"Copied source changed: {relative}")
        if entry["role"] in {"production-source", "test-source", "module-build", "consumer-rules", "third-party-license"}:
            require(entry["handling"] == "copied-byte-for-byte", f"Frozen input is not an exact copy: {relative}")
    actual = payload_paths(root)
    require(actual == expected, f"Payload set mismatch; added={sorted(actual - expected)}, missing={sorted(expected - actual)}")
    by_path = {entry["path"]: entry for entry in entries}
    legal_entries = [
        entry["path"].split("/resources/", 1)[1]
        for entry in entries if entry["role"] == "license-resource"
    ]
    require(len(legal_entries) == len(set(legal_entries)), "Legal resource paths collide across SDK modules")
    changed = {
        entry["path"] for entry in entries
        if entry["role"] in {"production-source", "test-source"}
        and entry.get("sourceState") != "matches-recorded-head"
    }
    require(changed == set(manifest["currentRegionSourceManifest"]), "Modified/local Region source index is incomplete")
    for sdk, details in manifest["sdks"].items():
        sdk_root = root / sdk
        modules = details["modules"]
        properties = dict(
            line.split("=", 1) for line in (sdk_root / "gradle.properties").read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")
        )
        require(properties["group"] == manifest["release"]["group"], f"Group mismatch: {sdk}")
        require(properties["version"] == manifest["release"]["version"], f"Version mismatch: {sdk}")
        settings = (sdk_root / "settings.gradle.kts").read_text(encoding="utf-8")
        require(set(re.findall(r'":([a-z0-9-]+)"', settings)) == set(modules), f"Wrong included module closure: {sdk}")
        catalog = tomllib.loads((sdk_root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
        require(catalog["versions"]["android-minSdk"] == "29", f"Wrong minSdk: {sdk}")
        require(catalog["versions"]["android-compileSdk"] == "37", f"Wrong compileSdk: {sdk}")
        accessors = {
            parts[0] + "".join(part.title() for part in parts[1:])
            for parts in (module.split("-") for module in modules)
        }
        for module in modules:
            build = (sdk_root / module / "build.gradle.kts").read_text(encoding="utf-8")
            dependencies = set(re.findall(r"projects\.([A-Za-z0-9]+)", build))
            require(dependencies <= accessors, f"Dependency outside release closure: {module}")
            require(sorted(dependencies) == details["projectDependencyAccessors"][module], f"Wrong dependency closure: {module}")
            source_set = "main" if sdk == "kap" or module == "vap-vk-android" else "commonMain"
            legal = f"{sdk}/{module}/src/{source_set}/resources/META-INF/prism-sdk/{sdk}/{module}"
            for name in ("LICENSE", "NOTICE"):
                require(by_path[f"{legal}/{name}"]["sha256"] == by_path[f"{sdk}/{name}"]["sha256"], f"License resource mismatch: {module}/{name}")
            if module in {"kap-android", "vap-vk-android"}:
                require('"arm64-v8a", "armeabi-v7a"' in build, f"Both native ABIs are required: {module}")
                require('version = "3.22.1"' in build, f"Wrong CMake version: {module}")
        if sdk == "kap":
            require(
                by_path["kap/kap-android/src/main/resources/META-INF/prism-sdk/kap/kap-android/ZSTD-LICENSE"]["sha256"]
                == by_path["kap/kap-android/src/main/cpp/third_party/zstd/LICENSE"]["sha256"],
                "Zstandard binary redistribution license is missing",
            )
        for relative in ["settings.gradle.kts", "build.gradle.kts", *(f"{module}/build.gradle.kts" for module in modules)]:
            text = (sdk_root / relative).read_text(encoding="utf-8")
            require("includeBuild" not in text and "/home/" not in text and "../" not in text, f"External filesystem dependency: {sdk}/{relative}")
        frozen = [entry for entry in entries if entry["path"].startswith(f"{sdk}/") and entry["role"] in {"production-source", "test-source", "module-build", "consumer-rules", "third-party-license"}]
        require(len(frozen) == details["frozenModuleFileCount"], f"Frozen file count mismatch: {sdk}")
        print(f"{sdk}: {len(modules)} modules, {len(frozen)} exact module/source/rule files verified")
    print(f"Snapshot integrity verified: {len(entries)} payload files; no upstream checkout or network used")


def main() -> int:
    """返回适用于 CI 的核验状态。 / Returns a CI-friendly verification status."""
    try:
        verify(Path(__file__).resolve().parent)
    except (KeyError, OSError, TypeError, ValueError) as error:
        print(f"Snapshot integrity failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
