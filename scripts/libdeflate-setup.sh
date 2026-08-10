#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION=1.25
ARCHIVE="$SCRIPT_DIR/libdeflate-${VERSION}.tar.gz"
SRC_DIR="$SCRIPT_DIR/libdeflate-${VERSION}"

if [ ! -f "$ARCHIVE" ]; then
    echo "Error: $ARCHIVE not found."
    exit 1
fi

echo "==> Extracting libdeflate $VERSION"

rm -rf "$SRC_DIR"

tar -xf "$ARCHIVE" -C "$SCRIPT_DIR"

echo "==> Source: $SRC_DIR"

cp "$SCRIPT_DIR/libdeflate-build.sh" "$SRC_DIR/build.sh"
chmod +x "$SRC_DIR/build.sh"

echo "==> Building libdeflate"

cd "$SRC_DIR"
./build.sh

cd ..

rm -rf "$SRC_DIR"

echo "==> libdeflate is ready:"
echo "$PROJECT_DIR/lib/src/main/cpp/libdeflate"
