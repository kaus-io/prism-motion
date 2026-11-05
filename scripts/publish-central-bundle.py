#!/usr/bin/env python3
"""离线打包并显式发布一个 Central 原子部署。 / Bundles offline and explicitly publishes one atomic Central deployment.

Portal API reference: https://central.sonatype.org/publish/publish-portal-api/
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import stat
import sys
import time
from typing import BinaryIO, Mapping
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile


SOURCE_SPEC = importlib.util.spec_from_file_location("central_release_source", Path(__file__).with_name("verify-release-source.py"))
assert SOURCE_SPEC is not None and SOURCE_SPEC.loader is not None
SOURCE = importlib.util.module_from_spec(SOURCE_SPEC)
SOURCE_SPEC.loader.exec_module(SOURCE)
REQUEST_SPEC = importlib.util.spec_from_file_location("central_release_request", Path(__file__).with_name("release-request.py"))
assert REQUEST_SPEC is not None and REQUEST_SPEC.loader is not None
REQUEST = importlib.util.module_from_spec(REQUEST_SPEC)
REQUEST_SPEC.loader.exec_module(REQUEST)
GROUP = "com.kaus-io"
PREFIX = "com/kaus-io/"
PORTAL = "https://central.sonatype.com/api/v1/publisher"
PUBLIC = "https://repo.maven.apache.org/maven2/"
TARGETS = {
    "prism-motion-format": ("", "android", "jvm", "linuxx64", "mingwx64"),
    "prism-motion-core": ("", "android", "jvm", "linuxx64", "mingwx64"),
    "prism-motion-compose": ("", "android", "jvm"),
    "prism-motion-engine-kap": ("", "android", "jvm"),
    "prism-motion-engine-vap": ("", "android", "jvm"),
    "prism-motion-ktx": ("", "android", "jvm", "linuxx64", "mingwx64"),
    "prism-motion-vap": ("", "android", "jvm", "linuxx64", "mingwx64"),
    "prism-motion-probe": ("", "jvm", "linuxx64", "mingwx64"),
    "prism-motion-utils": ("", "jvm", "linuxx64", "mingwx64"),
    "prism-motion-native-loader": ("", "jvm"),
    "kap-core": ("",), "kap-android": ("",), "kap-compose": ("",),
    "vap-log": ("", "android", "jvm"), "vap-core": ("", "android", "jvm"),
    "vap-decode-api": ("", "android", "jvm"), "vap-decode-jvm": ("", "jvm"),
    "vap-vk-android": ("",), "vap-decode-android": ("", "android"),
    "vap-compose": ("", "android", "jvm"),
}
ALLOWED_ARTIFACTS = frozenset(
    f"{artifact}-{target}" if target else artifact
    for artifact, targets in TARGETS.items() for target in targets
)
TOOLING_METADATA_ROOTS = frozenset(
    {artifact for artifact in TARGETS if artifact.startswith("prism-motion-")}
    | {"vap-log", "vap-core", "vap-decode-api", "vap-decode-jvm", "vap-decode-android", "vap-compose"}
)
PRIMARY = ("pom", "jar", "klib", "aar", "module")
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
STATES = frozenset(("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"))
MAX_FILE = 1024 * 1024 * 1024
MAX_BUNDLE = 4 * MAX_FILE
MAX_ENTRIES = 10000


class ReleaseError(ValueError):
    """仅包含固定安全消息的发布失败。 / A release failure containing only fixed, safe messages."""


class AlreadyPublished(ReleaseError):
    """仅由公开仓库中已核实的精确 POM 坐标触发。 / Raised only for an exact POM coordinate confirmed in the public repository."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """不把凭据或上传正文转发到重定向地址。 / Never forwards credentials or upload bodies to redirects."""

    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class PortalClient:
    """使用固定 HTTPS 端点和内存中的凭据。 / Uses fixed HTTPS endpoints and in-memory credentials."""

    def __init__(self, environment: Mapping[str, str]):
        username = environment.get("MAVEN_CENTRAL_USERNAME", "")
        password = environment.get("MAVEN_CENTRAL_PASSWORD", "")
        if not username.strip() or not password.strip() or ":" in username:
            raise ReleaseError("Central credentials are missing or invalid")
        if any(char in username + password for char in "\r\n\x00"):
            raise ReleaseError("Central credentials are missing or invalid")
        self.authorization = "Bearer " + base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method: str, url: str, data=None, headers=None, authenticated: bool = True,
                timeout: float = 30) -> tuple[int, bytes]:
        """返回有限响应，不记录响应体或异常文本。 / Returns a bounded response without logging bodies or exception text."""
        if not (url.startswith(PORTAL + "/") if authenticated else url.startswith(PUBLIC)):
            raise ReleaseError("Refusing a nonstandard Central endpoint")
        request_headers = {"User-Agent": "prism-motion-release/1", **(headers or {})}
        if authenticated:
            request_headers["Authorization"] = self.authorization
        request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
        try:
            with self.opener.open(request, timeout=timeout) as response:
                result = response.read(1024 * 1024 + 1)
                if len(result) > 1024 * 1024:
                    raise ReleaseError("Central response exceeds the safety limit")
                return response.status, result
        except urllib.error.HTTPError as error:
            code = error.code
            error.close()
            return code, b""
        except (OSError, ValueError, urllib.error.URLError):
            raise ReleaseError("Central HTTPS request failed; response details suppressed") from None


def require(condition: bool, message: str) -> None:
    """只使用调用方提供的安全错误消息。 / Uses only caller-provided safe error messages."""
    if not condition:
        raise ReleaseError(message)


def project_version(source_directory: Path | str | None = None) -> str:
    """读取发布标签源码的唯一版本清单，默认使用脚本所在仓库。 / Reads the release tag source's single version manifest, defaulting to the script's repository."""
    directory = source_directory if source_directory is not None else os.environ.get("RELEASE_SOURCE_DIRECTORY", str(Path(__file__).resolve().parents[1]))
    require(bool(str(directory).strip()), "Release source directory must not be empty")
    source = safe_path(Path(directory))
    require(source.is_dir(), "Release source must be an existing directory")
    properties = safe_path(source / "gradle.properties")
    versions = []
    for line in properties.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "version":
            versions.append(value.strip())
    require(len(versions) == 1, "Root version property is missing or ambiguous")
    require(bool(re.fullmatch(r"1\.0\.0-alpha(?:0[1-9]|[1-9][0-9])", versions[0])),
            "Root version must be 1.0.0-alphaNN with a positive two-digit ordinal")
    return versions[0]


def release_version(requested: str | None, source_directory: Path | str | None = None) -> str:
    """禁止命令行版本覆盖发布标签清单的版本。 / Forbids overriding the release tag manifest version from the command line."""
    version = project_version(source_directory)
    require(requested is None or requested == version, "Requested version differs from the root version")
    return version


def production_guard(environment: Mapping[str, str], version: str) -> None:
    """Central 只接受已公开的准确版本 Release，不依赖 GitHub 发布令牌。 / Accepts only the published exact-version Release without a GitHub publishing token."""
    try:
        requested = REQUEST.publication_requested(environment, version)
    except ValueError as error:
        raise ReleaseError(str(error)) from None
    require(requested, "Publishing requires a published canonical exact root-version Release")


def safe_path(path: Path, existing: bool = True) -> Path:
    """拒绝路径中任何符号链接。 / Rejects any symlink in a path."""
    absolute = path.absolute()
    require(not any(part.is_symlink() for part in (absolute, *absolute.parents)), "Symlinks are forbidden")
    if existing:
        require(absolute.exists(), "Required local path does not exist")
    return absolute.resolve()


def artifact_path(name: str, version: str) -> tuple[str, str, str]:
    """验证允许的 GAV 布局、分类器和扩展名。 / Validates the allowed GAV layout, classifiers and extensions."""
    require("\\" not in name and "\x00" not in name and not name.startswith("/"), "Invalid repository entry path")
    parts = name.split("/")
    require(len(parts) == 5 and all(part not in ("", ".", "..") for part in parts),
            "Repository entries must use the exact GAV layout")
    group1, group2, artifact, entry_version, filename = parts
    require((group1, group2) == ("com", "kaus-io") and artifact in ALLOWED_ARTIFACTS,
            "Foreign namespace or artifact is forbidden")
    require(entry_version == version, "Foreign artifact version is forbidden")
    stem = f"{artifact}-{version}"
    tooling = re.fullmatch(re.escape(stem + "-kotlin-tooling-metadata.json")
                          + r"(?:\.asc)?(?:\.(?:md5|sha1|sha256|sha512))?", filename)
    if tooling is not None:
        require(artifact in TOOLING_METADATA_ROOTS, "Tooling metadata is restricted to known KMP root artifacts")
        return artifact, filename, "json"
    match = re.fullmatch(re.escape(stem) + r"(?:-(?:sources|javadoc))?\.(pom|jar|klib|aar|module)(\.asc)?(?:\.(md5|sha1|sha256|sha512))?", filename)
    require(match is not None, "Unsupported artifact filename or extension")
    assert match is not None
    require(not (filename.startswith(stem + "-sources.") or filename.startswith(stem + "-javadoc."))
            or match[1] == "jar", "Unsupported artifact classifier")
    return artifact, filename, match[1]


def reject_private_material(data: bytes, depth: int = 0) -> None:
    """同时检查普通文件和嵌套产物内的私钥标记。 / Checks private-key markers in plain files and nested artifacts."""
    require(not re.search(rb"-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----", data),
            "Private key material is forbidden in release artifacts")
    if data.startswith(b"PK\x03\x04"):
        require(depth < 3, "Nested archive depth exceeds the safety limit")
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                require(len(archive.infolist()) <= MAX_ENTRIES, "Nested archive exceeds the safety limit")
                require(sum(item.file_size for item in archive.infolist()) <= MAX_BUNDLE,
                        "Nested archive exceeds the safety limit")
                for entry in archive.infolist():
                    require(entry.file_size <= MAX_FILE, "Nested archive entry exceeds the safety limit")
                    require(not stat.S_ISLNK(entry.external_attr >> 16), "Nested symlinks are forbidden")
                    if not entry.is_dir():
                        reject_private_material(archive.read(entry), depth + 1)
        except (zipfile.BadZipFile, RuntimeError):
            raise ReleaseError("Invalid nested artifact archive") from None


def validate_entries(entries: Mapping[str, object], reader, version: str) -> list[str]:
    """检查 POM 坐标、签名和存在的校验和，不替代完整 publication 验证器。 / Checks POM coordinates, signatures and present checksums, not full publication completeness."""
    require(bool(entries) and len(entries) <= MAX_ENTRIES, "Repository is empty or exceeds the safety limit")
    poms = []
    directories = set()
    for name in sorted(entries):
        artifact, filename, extension = artifact_path(name, version)
        directory = name.rsplit("/", 1)[0]
        directories.add(directory)
        data = reader(name)
        require(0 < len(data) <= MAX_FILE, "Empty artifact or artifact exceeds the safety limit")
        reject_private_material(data)
        if name.endswith(tuple("." + algorithm for algorithm in CHECKSUMS)):
            target, algorithm = name.rsplit(".", 1)
            require(target in entries, "Orphan artifact checksum")
            require(data.decode("ascii").strip() == hashlib.new(algorithm, reader(target)).hexdigest(),
                    "Artifact checksum mismatch")
        elif name.endswith(".asc"):
            require(name[:-4] in entries, "Orphan artifact signature")
        else:
            require(name + ".asc" in entries, "Every primary artifact requires a detached signature")
            if extension == "json":
                try:
                    metadata = json.loads(data)
                except (ValueError, UnicodeError):
                    raise ReleaseError("Invalid Kotlin tooling metadata JSON") from None
                require(isinstance(metadata, dict), "Invalid Kotlin tooling metadata JSON")
            if extension == "pom":
                require(filename == f"{artifact}-{version}.pom", "Classified POMs are forbidden")
                require(b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), "Unsafe POM XML")
                try:
                    pom = ET.fromstring(data)
                except ET.ParseError:
                    raise ReleaseError("Invalid POM XML") from None
                namespace = "{http://maven.apache.org/POM/4.0.0}" if pom.tag.startswith("{") else ""
                gav = tuple((pom.findtext(namespace + field) or "").strip()
                            for field in ("groupId", "artifactId", "version"))
                require(gav == (GROUP, artifact, version), "POM coordinates do not match the repository path")
                poms.append(name)
    require(bool(poms), "Repository must contain POM artifact entries")
    for directory in directories:
        artifact = directory.split("/")[-2]
        require(f"{directory}/{artifact}-{version}.pom" in entries, "Artifact directory has no canonical POM")
    return poms


def collect(repositories: list[Path], version: str) -> dict[str, Path]:
    """合并本地暂存树，拒绝任何路径或 GAV 冲突。 / Merges local staging trees, rejecting path or GAV collisions."""
    entries = {}
    coordinates = set()
    for repository in repositories:
        root = safe_path(repository)
        require(root.is_dir(), "Repository must be a directory")
        local = {}
        for directory, subdirectories, filenames in os.walk(root, followlinks=False):
            for basename in subdirectories + filenames:
                path = Path(directory) / basename
                require(not path.is_symlink(), "Symlinks are forbidden")
                require(path.is_dir() or path.is_file(), "Special filesystem entries are forbidden")
                if path.is_dir():
                    parts = path.relative_to(root).parts
                    require(len(parts) <= 4 and parts[0] == "com"
                            and (len(parts) < 2 or parts[1] == "kaus-io")
                            and (len(parts) < 3 or parts[2] in ALLOWED_ARTIFACTS)
                            and (len(parts) < 4 or parts[3] == version),
                            "Foreign or nested repository directory is forbidden")
            for filename in filenames:
                path = Path(directory) / filename
                relative = path.relative_to(root).as_posix()
                if re.fullmatch(r"(?:com/kaus-io/[a-z0-9-]+/)?maven-metadata\.xml(?:\.(?:md5|sha1|sha256|sha512))?", relative):
                    require("/" not in relative or relative.split("/")[2] in ALLOWED_ARTIFACTS,
                            "Foreign metadata artifact is forbidden")
                    continue
                artifact_path(relative, version)
                require(path.stat().st_size <= MAX_FILE, "Artifact exceeds the safety limit")
                local[relative] = path
        validate_entries(local, lambda name: local[name].read_bytes(), version)
        local_coordinates = {name.rsplit("/", 1)[0] for name in local}
        require(not (coordinates & local_coordinates) and not (entries.keys() & local.keys()),
                "Duplicate GAV or repository entry across staging roots")
        coordinates.update(local_coordinates)
        entries.update(local)
    require(sum(path.stat().st_size for path in entries.values()) <= MAX_BUNDLE,
            "Bundle exceeds the safety limit")
    return entries


def bundle(repositories: list[Path], output: Path, version: str) -> Path:
    """创建稳定排序、固定时间戳的 ZIP 和 SHA256 清单，绝不覆盖。 / Creates a sorted, fixed-timestamp ZIP and SHA256 manifest without overwriting."""
    entries = collect(repositories, version)
    output = safe_path(output, existing=False)
    manifest = Path(str(output) + ".sha256")
    require(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.zip", output.name)), "Output must have a safe ZIP basename")
    require(not output.exists() and not manifest.exists(), "Bundle or checksum manifest already exists")
    require(output.parent.is_dir(), "Output directory does not exist")
    require(all(not output.is_relative_to(safe_path(repository)) for repository in repositories),
            "Bundle output must be outside all staging repositories")
    with output.open("xb") as stream:
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, path in sorted(entries.items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    digest = file_digest(output)
    with manifest.open("x", encoding="ascii", newline="\n") as stream:
        stream.write(f"{digest}  {output.name}\n")
    return manifest


def file_digest(path: Path) -> str:
    """逐块计算 ZIP 校验和。 / Calculates a ZIP checksum in chunks."""
    with path.open("rb") as stream:
        return stream_digest(stream)


def stream_digest(stream: BinaryIO) -> str:
    """对同一个待上传描述符计算校验和。 / Checksums the same descriptor that will be uploaded."""
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    stream.seek(0)
    return digest.hexdigest()


def verify_bundle(stream: BinaryIO, filename: str, manifest: Path, version: str) -> list[str]:
    """上传前复核清单和所有 ZIP 条目，不解压到磁盘。 / Rechecks the manifest and every ZIP entry before upload, without extracting to disk."""
    manifest = safe_path(manifest)
    require(manifest.stat().st_size <= 4096, "Invalid checksum manifest")
    expected = manifest.read_bytes()
    checksum_line = f"{stream_digest(stream)}  {filename}".encode("ascii")
    require(expected in (checksum_line, checksum_line + b"\n"), "Bundle checksum manifest mismatch")
    require(os.fstat(stream.fileno()).st_size <= MAX_BUNDLE, "Bundle exceeds the safety limit")
    try:
        with zipfile.ZipFile(stream) as archive:
            infos = archive.infolist()
            require(0 < len(infos) <= MAX_ENTRIES, "ZIP entry count exceeds the safety limit")
            require(sum(item.file_size for item in infos) <= MAX_BUNDLE, "ZIP exceeds the safety limit")
            entries = {}
            for info in infos:
                require(not info.is_dir() and not stat.S_ISLNK(info.external_attr >> 16), "ZIP directories or symlinks are forbidden")
                require(stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG), "ZIP special files are forbidden")
                require(info.filename not in entries, "Duplicate ZIP entry")
                require(info.file_size <= MAX_FILE and not info.flag_bits & 1, "Unsafe ZIP entry")
                entries[info.filename] = info
            poms = validate_entries(entries, lambda name: archive.read(entries[name]), version)
    except (zipfile.BadZipFile, RuntimeError):
        raise ReleaseError("Invalid Central bundle ZIP") from None
    stream.seek(0)
    return poms


def confirm_already_published(client: PortalClient, pom: str) -> None:
    """HEAD 命中后读取同一公开 POM，只确认准确 GAV 已存在。 / Confirms an exact GAV by reading the same public POM after a successful HEAD."""
    parts = pom.split("/")
    require(len(parts) == 5, "Invalid public POM coordinate")
    artifact, version = parts[2], parts[3]
    artifact_path(pom, version)
    code, body = client.request("GET", PUBLIC + pom, authenticated=False)
    require(code == 200, "Cannot confirm the existing public POM")
    require(b"<!DOCTYPE" not in body.upper() and b"<!ENTITY" not in body.upper(), "Unsafe existing public POM")
    try:
        project = ET.fromstring(body)
    except ET.ParseError:
        raise ReleaseError("Invalid existing public POM") from None
    namespace = "{http://maven.apache.org/POM/4.0.0}" if project.tag.startswith("{") else ""
    coordinates = tuple((project.findtext(namespace + field) or "").strip()
                        for field in ("groupId", "artifactId", "version"))
    require(project.tag == namespace + "project" and coordinates == (GROUP, artifact, version),
            "Existing public POM coordinates do not match")
    raise AlreadyPublished("The exact artifact version is already published")


def preflight(client: PortalClient, poms: list[str]) -> None:
    """检查公开坐标，只有核实已存在的准确版本才允许幂等成功。 / Checks public coordinates and allows idempotent success only for a confirmed exact existing version."""
    for pom in poms:
        for attempt in range(3):
            try:
                code, _ = client.request("HEAD", PUBLIC + pom, authenticated=False)
            except ReleaseError:
                if attempt == 2:
                    raise
                continue
            if code == 404:
                break
            if code == 200:
                confirm_already_published(client, pom)
            if code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise ReleaseError("Public repository preflight failed; upload was not attempted")


def multipart(stream: BinaryIO, boundary: str):
    """以流式方式上传已验证的同一个 ZIP。 / Streams the exact ZIP descriptor that was validated."""
    yield (f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"central-bundle.zip\"\r\n"
           "Content-Type: application/octet-stream\r\n\r\n").encode("ascii")
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        yield block
    yield f"\r\n--{boundary}--\r\n".encode("ascii")


def output_value(environment: Mapping[str, str], name: str, value: str) -> None:
    """只向 runner 输出文件追加受控标识符。 / Appends only controlled identifiers to the runner output file."""
    if environment.get("GITHUB_OUTPUT"):
        path = safe_path(Path(environment["GITHUB_OUTPUT"]))
        with path.open("a", encoding="ascii", newline="\n") as stream:
            stream.write(f"{name}={value}\n")


def poll(client: PortalClient, deployment: str, interval: float, timeout: float,
         *, sleep=time.sleep, clock=time.monotonic) -> None:
    """有界轮询只在 PUBLISHED 时成功，重试状态请求而非上传。 / Bounded polling succeeds only on PUBLISHED and retries status requests, never uploads."""
    deadline = clock() + timeout
    failures = 0
    while clock() < deadline:
        try:
            code, body = client.request("POST", PORTAL + "/status?" + urllib.parse.urlencode({"id": deployment}),
                                        data=b"", timeout=min(30, max(0.1, deadline - clock())))
        except ReleaseError:
            code, body = 503, b""
        if code in (429, 500, 502, 503, 504):
            failures += 1
            require(failures <= 3, "Central status request repeatedly failed; do not blindly upload again")
        else:
            require(code == 200, "Central status request failed; details suppressed")
            failures = 0
            try:
                status = json.loads(body)
            except (ValueError, UnicodeError):
                raise ReleaseError("Invalid Central status response") from None
            require(isinstance(status, dict) and status.get("deploymentId") == deployment,
                    "Central status deployment identifier mismatch")
            state = status.get("deploymentState")
            require(isinstance(state, str) and state in STATES, "Invalid Central deployment state")
            print(f"Central deployment {deployment}: {state}", flush=True)
            require(state != "FAILED", "Central validation failed; inspect deployment in Portal (response details suppressed)")
            if state == "PUBLISHED":
                return
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(interval, remaining))
    raise ReleaseError("Central did not reach PUBLISHED before timeout; do not blindly upload again")


def publish(bundle_path: Path, manifest: Path, version: str, environment: Mapping[str, str],
            deployment_id_file: Path | None = None, interval: float = 10, timeout: float = 1800,
            deployment_id: str | None = None, *, tag_resolver=None, commit_resolver=None) -> str:
    """核对已公开 Release、标签和不可变性，准确重发幂等成功。 / Checks the published Release, tag, and immutability and handles exact repeated publication idempotently."""
    production_guard(environment, version)
    require(1 <= interval <= 60 and 1 <= timeout <= 7200, "Polling bounds are invalid")
    path = safe_path(bundle_path)
    require(path.is_file(), "Bundle must be a regular file")
    if deployment_id is not None:
        try:
            deployment_id = str(uuid.UUID(deployment_id))
        except ValueError:
            raise ReleaseError("Invalid deployment identifier") from None
    if deployment_id_file is not None:
        deployment_id_file = safe_path(deployment_id_file, existing=False)
        require(not deployment_id_file.exists() and deployment_id_file.parent.is_dir(),
                "Deployment ID output already exists or its directory is missing")
    if environment.get("GITHUB_OUTPUT"):
        require(safe_path(Path(environment["GITHUB_OUTPUT"])).is_file(), "Invalid runner output file")
    with path.open("rb") as stream:
        poms = verify_bundle(stream, path.name, manifest, version)
        commit = (commit_resolver or SOURCE.checkout_commit)(environment)
        require(commit == environment.get("GITHUB_SHA"), "Checkout differs from the canonical event commit")
        resolver = tag_resolver or SOURCE.remote_tag_commit
        SOURCE.verify_tag(version, commit, resolver)
        production_guard(environment, version)
        client = PortalClient(environment)
        if deployment_id is not None:
            print(f"Central deployment {deployment_id}: RESUMING", flush=True)
            if deployment_id_file is not None:
                with deployment_id_file.open("x", encoding="ascii", newline="\n") as identifier:
                    identifier.write(deployment_id + "\n")
            output_value(environment, "deployment_id", deployment_id)
            poll(client, deployment_id, interval, timeout)
            SOURCE.verify_tag(version, commit, resolver)
            production_guard(environment, version)
            output_value(environment, "deployment_state", "PUBLISHED")
            return deployment_id
        try:
            preflight(client, poms)
        except AlreadyPublished:
            SOURCE.verify_tag(version, commit, resolver)
            production_guard(environment, version)
            print("The exact Central version is already published; no upload was attempted", flush=True)
            output_value(environment, "deployment_state", "PUBLISHED")
            output_value(environment, "publication_status", "ALREADY_PUBLISHED")
            return "ALREADY_PUBLISHED"
        boundary = "prism-central-" + secrets.token_hex(24)
        stream.seek(0)
        prefix = next(multipart(io.BytesIO(b""), boundary))
        suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}",
                   "Content-Length": str(len(prefix) + os.fstat(stream.fileno()).st_size + len(suffix))}
        SOURCE.verify_tag(version, commit, resolver)
        production_guard(environment, version)
        code, body = client.request("POST", PORTAL + "/upload?publishingType=AUTOMATIC",
                                    data=multipart(stream, boundary), headers=headers, timeout=120)
        require(code == 201, "Central upload failed or is ambiguous; never blindly retry this upload")
        try:
            deployment = str(uuid.UUID(body.decode("ascii").strip()))
        except (ValueError, UnicodeError):
            raise ReleaseError("Central upload returned an invalid deployment identifier; inspect Portal before retrying") from None
    print(f"Central deployment {deployment}: PENDING", flush=True)
    if deployment_id_file is not None:
        with deployment_id_file.open("x", encoding="ascii", newline="\n") as stream:
            stream.write(deployment + "\n")
    output_value(environment, "deployment_id", deployment)
    poll(client, deployment, interval, timeout)
    SOURCE.verify_tag(version, commit, resolver)
    production_guard(environment, version)
    output_value(environment, "deployment_state", "PUBLISHED")
    return deployment


def main() -> int:
    """显式区分离线打包和有保护的生产上传。 / Explicitly separates offline bundling from guarded production upload."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pack = commands.add_parser("bundle", help="Validate staging and create an offline deterministic ZIP")
    pack.add_argument("--repository", type=Path, action="append", required=True)
    pack.add_argument("--output", type=Path, required=True)
    pack.add_argument("--version")
    upload = commands.add_parser("publish", help="Upload exactly one validated bundle and wait for PUBLISHED")
    upload.add_argument("--bundle", type=Path, required=True)
    upload.add_argument("--manifest", type=Path)
    upload.add_argument("--version")
    upload.add_argument("--deployment-id-file", type=Path)
    upload.add_argument("--deployment-id", help="Resume status ONLY for a saved deployment; never uploads")
    upload.add_argument("--poll-interval", type=float, default=10)
    upload.add_argument("--timeout", type=float, default=1800)
    arguments = parser.parse_args()
    try:
        version = release_version(arguments.version)
        if arguments.command == "bundle":
            bundle(arguments.repository, arguments.output, version)
            print("Validated deterministic Central bundle and SHA256 manifest created.")
        else:
            manifest = arguments.manifest or Path(str(arguments.bundle) + ".sha256")
            publish(arguments.bundle, manifest, version, os.environ, arguments.deployment_id_file,
                    arguments.poll_interval, arguments.timeout, arguments.deployment_id)
    except ReleaseError as error:
        print(f"Release tool failed: {error}", file=sys.stderr)
        return 1
    except Exception:
        print("Release tool failed; local or HTTP details suppressed for credential safety.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
