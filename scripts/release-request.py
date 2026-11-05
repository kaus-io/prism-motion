#!/usr/bin/env python3
"""只校验 GitHub Release 已公开事件，不依赖 GitHub 发布令牌。 / Validates only published GitHub Release events without a GitHub publishing token."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Mapping

REPOSITORY = "kaus-io/prism-motion"


def publication_requested(environment: Mapping[str, str], version: str) -> bool:
    """只把与清单版本一致的已公开 Release 视为发布入口。 / Accepts only an already-public Release matching the manifest version as a publication trigger."""
    if environment.get("GITHUB_EVENT_NAME") != "release":
        return False
    if not (environment.get("GITHUB_REPOSITORY") == REPOSITORY
            and environment.get("GITHUB_REF") == f"refs/tags/v{version}"
            and environment.get("GITHUB_REF_TYPE") == "tag"
            and not environment.get("GITHUB_HEAD_REF")):
        raise ValueError("Central publication requires the canonical exact-version Release tag")
    try:
        event = json.loads(Path(environment["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    except (KeyError, OSError, UnicodeError, ValueError):
        raise ValueError("Cannot validate the published Release event payload") from None
    if not isinstance(event, dict) or not isinstance(event.get("action"), str):
        raise ValueError("Invalid Release event payload")
    if "repository" in event:
        repository = event["repository"]
        if not isinstance(repository, dict) or repository.get("full_name") != REPOSITORY:
            raise ValueError("Release event repository does not match the canonical repository")
    if event["action"] != "published":
        return False
    release = event.get("release")
    if not (isinstance(release, dict) and release.get("tag_name") == f"v{version}"
            and release.get("draft") is False):
        raise ValueError("Release event must identify the exact non-draft version tag")
    return True
