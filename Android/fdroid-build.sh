#!/usr/bin/env bash
# FireDrop APK build for the F-Droid build server (and any Linux machine).
# The same pipeline as Android/build.ps1 on Windows: aapt2 -> javac -> jar -> d8 -> apk.
# No Gradle, no AndroidX, nothing prebuilt in the repository: the single
# third-party dependency com.google.zxing:core 3.5.3 (Apache-2.0) is downloaded
# from Maven Central during the build and verified by SHA-256.
#
# The resulting APK is left unsigned on purpose: the F-Droid build server signs
# it with its own key afterwards. Build commands run with the working directory
# at the repository root, ANDROID_HOME and JAVA_HOME set by fdroidserver.
#
# Usage: bash Android/fdroid-build.sh [output.apk]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
APP="$ROOT/Android/app"
OUT="${1:-$ROOT/Android/build/FireDrop-unsigned.apk}"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"

if [ ! -x "$BT/aapt2" ]; then
    echo "aapt2 not found at $BT/aapt2 - point ANDROID_HOME at the Android SDK" >&2
    exit 1
fi
if [ ! -f "$PLATFORM_JAR" ]; then
    echo "android.jar not found at $PLATFORM_JAR - install platform;android-34" >&2
    exit 1
fi

JAVAC="javac"
JAR="jar"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAR="$JAVA_HOME/bin/jar"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

ZXING_URL="https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar"
ZXING_SHA256="8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$(dirname "$OUT")"

echo "[1/8] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$WORK/resources.zip"

echo "[2/8] aapt2 link"
"$BT/aapt2" link -o "$WORK/app.base.apk" -I "$PLATFORM_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    -R "$WORK/resources.zip" \
    --java "$WORK/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --auto-add-overlay

echo "[3/8] download zxing-core (Apache-2.0) from Maven Central"
curl -fsSLo "$WORK/core.jar" "$ZXING_URL"
echo "$ZXING_SHA256  $WORK/core.jar" | sha256sum -c -

echo "[4/8] javac"
mkdir -p "$WORK/classes"
find "$APP/java" "$WORK/gen" -type f -name '*.java' > "$WORK/sources.txt"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:none \
    -classpath "$PLATFORM_JAR:$WORK/core.jar" \
    -d "$WORK/classes" "@$WORK/sources.txt"

echo "[5/8] jar"
"$JAR" cf "$WORK/classes.jar" -C "$WORK/classes" .

echo "[6/8] d8"
mkdir -p "$WORK/dex"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM_JAR" \
    --output "$WORK/dex" "$WORK/classes.jar" "$WORK/core.jar"

echo "[7/8] package classes.dex, keep resources.arsc uncompressed"
python3 - "$WORK/app.base.apk" "$WORK/dex/classes.dex" "$WORK/app.built.apk" <<'PYEOF'
import sys
import zipfile

in_path, dex_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
with open(dex_path, "rb") as f:
    dex = f.read()

with zipfile.ZipFile(in_path) as zin:
    names = zin.namelist()
    data = {name: zin.read(name) for name in names}

if "classes.dex" not in names:
    names.append("classes.dex")
    data["classes.dex"] = dex

with zipfile.ZipFile(out_path, "w") as zout:
    for name in names:
        info = zipfile.ZipInfo(name)
        # Android 11+ refuses APKs whose resources.arsc is compressed.
        info.compress_type = (
            zipfile.ZIP_STORED if name == "resources.arsc" else zipfile.ZIP_DEFLATED
        )
        zout.writestr(info, data[name])
PYEOF

echo "[8/8] zipalign"
"$BT/zipalign" -p -f 4 "$WORK/app.built.apk" "$OUT"

echo ""
echo "APK (unsigned, ready for fdroidserver signing): $OUT"
ls -l "$OUT"
