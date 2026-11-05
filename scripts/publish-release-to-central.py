#!/usr/bin/env python3
"""只转发已公开 Release 的预签名 Maven 制品。 / Publishes only presigned Maven assets from a published Release."""

from __future__ import annotations

import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Mapping
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("release_central_core", Path(__file__).with_name("publish-central-bundle.py"))
assert SPEC is not None and SPEC.loader is not None
core = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(core)
BUNDLE = "maven-central-bundle.zip"
MANIFEST = BUNDLE + ".sha256"
PUBLIC = "https://repo.maven.apache.org/maven2/com/kaus-io/prism-motion-core/"
DOWNLOAD = "https://github.com/kaus-io/prism-motion/releases/download/"


class _HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        """公开下载仍只允许无认证 HTTPS 跳转。 / Keeps public-download redirects on unauthenticated HTTPS only."""
        target = urllib.parse.urlsplit(new_url)
        core.require(target.scheme == "https" and bool(target.hostname)
                     and target.username is None and target.password is None, "Unsafe public download redirect")
        return super().redirect_request(request, response, code, message, headers, new_url)


def fetch(url: str, max_bytes: int):
    """只作无认证 GET，返回状态和有界字节块迭代器。 / Makes unauthenticated GETs, returning a status and bounded byte-chunk iterator."""
    request = urllib.request.Request(url, headers={"User-Agent": "prism-motion-release/1"}, method="GET")
    try:
        response = urllib.request.build_opener(_HttpsRedirect()).open(request, timeout=60)
    except urllib.error.HTTPError as error:
        error.close()
        if error.code == 404:
            return 404, ()
        raise
    if response.status != 200:
        code = response.status
        response.close()
        return code, ()

    def chunks():
        with response:
            total = 0
            while block := response.read(min(1024 * 1024, max_bytes - total + 1)):
                total += len(block)
                core.require(total <= max_bytes, "Public download exceeds the safety limit")
                yield block

    return response.status, chunks()


def _transfer(fetcher, url, limit, destination):
    code, chunks = fetcher(url, limit)
    try:
        core.require(code in (200, 404), "Public download failed")
        if code == 404:
            return code, 0
        size = 0
        for block in chunks:
            core.require(isinstance(block, bytes), "Invalid public download stream")
            size += len(block)
            core.require(size <= limit, "Public download exceeds the safety limit")
            destination.write(block)
        return code, size
    finally:
        if hasattr(chunks, "close"):
            chunks.close()


def _read(fetcher, url, limit):
    destination = io.BytesIO()
    code, _ = _transfer(fetcher, url, limit, destination)
    return code, destination.getvalue()


def _existing_pom(body, version):
    core.require(b"<!DOCTYPE" not in body.upper() and b"<!ENTITY" not in body.upper(), "Unsafe public POM")
    try:
        project = ET.fromstring(body)
    except ET.ParseError:
        raise core.ReleaseError("Invalid public POM") from None
    namespace = "{http://maven.apache.org/POM/4.0.0}" if project.tag.startswith("{") else ""
    fields = [project.findall(namespace + name) for name in ("groupId", "artifactId", "version")]
    core.require(project.tag == namespace + "project" and all(len(nodes) == 1 for nodes in fields)
                 and tuple((nodes[0].text or "").strip() for nodes in fields)
                 == ("com.kaus-io", "prism-motion-core", version), "Public POM coordinates do not match")


def _assets(environment, version):
    event_path = core.safe_path(Path(environment["GITHUB_EVENT_PATH"]))
    core.require(event_path.is_file() and event_path.stat().st_size <= 1024 * 1024, "Invalid Release event file")
    event = json.loads(event_path.read_text(encoding="utf-8"))
    assets = event["release"].get("assets")
    core.require(isinstance(assets, list), "Release bundle assets are missing")
    selected = {}
    for asset in assets:
        core.require(isinstance(asset, dict), "Invalid Release asset metadata")
        name = asset.get("name")
        if name not in (BUNDLE, MANIFEST):
            continue
        limit = core.MAX_BUNDLE if name == BUNDLE else 1024
        core.require(name not in selected and asset.get("state") == "uploaded"
                     and asset.get("type", "file") == "file"
                     and type(asset.get("size")) is int and 0 < asset["size"] <= limit
                     and asset.get("browser_download_url") == f"{DOWNLOAD}v{version}/{name}",
                     "Release bundle asset metadata is unsafe or ambiguous")
        selected[name] = asset
    core.require(set(selected) == {BUNDLE, MANIFEST}, "Release bundle assets are missing")
    return selected


def publish_release(environment: Mapping[str, str], fetcher=fetch, publisher=None) -> str:
    """先确认准确版本是否已存在，再原样转发预签名 ZIP；可注入离线客户端。 / Probes the exact version before forwarding an unchanged presigned ZIP; clients are injectable for offline tests."""
    version = (core.release_version(None, source_directory=environment["RELEASE_SOURCE_DIRECTORY"])
               if "RELEASE_SOURCE_DIRECTORY" in environment else core.release_version(None))
    core.production_guard(environment, version)
    code, pom = _read(fetcher, f"{PUBLIC}{version}/prism-motion-core-{version}.pom", 1024 * 1024)
    if code == 200:
        _existing_pom(pom, version)
        print("The exact Central version is already published; no upload was attempted", flush=True)
        return "ALREADY_PUBLISHED"
    assets = _assets(environment, version)
    code, manifest = _read(fetcher, assets[MANIFEST]["browser_download_url"], 1024)
    core.require(code == 200 and len(manifest) == assets[MANIFEST]["size"], "Checksum asset download failed")
    match = re.fullmatch(rb"([0-9a-f]{64})  maven-central-bundle\.zip\n?", manifest)
    core.require(match is not None, "Invalid bundle checksum manifest")
    with tempfile.TemporaryDirectory(prefix="prism-release-central-") as directory:
        root = core.safe_path(Path(directory))
        core.require(not root.is_relative_to(Path(__file__).resolve().parents[1]), "Downloads must remain outside the repository")
        bundle = core.safe_path(root / BUNDLE, existing=False)
        sidecar = core.safe_path(root / MANIFEST, existing=False)
        with bundle.open("xb") as stream:
            code, size = _transfer(fetcher, assets[BUNDLE]["browser_download_url"], core.MAX_BUNDLE, stream)
        core.require(code == 200 and size == assets[BUNDLE]["size"], "Bundle asset download failed")
        core.require(core.file_digest(bundle) == match[1].decode("ascii"), "Bundle checksum mismatch")
        with sidecar.open("xb") as stream:
            stream.write(manifest)
        return (publisher or core.publish)(bundle, sidecar, version, environment)


def main() -> int:
    """无参数发布入口，只输出固定安全错误。 / Provides a no-argument publication entry point with fixed safe errors only."""
    try:
        core.require(len(sys.argv) == 1, "Arguments are forbidden")
        publish_release(os.environ)
    except Exception:
        print("Release publication failed; local and HTTP details suppressed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
