#!/usr/bin/env python3
"""仅把发布密钥写入 runner 环境文件。 / Writes release secrets only to the runner environment file."""

from __future__ import annotations

import base64
import binascii
import os
from pathlib import Path
import re
import secrets
import stat
import sys
from typing import Mapping, TextIO


SECRET_NAMES = (
    "GPG_KEY_CONTENTS", "MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD",
    "SIGNING_KEY_ID", "SIGNING_PASSWORD",
)
HEADER = "-----BEGIN PGP PRIVATE KEY BLOCK-----"
FOOTER = "-----END PGP PRIVATE KEY BLOCK-----"


class SigningError(ValueError):
    """不包含任何密钥内容的错误。 / An error that never contains secret material."""


def normalize_key(value: str) -> str:
    """接受 ASCII armor 或其 base64 编码。 / Accepts ASCII armor or its base64 encoding."""
    try:
        key = value.strip().replace("\r\n", "\n").replace("\r", "\n")
        if not key.startswith(HEADER):
            key = base64.b64decode("".join(key.split()), validate=True).decode("ascii")
            key = key.strip().replace("\r\n", "\n").replace("\r", "\n")
        key.encode("ascii")
        lines = key.split("\n")
        if len(lines) < 4 or lines[0] != HEADER or lines[-1] != FOOTER:
            raise SigningError("Invalid signing configuration")
        if any(ord(char) < 32 and char != "\n" for char in key):
            raise SigningError("Invalid signing configuration")
        interior = lines[1:-1]
        separator = interior.index("")
        if any(not re.fullmatch(r"(?:Version|Comment): [ -~]+", line)
               for line in interior[:separator]):
            raise SigningError("Invalid signing configuration")
        payload = interior[separator + 1:]
        if payload and re.fullmatch(r"=[A-Za-z0-9+/]{4}", payload[-1]):
            payload = payload[:-1]
        if not payload or any(not re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", line) for line in payload):
            raise SigningError("Invalid signing configuration")
        if not base64.b64decode("".join(payload), validate=True):
            raise SigningError("Invalid signing configuration")
        return key + "\n"
    except (ValueError, UnicodeError, binascii.Error):
        raise SigningError("Invalid signing configuration") from None


def normalize_key_id(value: str) -> str:
    """把短 ID、长 ID 或指纹转换为 Gradle 的八位 ID。 / Converts a short ID, long ID or fingerprint to Gradle's eight-digit ID."""
    key_id = value.strip()
    if key_id.lower().startswith("0x"):
        key_id = key_id[2:]
    if not re.fullmatch(r"(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{16}|[0-9a-fA-F]{40})", key_id):
        raise SigningError("Invalid signing configuration")
    return key_id[-8:].upper()


def mask(value: str, output: TextIO) -> None:
    """转义工作流命令，防止值注入命令。 / Escapes workflow commands to prevent value injection."""
    escaped = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::add-mask::{escaped}", file=output)


def prepare(environment: Mapping[str, str], output: TextIO) -> None:
    """验证后原子追加五个 Gradle 环境属性。 / Validates then appends five Gradle environment properties."""
    if any(not environment.get(name, "").strip() for name in SECRET_NAMES):
        raise SigningError("Invalid signing configuration")
    if any("\x00" in environment[name] for name in SECRET_NAMES):
        raise SigningError("Invalid signing configuration")
    key = normalize_key(environment["GPG_KEY_CONTENTS"])
    key_id = normalize_key_id(environment["SIGNING_KEY_ID"])
    values = {
        "ORG_GRADLE_PROJECT_signingInMemoryKey": key,
        "ORG_GRADLE_PROJECT_signingInMemoryKeyId": key_id,
        "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword": environment["SIGNING_PASSWORD"],
        "ORG_GRADLE_PROJECT_mavenCentralUsername": environment["MAVEN_CENTRAL_USERNAME"],
        "ORG_GRADLE_PROJECT_mavenCentralPassword": environment["MAVEN_CENTRAL_PASSWORD"],
    }
    env_file = Path(environment.get("GITHUB_ENV", ""))
    workspace = Path(__file__).resolve().parents[1]
    if (not env_file.is_absolute() or env_file.resolve().is_relative_to(workspace)
            or any(part.is_symlink() for part in (env_file, *env_file.parents))):
        raise SigningError("Invalid runner environment file")
    if not env_file.is_file():
        raise SigningError("Invalid runner environment file")
    if environment.get("GITHUB_ACTIONS") == "true":
        for value in dict.fromkeys([*(environment[name] for name in SECRET_NAMES), *values.values()]):
            mask(value, output)
    records = []
    for name, value in values.items():
        delimiter = "PRISM_SECRET_" + secrets.token_hex(24)
        while delimiter in value:
            delimiter = "PRISM_SECRET_" + secrets.token_hex(24)
        records.append(f"{name}<<{delimiter}\n{value}\n{delimiter}\n")
    descriptor = os.open(env_file, os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise SigningError("Invalid runner environment file")
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "a", encoding="utf-8", newline="\n", closefd=False) as stream:
            stream.write("".join(records))
    finally:
        os.close(descriptor)


def main() -> int:
    """不接受命令行密钥，也不泄露异常内容。 / Accepts no command-line secrets and never exposes exception details."""
    try:
        if len(sys.argv) != 1:
            raise SigningError("Invalid signing configuration")
        prepare(os.environ, sys.stdout)
    except Exception:
        print("Release signing preparation failed; check secret presence and format.", file=sys.stderr)
        return 1
    print("Release signing environment prepared.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
