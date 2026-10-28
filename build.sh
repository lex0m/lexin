#!/system/bin/sh
# ---------------------------------------------------------------------------
# 乐信 构建脚本（无 Gradle）—— v2
#
#   res -> aapt2 -> R.java
#   src -> javac -> class -> R8 -> classes.dex
#   dex + assets + lib -> zip -> apksigner -> verify
#
# v2 相比 v1 修掉的构建期问题（每条都出过事）：
#   1. dist/ 先清空 + 打包失败立即退出 —— v1 失败时会拿上一次的 unsigned.apk 去签名，
#      产出"看起来成功"的旧包
#   2. 构建后自动校验（tools/verify_apk.py）：组件类是否被 R8 裁掉、.so 是否 STORED、
#      resources.arsc 是否被压缩、xposed_init、buildId 与源码是否一致
#   3. 版本号唯一来源 = AndroidManifest.xml；build-info.txt 写进 APK（可追溯到源码状态）
#   4. 打包确定性：固定顺序 + 固定时间戳（同样输入得到同样 zip）
#   5. DEBUG=true 时拒绝出正式包（必须显式 --debug）
#   6. 自动探测 Termux 前缀；不再依赖手工 export
#
# 用法：
#   sh build.sh               正式包（Log.DEBUG 必须为 false）
#   sh build.sh --debug       诊断包（允许 DEBUG=true，文件名带 -debug）
#   sh build.sh --push        额外拷一份到 /data/local/tmp（需要 root/Shizuku）
# ---------------------------------------------------------------------------
set -eu

W=$(cd "$(dirname "$0")" && pwd)
B=$W/build
OUT=$W/dist

# ---------- 参数 ----------
DEBUG_BUILD=0
PUSH=0
for arg in "$@"; do
    case "$arg" in
        --debug) DEBUG_BUILD=1 ;;
        --push)  PUSH=1 ;;
        -h|--help)
            printf '%s\n' \
                "用法: sh build.sh [--debug] [--push]" \
                "  --debug  允许 Log.DEBUG=true，产物名带 -debug" \
                "  --push   构建完拷到 /data/local/tmp/lexin.apk"
            exit 0 ;;
        *) printf '未知参数: %s\n' "$arg" >&2; exit 2 ;;
    esac
done

# ---------- 工具链 ----------
PY=$(command -v python3 2>/dev/null || true)
[ -n "$PY" ] || PY=/data/user/0/com.deepseek.harness/files/payload/bin/python3
[ -x "$PY" ] || { echo "找不到 python3" >&2; exit 3; }

PREFIX=${PREFIX:-}
if [ -z "$PREFIX" ] || [ ! -x "$PREFIX/lib/jvm/java-21-openjdk/bin/javac" ]; then
    for cand in \
        /data/user/0/com.deepseek.harness/files/payload/re/termux/usr \
        /data/data/com.termux/files/usr
    do
        if [ -x "$cand/lib/jvm/java-21-openjdk/bin/javac" ] && [ -x "$cand/bin/aapt2" ]; then
            PREFIX=$cand
            break
        fi
    done
fi
[ -n "$PREFIX" ] && [ -x "$PREFIX/bin/aapt2" ] || {
    echo "找不到 JDK/aapt2（设置 PREFIX=/path/to/termux/usr）" >&2; exit 3; }

JH=${JAVA_HOME:-$PREFIX/lib/jvm/java-21-openjdk}
JAVA=$JH/bin/java
JAVAC=$JH/bin/javac
KEYTOOL=$JH/bin/keytool
AAPT2=$PREFIX/bin/aapt2
APKSIGNER_JAR=${APKSIGNER_JAR:-$PREFIX/share/java/apksigner.jar}
export JAVA_HOME=$JH
export LD_LIBRARY_PATH=$PREFIX/lib

M=${WXMOD_DIR:-$W/../wxmod}
R8=$M/tools/r8.jar
ANDROID_JAR=$M/libs/android-35.jar
XPOSED_JAR=$M/libs/api-82-aliyun.jar

for f in "$JAVA" "$JAVAC" "$AAPT2" "$APKSIGNER_JAR" "$R8" "$ANDROID_JAR" "$XPOSED_JAR"; do
    [ -e "$f" ] || { printf '缺文件: %s\n' "$f" >&2; exit 3; }
done

# ---------- 版本（唯一来源：AndroidManifest.xml） ----------
set -- $("$PY" "$W/tools/buildmeta.py" "$W/AndroidManifest.xml")
VER=${1:?}
CODE=${2:?}

# ---------- 正式包不允许带日志 ----------
DEBUG_ON=$("$PY" -c 'import io,sys;print(1 if "DEBUG = true" in io.open(sys.argv[1],encoding="utf-8").read() else 0)' "$W/src/io/github/lex0m/lexin/Log.java")
if [ "$DEBUG_ON" = "1" ] && [ "$DEBUG_BUILD" != "1" ]; then
    echo "Log.DEBUG=true：拒绝出正式包。要出诊断包请加 --debug" >&2
    exit 4
fi

printf '== 乐信 v%s (versionCode %s)%s ==\n' "$VER" "$CODE" \
    "$([ "$DEBUG_BUILD" = "1" ] && echo ' [debug]' || true)"

# ---------- 清理（★ 只清上游产物；签名用 .tmp 原子替换，失败不留半包） ----------
rm -rf "$B"
mkdir -p "$OUT"
# 只清本次的上游产物；保留其它变体（正式包/诊断包要能并存）
rm -f "$OUT/unsigned.apk"
mkdir -p "$B/gen" "$B/gen-src/io/github/lex0m/lexin" "$B/classes" "$B/dex" "$B/res" "$OUT"

# 日志开关是**构建参数**，不再手改源码（上一版靠人记得改回 DEBUG=false，迟早出事）
if [ "$DEBUG_BUILD" = "1" ]; then DBG=true; else DBG=false; fi
printf 'package io.github.lex0m.lexin;\n\nfinal class BuildFlags {\n    static final boolean DEBUG = %s;\n    static final String VERSION = "%s";\n}\n' \
    "$DBG" "$VER" > "$B/gen-src/io/github/lex0m/lexin/BuildFlags.java"

echo "[1/7] aapt2 compile resources"
"$AAPT2" compile --dir "$W/res" -o "$B/res.zip"

echo "[2/7] aapt2 link + R.java"
"$AAPT2" link -o "$B/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$W/AndroidManifest.xml" \
    --java "$B/gen" \
    --min-sdk-version 28 --target-sdk-version 35 \
    "$B/res.zip"

echo "[3/7] javac"
find "$W/src" -name '*.java' | sort > "$B/sources.txt"
find "$B/gen-src" -name '*.java' 2>/dev/null | sort >> "$B/sources.txt"
find "$B/gen" -name '*.java' 2>/dev/null | sort >> "$B/sources.txt"
"$JAVAC" -nowarn -source 11 -target 11 -encoding UTF-8 \
    -cp "$ANDROID_JAR:$XPOSED_JAR" \
    -d "$B/classes" @"$B/sources.txt"

echo "[4/7] R8 (dex + shrink)"
find "$B/classes" -name '*.class' | sort > "$B/classes.txt"
"$JAVA" -cp "$R8" com.android.tools.r8.R8 \
    --min-api 28 --release \
    --lib "$ANDROID_JAR" \
    --lib "$XPOSED_JAR" \
    --pg-conf "$W/proguard-rules.pro" \
    --pg-map-output "$B/mapping.txt" \
    --output "$B/dex" @"$B/classes.txt"
[ -s "$B/dex/classes.dex" ] || { echo "R8 没有产出 classes.dex" >&2; exit 5; }
cp -f "$B/mapping.txt" "$W/mapping.txt"      # 崩溃栈反混淆用
cp -f "$B/mapping.txt" "$OUT/mapping-$VER-$CODE.txt"

echo "[5/7] package"
"$PY" "$W/tools/package.py" "$B" "$W" "$OUT/unsigned.apk" "$VER" "$CODE"

echo "[6/7] sign"
KS=$W/keystore/debug.ks
if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -alias k -keyalg RSA \
        -keysize 2048 -validity 10000 -storepass 123456 -keypass 123456 \
        -dname "CN=svc,OU=svc,O=svc"
fi
if [ "$DEBUG_BUILD" = "1" ]; then
    APK=$OUT/lexin-$VER-debug.apk
else
    APK=$OUT/lexin-$VER.apk
fi
# 先写临时文件再原子替换：签名/校验失败不会留下半个包或旧的同名包
TMPAPK=$APK.tmp
rm -f "$TMPAPK"
"$JAVA" -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-pass pass:123456 \
    --key-pass pass:123456 --v4-signing-enabled false --out "$TMPAPK" "$OUT/unsigned.apk"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$TMPAPK" > "$B/sign.txt" 2>&1 || {
    cat "$B/sign.txt" >&2; rm -f "$TMPAPK"; echo "签名校验失败" >&2; exit 6; }
mv -f "$TMPAPK" "$APK"
rm -f "$OUT"/*.idsig
head -4 "$B/sign.txt"

echo "[7/7] verify"
BADGING=$("$AAPT2" dump badging "$APK" | head -1)
case "$BADGING" in
    *"versionCode='$CODE'"*) ;;
    *) printf 'versionCode 不符: %s\n' "$BADGING" >&2; exit 7 ;;
esac
case "$BADGING" in
    *"versionName='$VER'"*) ;;
    *) printf 'versionName 不符: %s\n' "$BADGING" >&2; exit 7 ;;
esac
"$PY" "$W/tools/verify_apk.py" "$APK" "$W" "$VER"

rm -f "$OUT/unsigned.apk"
( cd "$OUT" && sha256sum *.apk | sort -k2 > SHA256SUMS )
if [ "$DEBUG_BUILD" != "1" ]; then
    cp -f "$APK" "$OUT/lexin.apk"
fi

if [ "$PUSH" = "1" ]; then
    if cp -f "$APK" /data/local/tmp/lexin.apk 2>/dev/null; then
        echo "pushed -> /data/local/tmp/lexin.apk"
    else
        echo "push 失败（当前 shell 没有写 /data/local/tmp 的权限；用 Shizuku/root 通道拷）" >&2
    fi
fi

printf '\nDONE  %s\n' "$APK"
printf 'size  %s bytes\n' "$(wc -c < "$APK" | tr -d ' ')"
printf 'sha256 %s\n' "$(sha256sum "$APK" | cut -d' ' -f1)"
printf 'map   %s\n' "$OUT/mapping-$VER-$CODE.txt"
