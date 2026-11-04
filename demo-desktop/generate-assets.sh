#!/usr/bin/env bash
# 生成演示/基准资产：尺寸 × 变体（透明/不透明）× 质量（VAP 码率 / KAP 像素格式）。
# Generates demo assets: size x variant (alpha/opaque) x quality (VAP bitrate / KAP pixel format).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/demo-data"
CLI="$ROOT/prism-motion-cli/build/bin/linuxX64/releaseExecutable/prism-motion.kexe"
FRAMES="${FRAMES:-24}"
SIZES="${SIZES:-128 256 512}"
VAP_BITRATES="${VAP_BITRATES:-1000 6000}"
KAP_FORMATS="${KAP_FORMATS:-rgba8 astc-8x8}"

[ -x "$CLI" ] || { echo "缺少 CLI：先运行 ./gradlew nativeCli"; exit 1; }
mkdir -p "$OUT"

for size in $SIZES; do
  for variant in tr op; do
    case $variant in tr) name=alpha ;; op) name=opaque ;; esac
    frames_dir="/tmp/prism-assets-$variant-$size"
    mkdir -p "$frames_dir"
    python3 - "$size" "$FRAMES" "$frames_dir" "$variant" <<'PY'
import sys, numpy as np, os
from PIL import Image
size, frames, out, variant = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
yy, xx = np.mgrid[0:size, 0:size]
for i in range(frames):
    r = ((xx * 255) // size).astype(np.uint8)
    g = ((yy * 255) // size).astype(np.uint8)
    b = np.full((size, size), i * 255 // frames, dtype=np.uint8)
    if variant == "tr":
        a = ((xx + yy + i * size // frames) % 256).astype(np.uint8)
        Image.fromarray(np.dstack([r, g, b, a]), "RGBA").save(f"{out}/f{i:03d}.png")
    else:
        Image.fromarray(np.dstack([r, g, b]), "RGB").save(f"{out}/f{i:03d}.png")
PY

    for bitrate in $VAP_BITRATES; do
      "$CLI" pack vap --frames "$frames_dir" --out "$OUT/$name-$size-b$bitrate.mp4" --fps 30 --bitrate "$bitrate"
    done
    for format in $KAP_FORMATS; do
      tag="${format//-}"
      "$CLI" pack kap --frames "$frames_dir" --out "$OUT/$name-$size-$tag.ktx2" --fps 30 --format "$format"
    done
  done
done

ls -lh "$OUT" | tail -n +2
