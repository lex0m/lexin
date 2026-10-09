#!/system/bin/sh
# ---------------------------------------------------------------------------
# 乐信 构建脚本（无 Gradle）
#   res -> aapt2 -> R.java
#   src -> javac -> class -> d8 -> classes.dex
#   dex + assets -> zip -> apksigner
#
# 依赖（按需通过环境变量覆盖）：
#   PREFIX    JDK/aapt2/apksigner 所在前缀，默认 Termux
#   WXMOD_DIR 存放 r8.jar 与 android-35.jar / api-82-aliyun.jar 的目录
# ---------------------------------------------------------------------------
set -u

PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
JH=${JAVA_HOME:-$PREFIX/lib/jvm/java-21-openjdk}
JAVA=$JH/bin/java
JAVAC=$JH/bin/javac
KEYTOOL=$JH/bin/keytool
AAPT2=$PREFIX/bin/aapt2
APKSIGNER=$PREFIX/bin/apksigner
export JAVA_HOME=$JH
export LD_LIBRARY_PATH=$PREFIX/lib

W=$(cd "$(dirname "$0")" && pwd)
M=${WXMOD_DIR:-$W/../wxmod}
T=$M/tools
L=$M/libs
R8=$T/r8.jar
ANDROID_JAR=$L/android-35.jar
XPOSED_JAR=$L/api-82-aliyun.jar

B=$W/build
OUT=$W/dist
rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/res" "$OUT"

echo "[1/6] aapt2 compile resources"
"$AAPT2" compile --dir "$W/res" -o "$B/res.zip" || exit 1

echo "[2/6] aapt2 link + R.java"
"$AAPT2" link -o "$B/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$W/AndroidManifest.xml" \
    --java "$B/gen" \
    --min-sdk-version 28 --target-sdk-version 35 \
    "$B/res.zip" || exit 1

echo "[3/6] javac"
find "$W/src" -name '*.java' > "$B/sources.txt"
find "$B/gen" -name '*.java' 2>/dev/null >> "$B/sources.txt"
"$JAVAC" -nowarn -source 11 -target 11 -encoding UTF-8 \
    -cp "$ANDROID_JAR:$XPOSED_JAR" \
    -d "$B/classes" @"$B/sources.txt" || exit 1

echo "[4/6] d8 (dex)"
find "$B/classes" -name '*.class' > "$B/classes.txt"
# R8 instead of D8: same dexing, plus shrinking and obfuscation. The keep rules
# in proguard-rules.pro protect the reflection-reached entry points; without them
# R8 renames Entry/XposedBridge.log callbacks and the module silently does nothing.
"$JAVA" -cp "$R8" com.android.tools.r8.R8 \
    --min-api 28 --release \
    --lib "$ANDROID_JAR" \
    --lib "$XPOSED_JAR" \
    --pg-conf "$W/proguard-rules.pro" \
    --pg-map-output "$W/mapping.txt" \
    --output "$B/dex" @"$B/classes.txt" || exit 1
[ -f "$B/dex/mapping.txt" ] && cp -f "$B/dex/mapping.txt" "$W/mapping.txt"     && echo "  obfuscation mapping -> $W/mapping.txt"
ls -la "$B/dex/"

echo "[5/6] package apk"
/data/user/0/com.deepseek.harness/files/payload/bin/python3 - "$B" "$W" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile, os
B, W, out = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(os.path.join(B, "base.apk")) as src, \
     zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        if item.filename.startswith("classes") and item.filename.endswith(".dex"):
            continue
        dst.writestr(item, src.read(item.filename))
    with open(os.path.join(B, "dex", "classes.dex"), "rb") as fh:
        dst.writestr("classes.dex", fh.read())
    # Ship the whole assets tree: xposed_init plus any themed resources.
    # Bundling them in the APK is what keeps the module from writing files into
    # WeChat's storage just to get its own images back.
    assets = os.path.join(W, "assets")
    shipped = []
    for root, _dirs, files in os.walk(assets):
        for fn in files:
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, assets)
            with open(full, "rb") as fh:
                dst.writestr("assets/" + rel, fh.read())
            shipped.append(rel)
    print("assets shipped:", len(shipped), shipped[:6])
print("packed:", out, os.path.getsize(out), "bytes")
PY

echo "[6/6] sign"
KS=$W/keystore/debug.ks
[ -f "$KS" ] || "$KEYTOOL" -genkeypair -keystore "$KS" -alias k -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass 123456 -keypass 123456 \
    -dname "CN=svc,OU=svc,O=svc" 2>/dev/null
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:123456 --key-pass pass:123456 \
    --out "$OUT/lexin.apk" "$OUT/unsigned.apk" || exit 1
"$APKSIGNER" verify --print-certs "$OUT/lexin.apk" | head -5
su -c "cp -f $OUT/lexin.apk /data/local/tmp/lexin.apk" 2>/dev/null
echo "DONE -> $OUT/lexin.apk  (installed copy: /data/local/tmp/lexin.apk)"
