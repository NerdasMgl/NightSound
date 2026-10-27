#!/bin/bash
# 夜莺 APK 构建脚本：javac -> dx -> aapt2 -> zipalign -> apksigner（不依赖 Gradle）
set -euo pipefail
unset JAVA_TOOL_OPTIONS
cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:-}"
[ -f "$ANDROID_JAR" ] || { echo "请设置 ANDROID_JAR 指向 API 34 的 android.jar，例如：ANDROID_JAR=/path/to/android-34.jar ./build.sh"; exit 1; }
DX="${DX:-/usr/lib/android-sdk/build-tools/debian/dx}"
VERSION_CODE="${VERSION_CODE:-4}"; VERSION_NAME="${VERSION_NAME:-0.2.2}"
OUT=build/nightsound-$VERSION_NAME.apk
rm -rf build/classes build/gen build/res.zip build/base.apk build/classes.dex build/aligned.apk; mkdir -p build/classes build/gen
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java build/gen -R build/res.zip \
  -A assets -0 bin --min-sdk-version 26 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d build/classes $(find src build/gen -name '*.java') 2> build/javac.log || { cat build/javac.log; echo "编译失败"; exit 1; }
grep -v "bootstrap class path\|^Note:\|warning: \[options\]\|^[0-9]* warning" build/javac.log || true
test -n "$(find build/classes -name '*.class' | head -1)"
$DX --dex --min-sdk-version=26 --output=build/classes.dex build/classes
(cd build && zip -q -j base.apk classes.dex)
zipalign -f -p 4 build/base.apk build/aligned.apk
# 签名密钥：默认放在 tools/（已被 .gitignore 忽略）。正式发布请用 KEYSTORE / KS_PASS 指定你自己的密钥并妥善保管：以后每个版本都必须用同一把。
KEYSTORE="${KEYSTORE:-tools/yehang.keystore}"; KS_PASS="${KS_PASS:-yehang123}"; KS_ALIAS="${KS_ALIAS:-yehang}"
mkdir -p "$(dirname "$KEYSTORE")"; [ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Yehang, O=Yehang, C=CN" >/dev/null 2>&1
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" --out "$OUT" build/aligned.apk
apksigner verify --verbose "$OUT" | head -6
ls -la "$OUT"
