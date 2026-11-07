#!/usr/bin/env bash
# Packages the in-tree Launcher3QuickStep APK into a KernelSU module zip.
#
# Usage:
#   ./kernelsu/build-module.sh [path-to-Launcher3QuickStep.apk] [output.zip]
#
# The APK must be signed with the ROM's platform key (the normal Soong build does
# this). A module built from a differently signed APK will be rejected by
# PackageManager when flashed over an installed ROM.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

APK="${1:-}"
if [ -z "$APK" ]; then
    # Prefer this checkout's tree, then any product under a sibling AOSP tree.
    for candidate in \
        "$ROOT/../../out/target/product/myron/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk" \
        "$ROOT/../../../evox17/out/target/product/myron/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk" \
        /android/Chroot/root/home/nullcode/evox17/out/target/product/myron/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk; do
        if [ -f "$candidate" ]; then
            APK="$candidate"
            break
        fi
    done
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "Launcher3QuickStep.apk not found; pass it as the first argument." >&2
    exit 1
fi

OUT="${2:-$ROOT/dist/SlopBoard-Glass-KernelSU.zip}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/system_ext/priv-app/Launcher3QuickStep/oat"
cp "$APK" "$STAGE/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk"
LIBDIR="$(dirname "$APK")/lib/arm64"
SRC_LIB="$LIBDIR/libnativegauss.so"
if [ -L "$SRC_LIB" ]; then
    # The in-tree install rule symlinks to /system/lib64; resolve it on the host.
    RESOLVED="$(readlink -f "$SRC_LIB" 2>/dev/null || true)"
    if [ -n "$RESOLVED" ] && [ -f "$RESOLVED" ]; then
        SRC_LIB="$RESOLVED"
    else
        PRODUCT_ROOT="$(cd "$(dirname "$APK")/../../.." && pwd)"
        SRC_LIB="$PRODUCT_ROOT/system/lib64/libnativegauss.so"
    fi
fi
if [ -f "$SRC_LIB" ]; then
    mkdir -p "$STAGE/system_ext/priv-app/Launcher3QuickStep/lib/arm64"
    cp "$SRC_LIB" "$STAGE/system_ext/priv-app/Launcher3QuickStep/lib/arm64/libnativegauss.so"
fi
cp "$HERE/module.prop" "$STAGE/module.prop"
cp "$HERE/customize.sh" "$STAGE/customize.sh"
# Hide the ROM's prebuilt odex/vdex, which would otherwise mismatch the new APK.
: > "$STAGE/system_ext/priv-app/Launcher3QuickStep/oat/.replace"

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
( cd "$STAGE" && zip -q -r "$OUT" . )
echo "Wrote $OUT ($(du -h "$OUT" | cut -f1))"
