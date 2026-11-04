"""Maven 暂存检查器的离线测试。 / Offline tests for the Maven staging validator."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile


SCRIPT = Path(__file__).with_name("verify-maven-publications.py")
SPEC = importlib.util.spec_from_file_location("verify_maven_publications", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)
VERSION = "1.0.0-alpha01"


def checksums(path: Path) -> None:
    """更新测试文件的 Maven 校验和。 / Updates Maven checksums for a fixture file."""
    for algorithm in ("md5", "sha1"):
        Path(f"{path}.{algorithm}").write_text(
            hashlib.new(algorithm, path.read_bytes()).hexdigest(), encoding="ascii"
        )


def write_artifact(path: Path, contents: bytes, signed: bool = True) -> None:
    """写入离线测试产物，不执行签名操作。 / Writes an offline fixture artifact without performing signing."""
    path.write_bytes(contents)
    checksums(path)
    if signed:
        Path(f"{path}.asc").write_text("fixture-signature-presence-only", encoding="ascii")


def jar(sources: bool = False) -> bytes:
    """构建最小的有效测试 JAR。 / Creates a minimal valid fixture JAR."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("Example.kt" if sources else "META-INF/MANIFEST.MF", "fixture")
    return buffer.getvalue()


def fixture(repository: Path, signed: bool = True) -> None:
    """在操作系统临时目录中生成完整的测试仓库。 / Creates a complete fixture repository in an OS temporary directory."""
    for artifact, target in validator.PUBLICATIONS.items():
        directory = repository.joinpath(*validator.GROUP.split("."), artifact, VERSION)
        directory.mkdir(parents=True)
        stem = f"{artifact}-{VERSION}"
        extension = "aar" if target == "android" else "klib" if target in ("linuxx64", "mingwx64") else "jar"
        binary = directory / f"{stem}.{extension}"
        write_artifact(binary, jar() if extension == "jar" else b"fixture", signed)
        write_artifact(directory / f"{stem}-sources.jar",
                       jar(sources=artifact not in validator.EMPTY_COMMON_SOURCE_ROOTS), signed)
        write_artifact(directory / f"{stem}-javadoc.jar", jar(), signed)
        dependencies = []
        if artifact in ("prism-motion-engine-kap-jvm", "prism-motion-engine-vap-jvm"):
            dependencies.append((validator.GROUP, "prism-motion-native-loader-jvm", VERSION))
        sdk = {"prism-motion-engine-kap-android": "kap-compose",
               "prism-motion-engine-vap-android": "vap-compose-android"}.get(artifact)
        if sdk:
            dependencies.append((validator.GROUP, sdk, validator.SDK_VERSION))
        dependency_xml = "".join(
            f"<dependency><groupId>{group}</groupId><artifactId>{module}</artifactId>"
            f"<version>{version}</version></dependency>" for group, module, version in dependencies
        )
        pom = (
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            f"<groupId>{validator.GROUP}</groupId><artifactId>{artifact}</artifactId><version>{VERSION}</version>"
            "<name>fixture</name><description>fixture</description><url>https://example.com/prism</url>"
            "<licenses><license><name>MIT License</name><url>https://opensource.org/license/mit/</url>"
            "</license></licenses><developers><developer><id>fixture</id><name>fixture</name>"
            "</developer></developers><scm><url>https://example.com/prism</url>"
            "<connection>scm:git:https://example.com/prism.git</connection>"
            "<developerConnection>scm:git:ssh://git@example.com/prism.git</developerConnection></scm>"
            f"<dependencies>{dependency_xml}</dependencies></project>"
        )
        write_artifact(directory / f"{stem}.pom", pom.encode(), signed)
        root_artifact = validator.ROOT_ARTIFACTS[artifact]
        component = {"group": validator.GROUP, "module": root_artifact, "version": VERSION}
        if target:
            component["url"] = f"../../{root_artifact}/{VERSION}/{root_artifact}-{VERSION}.module"
        metadata = {
            "formatVersion": "1.1",
            "component": component,
            "variants": [{"name": "runtime", "files": [{
                "name": binary.name, "url": binary.name, "size": binary.stat().st_size,
                "sha1": hashlib.sha1(binary.read_bytes()).hexdigest(),
            }]}],
        }
        write_artifact(directory / f"{stem}.module", json.dumps(metadata).encode(), signed)


class PublicationValidationTest(unittest.TestCase):
    """覆盖完整性、坐标映射和签名门槛。 / Covers completeness, coordinate mapping and signature requirements."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="prism-maven-validator-test-")
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        fixture(self.repository)

    def artifact(self, module: str, suffix: str) -> Path:
        """定位某个测试 publication 文件。 / Locates a fixture publication file."""
        return self.repository.joinpath(*validator.GROUP.split("."), module, VERSION,
                                        f"{module}-{VERSION}{suffix}")

    def test_complete_linux_release(self) -> None:
        self.assertEqual(validator.verify(self.repository, VERSION), 39)

    def test_signatures_required_unless_explicitly_disabled(self) -> None:
        signature = Path(f"{self.artifact('prism-motion-core', '.pom')}.asc")
        signature.unlink()
        with self.assertRaisesRegex(validator.ValidationError, "Missing signature"):
            validator.verify(self.repository, VERSION)
        self.assertEqual(validator.verify(self.repository, VERSION, allow_unsigned=True), 39)

    def test_missing_native_publication_fails(self) -> None:
        self.artifact("prism-motion-format-mingwx64", ".pom").unlink()
        with self.assertRaisesRegex(validator.ValidationError, "Missing publications"):
            validator.verify(self.repository, VERSION)

    def test_unexpected_cli_publication_fails(self) -> None:
        (self.repository / "prism-motion-cli.pom").write_text("fixture", encoding="utf-8")
        with self.assertRaisesRegex(validator.ValidationError, "Unexpected publications"):
            validator.verify(self.repository, VERSION)

    def test_stale_loader_dependency_fails(self) -> None:
        path = self.artifact("prism-motion-engine-kap-jvm", ".pom")
        path.write_text(path.read_text().replace("prism-motion-native-loader-jvm", "utils-jvm-jvm"))
        checksums(path)
        with self.assertRaisesRegex(validator.ValidationError, "Stale utils-jvm"):
            validator.verify(self.repository, VERSION)

    def test_missing_loader_dependency_fails(self) -> None:
        path = self.artifact("prism-motion-engine-vap-jvm", ".pom")
        path.write_text(path.read_text().replace("prism-motion-native-loader-jvm", "jna"))
        checksums(path)
        with self.assertRaisesRegex(validator.ValidationError, "Missing renamed JVM loader"):
            validator.verify(self.repository, VERSION)

    def test_wrong_sdk_namespace_fails(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "Wrong SDK coordinate"):
            validator.internal_reference("legacy.group", "kap-compose", VERSION, VERSION)

    def test_sdk_android_pom_uses_platform_variant(self) -> None:
        path = self.artifact("prism-motion-engine-vap-android", ".pom")
        path.write_text(path.read_text().replace("vap-compose-android", "vap-compose"))
        checksums(path)
        with self.assertRaisesRegex(validator.ValidationError, "Missing SDK dependency"):
            validator.verify(self.repository, VERSION)

    def test_wrong_sdk_target_version_fails(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "Wrong SDK coordinate"):
            validator.internal_reference(validator.GROUP, "vap-compose-android", "0.0.1-LOCAL", VERSION)

    def test_pom_metadata_is_required(self) -> None:
        path = self.artifact("prism-motion-core", ".pom")
        path.write_text(path.read_text().replace("<name>MIT License</name>", "<name></name>"))
        checksums(path)
        with self.assertRaisesRegex(validator.ValidationError, "Missing m:licenses"):
            validator.verify(self.repository, VERSION)

    def test_stale_gradle_dependency_fails(self) -> None:
        path = self.artifact("prism-motion-engine-kap-jvm", ".module")
        metadata = json.loads(path.read_text())
        metadata["variants"][0]["dependencies"] = [{
            "group": validator.GROUP, "module": "utils-jvm-jvm", "version": {"requires": VERSION}
        }]
        path.write_text(json.dumps(metadata))
        checksums(path)
        with self.assertRaisesRegex(validator.ValidationError, "Stale utils-jvm"):
            validator.verify(self.repository, VERSION)

    def test_missing_metadata_redirect_fails(self) -> None:
        path = self.artifact("prism-motion-core", ".module")
        metadata = json.loads(path.read_text())
        metadata["variants"].append({"name": "jvm", "available-at": {
            "group": validator.GROUP, "module": "prism-motion-core-jvm", "version": VERSION,
            "url": "missing.module",
        }})
        path.write_text(json.dumps(metadata))
        checksums(path)
        with self.assertRaisesRegex(validator.ValidationError, "Missing metadata target"):
            validator.verify(self.repository, VERSION)

    def test_target_metadata_must_link_its_own_root(self) -> None:
        path = self.artifact("prism-motion-core-jvm", ".module")
        metadata = json.loads(path.read_text())
        metadata["component"]["url"] = (
            f"../../prism-motion-format/{VERSION}/prism-motion-format-{VERSION}.module"
        )
        path.write_text(json.dumps(metadata))
        checksums(path)
        with self.assertRaisesRegex(validator.ValidationError, "Wrong root component redirect"):
            validator.verify(self.repository, VERSION)

    def test_tampered_checksum_fails(self) -> None:
        path = self.artifact("prism-motion-core", ".jar")
        path.write_bytes(jar(sources=True))
        with self.assertRaisesRegex(validator.ValidationError, "Checksum mismatch"):
            validator.verify(self.repository, VERSION)

    def test_duplicate_central_directory_names_fail_in_every_jar_category(self) -> None:
        for module, suffix, name in (
            ("prism-motion-core", ".jar", "Example.class"),
            ("prism-motion-core", "-sources.jar", "commonMain/Example.kt"),
            ("prism-motion-core", "-javadoc.jar", "index.html"),
            ("prism-motion-native-loader", "-sources.jar", "META-INF/MANIFEST.MF"),
        ):
            with self.subTest(module=module, suffix=suffix):
                path = self.artifact(module, suffix)
                original = path.read_bytes()
                buffer = io.BytesIO()
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(buffer, "w") as archive:
                        archive.writestr(name, "first")
                        archive.writestr(name, "second")
                with zipfile.ZipFile(io.BytesIO(buffer.getvalue())) as archive:
                    self.assertEqual(archive.namelist(), [name, name])
                write_artifact(path, buffer.getvalue())
                with self.assertRaisesRegex(validator.ValidationError, "Duplicate JAR entry names"):
                    validator.verify(self.repository, VERSION)
                write_artifact(path, original)

    def test_duplicate_central_records_sharing_one_local_header_are_rejected(self) -> None:
        original = jar(sources=True)
        end_offset = original.rfind(b"PK\x05\x06")
        end_record = list(struct.unpack("<4s4H2LH", original[end_offset:end_offset + 22]))
        directory_size, directory_offset = end_record[5:7]
        directory = original[directory_offset:directory_offset + directory_size]
        self.assertEqual(end_record[3:5], [1, 1])
        end_record[3:5] = [2, 2]
        end_record[5] = directory_size * 2
        duplicate = original[:directory_offset] + directory + directory + struct.pack("<4s4H2LH", *end_record)
        with zipfile.ZipFile(io.BytesIO(duplicate)) as archive:
            entries = archive.infolist()
            self.assertEqual(entries[0].filename, entries[1].filename)
            self.assertEqual(entries[0].header_offset, entries[1].header_offset)
        write_artifact(self.artifact("prism-motion-utils-linuxx64", "-sources.jar"), duplicate)
        with self.assertRaisesRegex(validator.ValidationError, "Duplicate JAR entry names"):
            validator.verify(self.repository, VERSION)

    def test_identical_contents_at_distinct_source_paths_are_not_duplicates(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("first/Example.kt", "identical content")
            archive.writestr("second/Example.kt", "identical content")
        write_artifact(self.artifact("prism-motion-core", "-sources.jar"), buffer.getvalue())
        self.assertEqual(validator.verify(self.repository, VERSION), 39)

    def test_empty_common_engine_and_loader_roots_are_valid(self) -> None:
        self.assertEqual(validator.EMPTY_COMMON_SOURCE_ROOTS, {
            "prism-motion-engine-kap", "prism-motion-engine-vap", "prism-motion-native-loader",
        })
        for module in validator.EMPTY_COMMON_SOURCE_ROOTS:
            write_artifact(self.artifact(module, "-sources.jar"), jar())
        self.assertEqual(validator.verify(self.repository, VERSION), 39)

    def test_empty_source_allowlist_does_not_exempt_platforms_or_other_roots(self) -> None:
        for module in ("prism-motion-native-loader-jvm", "prism-motion-engine-kap-android",
                       "prism-motion-engine-kap-jvm", "prism-motion-engine-vap-android",
                       "prism-motion-engine-vap-jvm", "prism-motion-core"):
            with self.subTest(module=module):
                path = self.artifact(module, "-sources.jar")
                write_artifact(path, jar())
                with self.assertRaisesRegex(validator.ValidationError, "Sources jar has no Kotlin sources"):
                    validator.verify(self.repository, VERSION)
                write_artifact(path, jar(sources=True))


if __name__ == "__main__":
    unittest.main()
