#!/usr/bin/env python3
"""校验清单、CI 标签和首发命名空间的一致性。 / Validates manifest, CI tag and initial-release namespace consistency."""

import argparse
import importlib.util
import os
from pathlib import Path
import re
import sys

REQUEST_SPEC = importlib.util.spec_from_file_location("version_release_request", Path(__file__).with_name("release-request.py"))
assert REQUEST_SPEC is not None and REQUEST_SPEC.loader is not None
REQUEST = importlib.util.module_from_spec(REQUEST_SPEC)
REQUEST_SPEC.loader.exec_module(REQUEST)
ROOT = Path(__file__).resolve().parents[1]
VERSION_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)(?:0[1-9]|[1-9][0-9]))?")


def release_version(root: Path, reference: str) -> str:
    """版本只取自清单，不让标签为旧二进制改名。 / Reads the manifest version, never relabeling binaries from a tag."""
    properties = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                      if line.strip() and not line.lstrip().startswith("#") and "=" in line)
    version = properties["version"]
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("Invalid release version")
    if reference.startswith("refs/tags/") and reference != f"refs/tags/v{version}":
        raise ValueError("Release tag disagrees with gradle.properties")
    return version


def main() -> int:
    """向 CI 输出非敏感的发布元数据。 / Emits non-sensitive release metadata for CI."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default=os.environ.get("GITHUB_REF", ""))
    args = parser.parse_args()
    try:
        version = release_version(ROOT, args.ref)
        sdk_versions = {release_version(ROOT / "thirdparty" / "prism-sdk" / sdk, "") for sdk in ("kap", "vap")}
        if sdk_versions != {version}:
            raise ValueError("First-release bootstrap requires matching SDK and Prism versions; do not republish frozen SDKs")
        publishing = REQUEST.publication_requested(os.environ, version)
    except (OSError, KeyError, ValueError) as error:
        print(f"Release metadata check failed: {error}", file=sys.stderr)
        return 1
    values = {"version": version, "publish": str(publishing).lower(), "prerelease": str("-" in version).lower()}
    if path := os.environ.get("GITHUB_OUTPUT"):
        with open(path, "a", encoding="utf-8") as output:
            for name, value in values.items():
                output.write(f"{name}={value}\n")
    print(f"Release version: {version}; namespace: com.kaus-io; publish: {publishing}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
