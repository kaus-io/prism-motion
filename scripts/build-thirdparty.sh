#!/usr/bin/env bash
# 构建 CLI 依赖的第三方 C 库：stb_image（PNG 解码）与 OpenH264（H.264 编码）。
# 用法：./scripts/build-thirdparty.sh [linux-x86_64]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="${1:-linux-x86_64}"
TP="$ROOT/thirdparty"
SRC="$TP/src"
mkdir -p "$SRC" "$TP/include" "$TP/include/openh264" "$TP/lib/$PLATFORM"

UPSTREAM="$TP/upstream"
if [ ! -d "$UPSTREAM/openh264/codec" ] || [ ! -f "$UPSTREAM/stb/stb_image.h" ]; then
  echo "缺少上游源码。先执行：git submodule update --init --recursive" >&2
  exit 1
fi

if [ ! -f "$TP/lib/$PLATFORM/libstb.a" ]; then
  echo "==> 编译 libstb.a"
  cat > "$SRC/stb_impl.c" <<'C'
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#include "stb_image.h"
C
  cc -O2 -fPIC -I"$UPSTREAM/stb" -c "$SRC/stb_impl.c" -o "$SRC/stb_impl.o"
  ar rcs "$TP/lib/$PLATFORM/libstb.a" "$SRC/stb_impl.o"
fi

if [ ! -f "$TP/lib/$PLATFORM/libopenh264.a" ]; then
  if ! command -v nasm >/dev/null && [ -z "${NASM_DIR:-}" ]; then
    echo "缺少 nasm：OpenH264 的 x86 汇编需要它。装好后重跑，或设置 NASM_DIR 指向含 nasm 的目录。" >&2
    exit 1
  fi
  [ -n "${NASM_DIR:-}" ] && export PATH="$NASM_DIR:$PATH"
  echo "==> 编译 OpenH264（submodule 源码）"
  ( cd "$UPSTREAM/openh264" && make -j"$(nproc)" )
  cp "$UPSTREAM/openh264/libopenh264.a" "$TP/lib/$PLATFORM/"
  cp "$UPSTREAM/openh264/codec/api/wels/"*.h "$TP/include/openh264/"
fi

if [ ! -f "$TP/native/$PLATFORM/libprismdecode.so" ]; then
  echo "==> 编译 libprismdecode.so（OpenH264 解码器 + JVM/native 共用）"
  mkdir -p "$TP/native/$PLATFORM"
  # 复制带 soname 的共享库，并建一个不带版本号的软链供链接使用。
  cp "$UPSTREAM/openh264/libopenh264.so.2.6.0" "$TP/native/$PLATFORM/libopenh264.so.8"
  ln -sf libopenh264.so.8 "$TP/native/$PLATFORM/libopenh264.so"
  cc -O2 -fPIC -shared -I"$TP/shim" -I"$UPSTREAM/openh264/codec/api/wels" \
    -o "$TP/native/$PLATFORM/libprismdecode.so" "$TP/shim/prism_decode.c" \
    -L"$TP/native/$PLATFORM" -lopenh264 -Wl,-rpath,'$ORIGIN' -lpthread
fi

if [ ! -f "$TP/native/$PLATFORM/libastcenc.so" ]; then
  echo "==> 编译 libastcenc.so（供 JVM 经 JNA 调用）"
  mkdir -p "$TP/native/$PLATFORM"
  CORE_SOURCES=$(ls "$UPSTREAM"/astcenc/Source/*.cpp | grep -v astcenccli)
  c++ -std=c++17 -O2 -fPIC -shared -DASTCENC_DYNAMIC_LIBRARY \
    -I"$UPSTREAM/astcenc/Source" -o "$TP/native/$PLATFORM/libastcenc.so" \
    $CORE_SOURCES -lpthread
fi

if [ ! -f "$TP/lib/$PLATFORM/libprismcodec.a" ]; then
  echo "==> 编译 libprismcodec.a（OpenH264 的 C 封装）"
  cc -O2 -fPIC -I"$TP/shim" -I"$UPSTREAM/openh264/codec/api/wels" -c "$TP/shim/prism_codec.c" -o "$SRC/prism_codec.o"
  ar rcs "$TP/lib/$PLATFORM/libprismcodec.a" "$SRC/prism_codec.o"
fi

echo "完成：$TP/lib/$PLATFORM"
ls -lh "$TP/lib/$PLATFORM"
