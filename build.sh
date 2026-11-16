#!/usr/bin/env bash
# Manual APK build for OpenLaunchTV — no Gradle.
#
# Why: on this VM the Gradle daemon's loopback connection never reaches the
# daemon (the sandbox intercepts it), so builds here run the toolchain
# directly: aapt2 -> javac -> d8 -> zipalign -> apksigner. On a normal
# machine (or Android Studio) the Gradle files in this repo work too.
#
# Output: hodor-launcher.apk (debug-signed, sideloadable).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk/jdk-17.0.16+8}"
export PATH="$JAVA_HOME/bin:$PATH"
BT="${ANDROID_SDK:-$HOME/android-sdk}/build-tools/34.0.0"
AJ="${ANDROID_SDK:-$HOME/android-sdk}/platforms/android-34/android.jar"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
JAR="$JAVA_HOME/bin/jar"

OUT=build/manual
rm -rf "$OUT"
mkdir -p "$OUT/compiled-res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 1. Compile + link resources, generate R.java
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/compiled-res/res.zip"
"$BT/aapt2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$AJ" \
    -A app/src/main/assets \
    --manifest app/src/main/AndroidManifest.xml \
    "$OUT/compiled-res/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version 28 --target-sdk-version 34 \
    --version-code 1 --version-name 0.1

# 2. Compile Java sources against android.jar
find app/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -classpath "$AJ" -d "$OUT/classes" @"$OUT/sources.txt"

# 3. Dex
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$AJ" --min-api 28 --output "$OUT/dex" "$OUT/classes.jar"

# 4. Add dex, align, sign (debug key)
(cd "$OUT/dex" && zip -q -X "../app-unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    "$KEYTOOL" -genkeypair -keystore "$KS" -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out openlaunch-tv.apk "$OUT/app-aligned.apk"

echo "Built: $(pwd)/openlaunch-tv.apk"
ls -la openlaunch-tv.apk
