#!/bin/bash
# Builds "PPP Link.app": the SwiftUI menu-bar app with ppplink-engine and slirp inside.
# Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.
#
#   ./make-app.sh                     native architecture, ad-hoc signed
#   UNIVERSAL=1 ./make-app.sh         Apple silicon + Intel (needs Xcode, not just the CLT)
#   SIGN_IDENTITY="Developer ID Application: ..." ./make-app.sh
#                                     signed for distribution (then notarise; see README)
#
# Needs the Xcode command line tools (xcode-select --install) and macOS 13 or later.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DESKTOP="$(dirname "$HERE")"
VERSION="${VERSION:-1.0.0}"
BUILD_NUMBER="${BUILD_NUMBER:-1}"
OUT="${OUT:-$DESKTOP/build}"
APP="$OUT/PPP Link.app"
SIGN_IDENTITY="${SIGN_IDENTITY:--}"

# Helpers are built under desktop/build with relative paths, so a checkout whose
# path contains spaces still works with make.
if [ "${UNIVERSAL:-0}" = 1 ]; then
    ARCHFLAGS="-arch arm64 -arch x86_64"
    SWIFT_ARCHS=(--arch arm64 --arch x86_64)
    HELPERS_REL="build/helpers-universal"
else
    ARCHFLAGS=""
    SWIFT_ARCHS=()
    HELPERS_REL="build/helpers"
fi
HELPERS="$DESKTOP/$HELPERS_REL"

echo "==> Building ppplink-engine and slirp"
make -C "$DESKTOP" BUILD="$HELPERS_REL" ARCHFLAGS="$ARCHFLAGS" VERSION="$VERSION" \
    "$HELPERS_REL/slirp-bin" "$HELPERS_REL/ppplink-engine"

echo "==> Building the app"
cd "$HERE"
swift build -c release ${SWIFT_ARCHS[@]+"${SWIFT_ARCHS[@]}"}
BIN="$(swift build -c release ${SWIFT_ARCHS[@]+"${SWIFT_ARCHS[@]}"} --show-bin-path)"

echo "==> Assembling $APP"
rm -rf "$APP"
mkdir -p "$OUT" "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/PPPLink" "$APP/Contents/MacOS/PPPLink"
cp "$HELPERS/ppplink-engine" "$APP/Contents/MacOS/ppplink-engine"
cp "$HELPERS/slirp-bin" "$APP/Contents/MacOS/slirp"
sed -e "s/@VERSION@/$VERSION/g" -e "s/@BUILD@/$BUILD_NUMBER/g" \
    "$HERE/Resources/Info.plist" > "$APP/Contents/Info.plist"
cp "$DESKTOP/../LICENSE" "$DESKTOP/../NOTICE" "$DESKTOP/../THIRD_PARTY_NOTICES.txt" "$APP/Contents/Resources/"

# Icon: AppIcon.png (1024 px) -> AppIcon.icns
ICONSET="$OUT/AppIcon.iconset"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
    sips -z $size $size "$HERE/Resources/AppIcon.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z $double $double "$HERE/Resources/AppIcon.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf "$ICONSET"

echo "==> Signing (${SIGN_IDENTITY})"
SIGN_ARGS=(--force --options runtime --sign "$SIGN_IDENTITY")
[ "$SIGN_IDENTITY" != "-" ] && SIGN_ARGS+=(--timestamp)
codesign "${SIGN_ARGS[@]}" "$APP/Contents/MacOS/slirp"
codesign "${SIGN_ARGS[@]}" "$APP/Contents/MacOS/ppplink-engine"
codesign "${SIGN_ARGS[@]}" "$APP"
codesign --verify --strict "$APP"

if [ "${ZIP:-0}" = 1 ]; then
    ZIPFILE="$OUT/PPP-Link-$VERSION-macOS.zip"
    rm -f "$ZIPFILE"
    ditto -c -k --keepParent "$APP" "$ZIPFILE"
    echo "==> $ZIPFILE"
fi

echo "==> Done: $APP"
echo "    Run it with: open \"$APP\"   (or drag it to Applications)"
