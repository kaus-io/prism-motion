#!/usr/bin/env bash
# 在已连接的 Android 设备上跑演示基准：安装 APK → bench 模式播放 → 拉取 CSV。
# Runs the demo benchmark on a connected device: install, play in bench mode, pull the CSV.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

"${ADB[@]}" wait-for-device
"$ROOT/gradlew" -p "$ROOT" :demo-android:assembleDebug
"${ADB[@]}" install -r "$ROOT/demo-android/build/outputs/apk/debug/demo-android-debug.apk"

"${ADB[@]}" shell am force-stop com.zxhhyj.prism.motion.demo.android
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am start -n com.zxhhyj.prism.motion.demo.android/.MainActivity --ez bench true

echo "等待 bench 完成（全部资产 × 1/8/16，约 10 分钟）…"
until "${ADB[@]}" logcat -d -s prism-bench:I | grep -q BENCH_DONE; do sleep 10; done

REMOTE="$("${ADB[@]}" logcat -d -s prism-bench:I | grep BENCH_DONE | tail -1 | sed 's/.*BENCH_DONE //')"
OUT="$ROOT/demo-android/build/reports/bench.csv"
mkdir -p "$(dirname "$OUT")"
"${ADB[@]}" pull "$REMOTE" "$OUT"
echo
column -s, -t "$OUT"
echo
echo "CSV: $OUT"
