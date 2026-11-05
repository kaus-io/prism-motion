#!/usr/bin/env python3
"""对发布来源做只读、失败关闭的远端标签验证。 / Verifies remote release tags read-only and fails closed."""

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Mapping

ROOT = Path(__file__).resolve().parents[1]
REMOTE = "https://github.com/kaus-io/prism-motion.git"
SHA = re.compile(r"[0-9a-f]{40}")
ERROR = "Release source cannot be proven against the canonical remote tag"


def git_output(arguments: list[str], directory: Path, runner=subprocess.run) -> str:
    """忽略用户配置和凭据，只捕获受限 Git 输出。 / Ignores user configuration and credentials and captures bounded Git output."""
    environment = {name: os.environ[name] for name in ("PATH", "SYSTEMROOT", "WINDIR", "TMPDIR", "TEMP", "TMP")
                   if name in os.environ}
    environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_COUNT="0",
                       GIT_TERMINAL_PROMPT="0", GIT_CEILING_DIRECTORIES=str(directory), LC_ALL="C")
    try:
        result = runner(["git", "-c", "credential.helper=", "-c", "http.followRedirects=false", *arguments],
                        cwd=directory, env=environment, capture_output=True, text=True, encoding="ascii", timeout=45)
        if result.returncode or len(result.stdout) > 4096:
            raise ValueError(ERROR)
        return result.stdout
    except (OSError, UnicodeError, subprocess.SubprocessError):
        raise ValueError(ERROR) from None


def checkout_commit(environment: Mapping[str, str], runner=subprocess.run) -> str:
    """要求发布源码 checkout 与事件提交一致，允许发布工具来自新主干。 / Matches the source checkout to the event commit even when publishing tools come from newer main."""
    directory = Path(environment.get("RELEASE_SOURCE_DIRECTORY", str(ROOT))).absolute()
    if not directory.is_dir() or any(part.is_symlink() for part in (directory, *directory.parents)):
        raise ValueError(ERROR)
    commit = git_output(["rev-parse", "--verify", "HEAD^{commit}"], directory, runner).strip()
    if SHA.fullmatch(commit) is None or environment.get("GITHUB_SHA") != commit:
        raise ValueError(ERROR)
    return commit


def remote_tag_commit(version: str, runner=subprocess.run) -> str:
    """使用服务器递归剥离后的标签提交，兼容轻量标签。 / Uses server-peeled annotated tags, including nested tags, or lightweight tags."""
    if re.fullmatch(r"1\.0\.0-alpha(?:0[1-9]|[1-9][0-9])", version) is None:
        raise ValueError(ERROR)
    reference = "refs/tags/" + version
    output = git_output(["ls-remote", "--exit-code", "--", REMOTE, reference, reference + "^{}"],
                        Path(tempfile.gettempdir()), runner)
    refs = {}
    for line in output.splitlines():
        fields = line.split("\t")
        if (len(fields) != 2 or SHA.fullmatch(fields[0]) is None
                or fields[1] not in (reference, reference + "^{}") or fields[1] in refs):
            raise ValueError(ERROR)
        refs[fields[1]] = fields[0]
    if reference not in refs:
        raise ValueError(ERROR)
    return refs.get(reference + "^{}", refs[reference])


def verify_tag(version: str, commit: str, resolver=remote_tag_commit) -> None:
    """在每次写远端或声明成功前再次证明来源。 / Re-proves source before every remote write or success claim."""
    if SHA.fullmatch(commit) is None or resolver(version) != commit:
        raise ValueError(ERROR)
