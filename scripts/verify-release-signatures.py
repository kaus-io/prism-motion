#!/usr/bin/env python3
"""在临时密钥环中验证实际暂存签名，不输出私钥。 / Verifies staged signatures in an ephemeral keyring without logging private keys."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def verify(repositories: list[Path], public_key: Path) -> int:
    """仅在内存中转换私钥包，临时密钥环只接收公钥。 / Converts secret packets in memory and imports only public packets into the temporary keyring."""
    key = os.environ.get("ORG_GRADLE_PROJECT_signingInMemoryKey", "")
    if not key.startswith("-----BEGIN PGP PRIVATE KEY BLOCK-----"):
        raise ValueError("Prepared in-memory signing key is missing")
    if public_key.exists():
        raise ValueError("Public-key export destination already exists")
    with tempfile.TemporaryDirectory(prefix="prism-signature-verification-") as directory:
        home = Path(directory)
        home.chmod(0o700)
        base = ["gpg", "--homedir", str(home), "--batch", "--no-tty"]
        try:
            converted = subprocess.run(base + ["--dry-run", "--armor", "--import-options", "import-export", "--import"],
                                       input=key.encode(), capture_output=True, timeout=60)
            if converted.returncode or not converted.stdout.startswith(b"-----BEGIN PGP PUBLIC KEY BLOCK-----"):
                raise ValueError("Cannot derive public release verification key")
            packets = subprocess.run(base + ["--list-packets"], input=converted.stdout, capture_output=True, timeout=60)
            if packets.returncode or b"secret key packet" in packets.stdout or b"secret sub key packet" in packets.stdout:
                raise ValueError("Verification key contains non-public packets")
            private_directory = home / "private-keys-v1.d"
            if private_directory.exists() and any(private_directory.iterdir()):
                raise ValueError("Public conversion persisted private key material")
            imported = subprocess.run(base + ["--import"], input=converted.stdout, capture_output=True, timeout=60)
            if imported.returncode:
                raise ValueError("Cannot import public release verification key")
            if private_directory.exists() and any(private_directory.iterdir()):
                raise ValueError("Public import persisted private key material")
            count = 0
            for repository in repositories:
                root = repository.resolve(strict=True)
                if not root.is_dir():
                    raise ValueError("Staging repository is not a directory")
                for directory_path, subdirectories, files in os.walk(root, followlinks=False):
                    current = Path(directory_path)
                    if any((current / entry).is_symlink() for entry in subdirectories + files):
                        raise ValueError("Staging repository contains symlinks")
                    for filename in files:
                        if not filename.endswith(".asc"):
                            continue
                        signature = current / filename
                        artifact = signature.with_suffix("")
                        if not artifact.is_file() or not artifact.resolve().is_relative_to(root):
                            raise ValueError("Detached signature has no local artifact")
                        checked = subprocess.run(base + ["--verify", str(signature), str(artifact)], capture_output=True, timeout=60)
                        if checked.returncode:
                            raise ValueError(f"Invalid release signature for {artifact.name}")
                        count += 1
            if not count:
                raise ValueError("No release signatures were found")
            exported = subprocess.run(base + ["--armor", "--export"], capture_output=True, timeout=60)
            if exported.returncode or not exported.stdout.startswith(b"-----BEGIN PGP PUBLIC KEY BLOCK-----"):
                raise ValueError("Cannot export public verification key")
            public_key.parent.mkdir(parents=True, exist_ok=True)
            public_key.write_bytes(exported.stdout)
            return count
        finally:
            subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"], capture_output=True, timeout=15)


def main() -> int:
    """所有错误都避免包含原始 GPG 输出或密钥内容。 / Errors never include raw GPG output or key material."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, action="append", required=True)
    parser.add_argument("--public-key-output", type=Path, required=True)
    args = parser.parse_args()
    try:
        count = verify(args.repository, args.public_key_output)
    except (OSError, ValueError, subprocess.SubprocessError):
        print("Release signature verification failed", file=sys.stderr)
        return 1
    print(f"Verified {count} detached release signatures")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
