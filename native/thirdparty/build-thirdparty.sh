#!/usr/bin/env bash
# 构建 CLI 依赖的第三方 C 库：stb_image（PNG 解码）与 OpenH264（H.264 编码）。
# 用法：./thirdparty/build-thirdparty.sh [linux-x86_64]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="${1:-linux-x86_64}"
TP="$ROOT/thirdparty"
SRC="$TP/src"
mkdir -p "$SRC" "$TP/include" "$TP/include/openh264" "$TP/lib/$PLATFORM"

STB_COMMIT="f4a71b13373436a2866c5d68f8f80ac6f0bc1ffe" # stb master 快照，v2.30
OPENH264_VERSION="2.6.0"

if [ ! -f "$TP/include/stb_image.h" ]; then
  echo "==> 下载 stb_image.h"
  curl -fsSL -o "$TP/include/stb_image.h" \
    "https://raw.githubusercontent.com/nothings/stb/$STB_COMMIT/stb_image.h"
fi

if [ ! -f "$TP/lib/$PLATFORM/libstb.a" ]; then
  echo "==> 编译 libstb.a"
  cat > "$SRC/stb_impl.c" <<'C'
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#include "stb_image.h"
C
  cc -O2 -fPIC -I"$TP/include" -c "$SRC/stb_impl.c" -o "$SRC/stb_impl.o"
  ar rcs "$TP/lib/$PLATFORM/libstb.a" "$SRC/stb_impl.o"
fi

if [ ! -f "$TP/lib/$PLATFORM/libopenh264.a" ]; then
  if ! command -v nasm >/dev/null && [ -z "${NASM_DIR:-}" ]; then
    echo "缺少 nasm：OpenH264 的 x86 汇编需要它。装好后重跑，或设置 NASM_DIR 指向含 nasm 的目录。" >&2
    exit 1
  fi
  [ -n "${NASM_DIR:-}" ] && export PATH="$NASM_DIR:$PATH"
  echo "==> 编译 OpenH264 $OPENH264_VERSION"
  curl -fsSL -o "$SRC/openh264.tar.gz" \
    "https://github.com/cisco/openh264/archive/refs/tags/v${OPENH264_VERSION}.tar.gz"
  tar -xzf "$SRC/openh264.tar.gz" -C "$SRC"
  ( cd "$SRC/openh264-${OPENH264_VERSION}" && make -j"$(nproc)" )
  cp "$SRC/openh264-${OPENH264_VERSION}/libopenh264.a" "$TP/lib/$PLATFORM/"
  cp "$SRC/openh264-${OPENH264_VERSION}/codec/api/wels/"*.h "$TP/include/openh264/"
fi

if [ ! -f "$TP/lib/$PLATFORM/libprismcodec.a" ]; then
  echo "==> 编译 libprismcodec.a（OpenH264 的 C 封装）"
  cc -O2 -fPIC -I"$TP/shim" -I"$TP/include/openh264" -c "$TP/shim/prism_codec.c" -o "$SRC/prism_codec.o"
  ar rcs "$TP/lib/$PLATFORM/libprismcodec.a" "$SRC/prism_codec.o"
fi

echo "完成：$TP/lib/$PLATFORM"
ls -lh "$TP/lib/$PLATFORM"
