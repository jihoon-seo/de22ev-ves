#!/bin/bash
# Builds the release assets into dist/:
#   de22ev-ves-<version>.zip       the HMG-API mod (signed when SIGN_KEY is set)
#   de22ev-ves-<version>.USB.zip   the NEMESIS USB kit that installs it
#   SHA256SUMS
#
# Needs: a JDK 17+ (JAVA_HOME or on PATH), Python 3, curl (unless HMGSIGN_SRC is set) and
# the Android SDK pieces:
#   ANDROID_JAR   platforms/android-19 android.jar (API 19, Android 4.4)
#   BUILD_TOOLS   a build-tools folder with aapt2, zipalign and lib/apksigner.jar
# SIGN_KEY      the mod author's private key (kept outside the repository); without it the
#               package is unsigned, which HMG-API refuses without overrides.
# APP_KEYSTORE  PKCS12 keystore for the app's APK signature (created on first use, keep it:
#               an update must carry the same key); APP_KEYSTORE_PASS its password file.
# HMGSIGN_SRC   a folder with HMG-API's Signing.java and Json.java, instead of fetching them.
set -euo pipefail
cd "$(dirname "$0")"
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}
PY=${PYTHON:-python}
: "${ANDROID_JAR:?set ANDROID_JAR to platforms/android-19/android.jar}"
: "${BUILD_TOOLS:?set BUILD_TOOLS to an Android build-tools folder}"
APP_KEYSTORE=${APP_KEYSTORE:-keys/app.p12}
APP_KEYSTORE_PASS=${APP_KEYSTORE_PASS:-keys/app.pass}
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
exe() { if [ -x "$1.exe" ]; then echo "$1.exe"; else echo "$1"; fi; }
AAPT2=$(exe "$BUILD_TOOLS/aapt2")
ZIPALIGN=$(exe "$BUILD_TOOLS/zipalign")
# D8_JAR: a jar with com.android.tools.r8.D8; build-tools 34's d8.jar (8.2.2) crashes on these
# classes, D8 9.x works (e.g. r8.jar from https://maven.google.com, com.android.tools:r8)
D8_JAR=${D8_JAR:-$BUILD_TOOLS/lib/d8.jar}
D8=("${JAVA_BIN}java" -cp "$(native "$D8_JAR")" com.android.tools.r8.D8 --min-api 19 --no-desugaring --release)

VERSION=$("$PY" -c "import json;print(json.load(open('mod/mod.json',encoding='utf-8'))['version'])")
ID=$("$PY" -c "import json;print(json.load(open('mod/mod.json',encoding='utf-8'))['id'])")
UUID=$(tr -d '\r\n' < keys/uuid.txt)
PKG=$ID-$VERSION.zip
grep -q "^ZIP=$PKG\$" usb/VES_MOD/run.sh || { echo "usb/VES_MOD/run.sh does not name $PKG" >&2; exit 1; }
grep -q "^UUID=$UUID\$" usb/VES_MOD/run.sh || { echo "usb/VES_MOD/run.sh does not name uuid $UUID" >&2; exit 1; }
grep -q "android:versionName=\"$VERSION\"" app/AndroidManifest.xml || { echo "app/AndroidManifest.xml versionName is not $VERSION" >&2; exit 1; }
echo "building $ID $VERSION"

rm -rf build dist
mkdir -p build/classes build/vesd build/apk/classes build/apk/gen build/apk/res build/mod/app build/usb build/hmgsign dist

srcs() { find "$@" -name '*.java' | sort | while IFS= read -r f; do native "$f"; done; }

# vesd.jar: ves.core + ves.android + ves.hmg, compiled against android.jar and the
# compile-only stand-ins in android/stubs (hidden framework classes; not packaged)
mapfile -t S < <(srcs core/src/ves/core android/src android/stubs)
"${JAVA_BIN}javac" --release 8 -Xlint:-options -nowarn -encoding UTF-8 -cp "$(native "$ANDROID_JAR")" \
    -d "$(native build/classes)" "${S[@]}"
mapfile -t C < <(find build/classes/ves -name '*.class' | sort | while IFS= read -r f; do native "$f"; done)
"${D8[@]}" --lib "$(native "$ANDROID_JAR")" --output "$(native build/vesd)" "${C[@]}"
# the built-in sound packs (packs/<slot>/: pack.txt + loops; sounds by 히릿, used with permission, see
# THIRD_PARTY.md) go inside vesd.jar: HMG-API leaves only the service's own jar readable to its user (system).
# The slots are PackStore.BUILT_IN; a missing one fails the build.
mapfile -t BUILT_IN < <(grep -E 'static final String\[\] BUILT_IN = ' android/src/ves/android/PackStore.java | grep -oE '"[a-z0-9_]+"' | tr -d '"')
[ "${#BUILT_IN[@]}" -gt 0 ] || { echo "no BUILT_IN slots in android/src/ves/android/PackStore.java" >&2; exit 1; }
for b in "${BUILT_IN[@]}"; do
    [ -f "packs/$b/pack.txt" ] || { echo "packs/$b/pack.txt missing (PackStore.BUILT_IN lists $b)" >&2; exit 1; }
    mkdir -p "build/vesd/packs/$b"
    cp "packs/$b/pack.txt" "packs/$b/"*.wav "build/vesd/packs/$b/"
done
"$PY" tools/zipdir.py build/vesd build/mod/vesd.jar

# the app: resources (icon drawn by tools/make_icon.py), R, classes (ves.app + ves.hmg)
cp -r app/res/. build/apk/res/
mkdir -p build/apk/res/drawable
"$PY" -I tools/make_icon.py build/apk/res/drawable/ic_ves.png 96
"$PY" -I tools/make_icon.py build/mod/tile.png 192
"$AAPT2" compile --dir "$(native build/apk/res)" -o "$(native build/apk/res.zip)"
"$AAPT2" link -I "$(native "$ANDROID_JAR")" --manifest "$(native app/AndroidManifest.xml)" \
    --min-sdk-version 19 --target-sdk-version 19 --java "$(native build/apk/gen)" \
    -o "$(native build/apk/base.apk)" "$(native build/apk/res.zip)"
mapfile -t A < <(srcs app/java build/apk/gen android/src/ves/hmg core/src/ves/core/ProbeResume.java core/src/ves/core/StatusLabel.java)
"${JAVA_BIN}javac" --release 8 -Xlint:-options -nowarn -encoding UTF-8 -cp "$(native "$ANDROID_JAR")" \
    -d "$(native build/apk/classes)" "${A[@]}"
mapfile -t AC < <(find build/apk/classes -name '*.class' | sort | while IFS= read -r f; do native "$f"; done)
"${D8[@]}" --lib "$(native "$ANDROID_JAR")" --output "$(native build/apk)" "${AC[@]}"
cp build/apk/base.apk build/apk/unsigned.apk
"$PY" -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a'); z.write(sys.argv[2],'classes.dex'); z.close()" \
    build/apk/unsigned.apk build/apk/classes.dex
"$ZIPALIGN" -f 4 "$(native build/apk/unsigned.apk)" "$(native build/apk/aligned.apk)"
if [ ! -f "$APP_KEYSTORE" ]; then
    echo "creating the app signing key $APP_KEYSTORE (keep it: updates need the same key)"
    mkdir -p "$(dirname "$APP_KEYSTORE")"
    "$PY" -c "import secrets;print(secrets.token_hex(16))" > "$APP_KEYSTORE_PASS"
    "${JAVA_BIN}keytool" -genkeypair -keystore "$(native "$APP_KEYSTORE")" -storetype PKCS12 \
        -storepass:file "$(native "$APP_KEYSTORE_PASS")" -alias app -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=de22ev-ves" > /dev/null
fi
"${JAVA_BIN}java" -jar "$(native "$BUILD_TOOLS/lib/apksigner.jar")" sign --ks "$(native "$APP_KEYSTORE")" \
    --ks-pass "file:$(native "$APP_KEYSTORE_PASS")" --min-sdk-version 19 \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out "$(native build/mod/app/EngineSound.apk)" "$(native build/apk/aligned.apk)"

# the mod folder (its source and licence travel with it)
cp mod/mod.json mod/settings.json mod/README.ko.txt LICENSE THIRD_PARTY.md build/mod/
mkdir -p build/mod/src/core build/mod/src/android
cp -r core/src build/mod/src/core/
cp -r android/src android/stubs build/mod/src/android/
cp -r app tools build.sh build/mod/src/

# the signer: HMG-API's Signing + Json at a pinned commit, with tools/hmgsign/Engine.java
HMG_COMMIT=4fab4376adae65b67025046539963a68fb37f76f
mkdir -p build/hmgsign-src/hmgapi/engine
for f in Signing Json; do
    if [ -n "${HMGSIGN_SRC:-}" ]; then
        cp "$HMGSIGN_SRC/$f.java" build/hmgsign-src/hmgapi/engine/
    else
        curl -fsSL "https://raw.githubusercontent.com/VysseCodes/HMG-API/$HMG_COMMIT/core/runtime/java/hmgapi/engine/$f.java" \
            -o build/hmgsign-src/hmgapi/engine/$f.java
    fi
done
cp tools/hmgsign/Engine.java build/hmgsign-src/hmgapi/engine/
"${JAVA_BIN}javac" -nowarn -encoding UTF-8 -d "$(native build/hmgsign)" \
    "$(native build/hmgsign-src/hmgapi/engine/Signing.java)" "$(native build/hmgsign-src/hmgapi/engine/Json.java)" \
    "$(native build/hmgsign-src/hmgapi/engine/Engine.java)"
SIGNER=("${JAVA_BIN}java" -cp "$(native build/hmgsign)" hmgapi.engine.Signing)

if [ -n "${SIGN_KEY:-}" ]; then
    "${SIGNER[@]}" sign "$(native build/mod)" "$(native "$SIGN_KEY")"
    "${SIGNER[@]}" verify "$(native build/mod)" "$UUID"
else
    echo "SIGN_KEY not set: the package is unsigned (HMG-API refuses it without overrides)"
fi
"$PY" tools/zipdir.py build/mod "dist/$PKG"

# the NEMESIS USB kit, with the same package inside
cp -r usb/. build/usb/
cp "dist/$PKG" build/usb/VES_MOD/
"$PY" tools/zipdir.py build/usb "dist/$ID-$VERSION.USB.zip"

(cd dist && sha256sum "$PKG" "$ID-$VERSION.USB.zip" > SHA256SUMS)
cat dist/SHA256SUMS
