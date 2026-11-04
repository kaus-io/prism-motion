#!/usr/bin/env bash
# 在已连接的设备上跑 Android 基准：安装 bench APK → 逐资产执行 → 汇总 CSV。
# Runs the Android benchmark on a connected device and collects one CSV.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
PKG=com.zxhhyj.prism.motion.bench.android
ASSETS="${ASSETS:-alpha-128-astc8x8 alpha-128-rgba8 alpha-128-b1000 alpha-128-b6000 alpha-256-astc8x8 alpha-256-rgba8 alpha-256-b1000 alpha-256-b6000}"
OUT="${OUT:-$ROOT/bench-android/build/reports/bench.csv}"

"${ADB[@]}" wait-for-device
"$ROOT/gradlew" -p "$ROOT" :bench-android:assembleDebug
"${ADB[@]}" install -r "$ROOT/bench-android/build/outputs/apk/debug/bench-android-debug.apk" >/dev/null

mkdir -p "$(dirname "$OUT")"
printf '%s\n' "asset,engine,variant,size,quality,asset_bytes,count,frames,avg_ms,p95_ms,p99_ms,max_ms,jank_ratio,fps_std,fps,pss_start_mb,pss_end_mb,pss_max_mb,cpu_percent,first_frame_ms" > "$OUT"
for asset in $ASSETS; do
  "${ADB[@]}" shell am force-stop "$PKG"
  "${ADB[@]}" shell rm -f "/sdcard/Android/data/$PKG/files/bench.csv"
  "${ADB[@]}" logcat -c
  "${ADB[@]}" shell am start -n "$PKG/.BenchActivity" --es only "$asset" >/dev/null
  for _ in $(seq 1 30); do
    sleep 2
    "${ADB[@]}" logcat -d -s prism-bench:I 2>/dev/null | grep -q BENCH_DONE && break
  done
  "${ADB[@]}" shell "cat /sdcard/Android/data/$PKG/files/bench.csv" 2>/dev/null | tail -n +2 >> "$OUT"
  echo "done $asset"
done

column -s, -t "$OUT"
echo
echo "CSV: $OUT"
