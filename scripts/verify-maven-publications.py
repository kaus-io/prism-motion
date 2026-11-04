#!/usr/bin/env python3
"""验证 Linux 首发 Maven 暂存产物，不调用 Gradle 或网络。 / Validates Linux-release Maven staging without Gradle or network calls."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile


GROUP = "com.kaus-io"
SDK_VERSION = "1.0.0-alpha01"
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
}
PUBLICATIONS = {
    f"{artifact}-{target}" if target else artifact: target
    for artifact, targets in TARGETS.items()
    for target in targets
}
ROOT_ARTIFACTS = {
    f"{artifact}-{target}" if target else artifact: artifact
    for artifact, targets in TARGETS.items()
    for target in targets
}
# 这些模块仅在平台源码集中实现，共享源码集合法为空。 / These modules implement only platform source sets; their common source sets are legitimately empty.
EMPTY_COMMON_SOURCE_ROOTS = frozenset({
    "prism-motion-engine-kap",
    "prism-motion-engine-vap",
    "prism-motion-native-loader",
})
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class ValidationError(ValueError):
    """发布产物不符合约定。 / Publication artifacts violate the release contract."""


def require(condition: bool, message: str) -> None:
    """提供可定位的验证失败。 / Reports a contextual validation failure."""
    if not condition:
        raise ValidationError(message)


def internal_reference(group: str, artifact: str, version: str, expected_version: str) -> None:
    """拒绝内部旧坐标，并固定外部 SDK 的已约定版本。 / Rejects stale internal coordinates and enforces agreed SDK versions."""
    if artifact == "utils-jvm" or artifact.startswith("utils-jvm-"):
        raise ValidationError(f"Stale utils-jvm coordinate: {group}:{artifact}:{version}")
    if artifact.startswith("prism-motion-"):
        require(group == GROUP, f"Wrong internal group: {group}:{artifact}:{version}")
        require(artifact in PUBLICATIONS, f"Unexpected internal artifact: {artifact}")
        require(version == expected_version, f"Wrong internal version: {artifact}:{version}")
    if artifact in ("kap-compose", "vap-compose", "vap-compose-android", "vap-compose-jvm"):
        require(group == GROUP and version == SDK_VERSION,
                f"Wrong SDK coordinate: {group}:{artifact}:{version}")


def repository_file(repository: Path, parent: Path, url: str) -> Path:
    """仅接受暂存仓库内的相对文件引用。 / Accepts only relative file references contained in the staging repository."""
    require(":" not in url and not url.startswith(("/", "\\")), f"Nonlocal metadata URL: {url}")
    target = (parent / url).resolve()
    require(target.is_relative_to(repository), f"Metadata escapes staging repository: {url}")
    require(target.is_file(), f"Missing metadata target: {target}")
    return target


def check_module(repository: Path, path: Path, artifact: str, version: str) -> None:
    """检查 Gradle 元数据中的 GAV、依赖和重定向。 / Checks Gradle metadata GAVs, dependencies and redirects."""
    metadata = json.loads(path.read_text(encoding="utf-8"))
    component = metadata.get("component", {})
    component_artifact = ROOT_ARTIFACTS[artifact] if "url" in component else artifact
    require((component.get("group"), component.get("module"), component.get("version")) ==
            (GROUP, component_artifact, version), f"Wrong component coordinates: {path}")
    if "url" in component:
        target = repository_file(repository, path.parent, component["url"])
        expected = repository.joinpath(*GROUP.split("."), component_artifact, version,
                                       f"{component_artifact}-{version}.module")
        require(target == expected, f"Wrong root component redirect: {path}")
    for variant in metadata.get("variants", []):
        for dependency in variant.get("dependencies", []) + variant.get("dependencyConstraints", []):
            constraint = dependency.get("version", {})
            dependency_version = constraint if isinstance(constraint, str) else (
                constraint.get("requires") or constraint.get("strictly") or constraint.get("prefers") or ""
            )
            internal_reference(dependency.get("group", ""), dependency.get("module", ""),
                               dependency_version, version)
        available = variant.get("available-at")
        if available:
            internal_reference(available.get("group", ""), available.get("module", ""),
                               available.get("version", ""), version)
            repository_file(repository, path.parent, available["url"])
        for artifact_file in variant.get("files", []):
            target = repository_file(repository, path.parent, artifact_file["url"])
            if "size" in artifact_file:
                require(target.stat().st_size == artifact_file["size"], f"Metadata size mismatch: {target}")
            for algorithm in ("sha512", "sha256", "sha1", "md5"):
                if algorithm in artifact_file:
                    actual = hashlib.new(algorithm, target.read_bytes()).hexdigest()
                    require(actual == artifact_file[algorithm], f"Metadata checksum mismatch: {target}")


def check_pom(path: Path, artifact: str, version: str) -> None:
    """检查 Central 必需 POM 字段和项目依赖映射。 / Checks Central-required POM fields and project dependency mapping."""
    pom = ET.parse(path).getroot()

    def text(field: str) -> str:
        return (pom.findtext(field, namespaces=NS) or "").strip()

    require((text("m:groupId"), text("m:artifactId"), text("m:version")) ==
            (GROUP, artifact, version), f"Wrong POM coordinates: {path}")
    for field in ("m:name", "m:description", "m:url", "m:licenses/m:license/m:name",
                  "m:licenses/m:license/m:url", "m:developers/m:developer/m:id",
                  "m:developers/m:developer/m:name", "m:scm/m:url", "m:scm/m:connection",
                  "m:scm/m:developerConnection"):
        require(bool(text(field)), f"Missing {field}: {path}")
    require(text("m:licenses/m:license/m:name") == "MIT License", f"Expected MIT license: {path}")
    dependencies = set()
    for dependency in pom.findall("m:dependencies/m:dependency", NS):
        group = (dependency.findtext("m:groupId", namespaces=NS) or "").strip()
        module = (dependency.findtext("m:artifactId", namespaces=NS) or "").strip()
        dependency_version = (dependency.findtext("m:version", namespaces=NS) or "").strip()
        internal_reference(group, module, dependency_version, version)
        dependencies.add((group, module, dependency_version))
    if artifact in ("prism-motion-engine-kap-jvm", "prism-motion-engine-vap-jvm"):
        require((GROUP, "prism-motion-native-loader-jvm", version) in dependencies,
                f"Missing renamed JVM loader project dependency: {path}")
    sdk = {"prism-motion-engine-kap-android": "kap-compose",
           "prism-motion-engine-vap-android": "vap-compose-android"}.get(artifact)
    if sdk:
        require((GROUP, sdk, SDK_VERSION) in dependencies, f"Missing SDK dependency: {path}")


def verify(repository: Path, version: str, allow_unsigned: bool = False) -> int:
    """检查完整的 39 个 Linux 首发 publication。 / Checks all 39 publications in the Linux first release."""
    repository = repository.resolve()
    require(repository.is_dir(), f"Repository does not exist: {repository}")
    group_path = repository.joinpath(*GROUP.split("."))
    expected_poms = {
        group_path / artifact / version / f"{artifact}-{version}.pom"
        for artifact in PUBLICATIONS
    }
    actual_poms = set(repository.rglob("*.pom"))
    missing = sorted(expected_poms - actual_poms)
    unexpected = sorted(actual_poms - expected_poms)
    require(not missing, "Missing publications: " + ", ".join(str(path) for path in missing))
    require(not unexpected, "Unexpected publications (CLI/apps/bench/other versions excluded): " +
            ", ".join(str(path) for path in unexpected))
    for artifact, target in PUBLICATIONS.items():
        directory = group_path / artifact / version
        stem = f"{artifact}-{version}"
        extension = "aar" if target == "android" else "klib" if target in ("linuxx64", "mingwx64") else "jar"
        required = [directory / f"{stem}.{extension}", directory / f"{stem}.pom",
                    directory / f"{stem}.module", directory / f"{stem}-sources.jar",
                    directory / f"{stem}-javadoc.jar"]
        for path in required:
            require(path.is_file() and path.stat().st_size > 0, f"Missing or empty artifact: {path}")
            if path.suffix == ".jar":
                require(zipfile.is_zipfile(path), f"Invalid jar: {path}")
                with zipfile.ZipFile(path) as archive:
                    names = archive.namelist()
                    require(len(names) == len(set(names)), f"Duplicate JAR entry names: {path}")
                    if path.name.endswith("-sources.jar") and artifact not in EMPTY_COMMON_SOURCE_ROOTS:
                        require(any(name.endswith(".kt") for name in names),
                                f"Sources jar has no Kotlin sources: {path}")
            for algorithm in ("md5", "sha1"):
                checksum = Path(f"{path}.{algorithm}")
                require(checksum.is_file(), f"Missing checksum: {checksum}")
                actual = hashlib.new(algorithm, path.read_bytes()).hexdigest()
                require(checksum.read_text(encoding="ascii").strip() == actual,
                        f"Checksum mismatch: {checksum}")
            if not allow_unsigned:
                signature = Path(f"{path}.asc")
                require(signature.is_file() and signature.stat().st_size > 0,
                        f"Missing signature (local-only --allow-unsigned may bypass): {signature}")
        check_pom(directory / f"{stem}.pom", artifact, version)
        check_module(repository, directory / f"{stem}.module", artifact, version)
    return len(PUBLICATIONS)


def project_version() -> str:
    """使用仓库版本单一来源作为默认值。 / Uses the repository's single version authority as the default."""
    properties = Path(__file__).resolve().parents[1] / "gradle.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "version":
            return value.strip()
    raise ValidationError("No version property in gradle.properties")


def main() -> int:
    """提供仅检查本地产物的命令入口。 / Provides a local-artifact-only command entry point."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", default=None)
    parser.add_argument("--allow-unsigned", action="store_true", help="For explicitly unsigned local staging only")
    arguments = parser.parse_args()
    try:
        version = arguments.version or project_version()
        count = verify(arguments.repository, version, arguments.allow_unsigned)
    except (ValidationError, OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Maven publication validation failed: {error}", file=sys.stderr)
        return 1
    print(f"Validated {count} Prism publications at {GROUP}:*:{version} (Android/JVM/linuxX64/mingwX64).")
    if arguments.allow_unsigned:
        print("Unsigned local staging validation only; not proof of Central release readiness.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
