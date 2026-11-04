#!/usr/bin/env bash
# 统一基准入口；使用事件通知而不是 sleep 轮询。 / Bench entry; waits for events rather than sleep polling.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/run-android-bench.py" "$@"
