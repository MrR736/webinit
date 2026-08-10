#!/usr/bin/env bash
set -e

NDK="$HOME/Android/Sdk/ndk/27.0.12077973"
API=24

BUILD_DIR="$PWD/build-android"
OUT_DIR="$PWD/../../lib/src/main/cpp/libdeflate"

ABIS=(
	"armeabi-v7a"
	"arm64-v8a"
	"x86"
	"x86_64"
)

rm -rf "$BUILD_DIR" "$OUT_DIR"
mkdir -p "$OUT_DIR/include"

FIRST=1

for ABI in "${ABIS[@]}"; do
	echo "===== Building $ABI ====="

	cmake -S . -B "$BUILD_DIR/$ABI" \
		-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
		-DANDROID_ABI="$ABI" \
		-DANDROID_PLATFORM="android-$API" \
		-DCMAKE_BUILD_TYPE=Release \
		-DLIBDEFLATE_BUILD_SHARED_LIB=OFF \
		-DLIBDEFLATE_BUILD_STATIC_LIB=ON \
		-DLIBDEFLATE_BUILD_GZIP=OFF \
		-DLIBDEFLATE_BUILD_TESTS=OFF

	cmake --build "$BUILD_DIR/$ABI" --parallel "$(nproc)"
	mkdir -p "$OUT_DIR/lib/$ABI"
	cp "$BUILD_DIR/$ABI/libdeflate.a" "$OUT_DIR/lib/$ABI/"

	if [ "$FIRST" -eq 1 ]; then
		cp libdeflate.h "$OUT_DIR/include/"
		FIRST=0
	fi
done

cp "$PWD/COPYING" "$OUT_DIR/"

cat > "$OUT_DIR/FindLibDeflate.cmake" <<'EOF'
add_library(libdeflate STATIC IMPORTED GLOBAL)

set_target_properties(libdeflate PROPERTIES
	IMPORTED_LOCATION
		"${CMAKE_CURRENT_LIST_DIR}/lib/${ANDROID_ABI}/libdeflate.a"
	INTERFACE_INCLUDE_DIRECTORIES
		"${CMAKE_CURRENT_LIST_DIR}/include"
)
EOF

echo
echo "Finished."
echo
