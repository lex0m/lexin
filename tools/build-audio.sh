#!/system/bin/sh
# ---------------------------------------------------------------------------
# 重新编译 liblexinaudio.so（SILK 解码 + LAME 编码 + JNI 包装）
#
# 这个脚本是**唯一**的音频库构建入口。上一版是临时手敲命令、没留脚本，
# 结果出问题时没人知道当初怎么编的（本次就是靠反推才定位到解码器初始化用错）。
#
# 依赖（路径可用环境变量覆盖）：
#   SILK_SRC  kn007/silk-v3-decoder 的 silk/ 目录（含 src/ interface/ test/ Makefile）
#   LAME_SRC  rbrito/lame 根目录（含 include/ libmp3lame/）
#   LAME_CONF 手写的 config.h 所在目录（AArch64/Android 的 typedef 必须）
#
# 用法：
#   sh tools/build-audio.sh            编 .so 到 lib/arm64-v8a/
#   sh tools/build-audio.sh --test     额外编 CLI 并跑一段自测（需要 tone.silk）
# ---------------------------------------------------------------------------
set -eu

W=$(cd "$(dirname "$0")/.." && pwd)
SILK_SRC=${SILK_SRC:-$HOME/work/silksrc/silk}
LAME_SRC=${LAME_SRC:-$HOME/work/lamesrc}
LAME_CONF=${LAME_CONF:-$HOME/work/lameconf}
PREFIX=${PREFIX:-}
if [ -z "$PREFIX" ] || [ ! -x "$PREFIX/bin/clang-21" ]; then
    for cand in \
        /data/user/0/com.deepseek.harness/files/payload/re/termux/usr \
        /data/data/com.termux/files/usr
    do
        [ -x "$cand/bin/clang-21" ] && PREFIX=$cand && break
    done
fi
[ -n "$PREFIX" ] || { echo "找不到 clang（设置 PREFIX=…/termux/usr）" >&2; exit 3; }

CLANG=$PREFIX/bin/clang-21
MAKE=$PREFIX/bin/make
AR=$PREFIX/bin/ar
RANLIB=$PREFIX/bin/ranlib
export LD_LIBRARY_PATH=$PREFIX/lib
export SHELL=/system/bin/sh

TARGET_TRIPLE=${TARGET_TRIPLE:-aarch64-linux-android21}
CFLAGS="-Wall -O2 -fPIC -DHAVE_CONFIG_H -DANDROID"
INC="-I$SILK_SRC/interface -I$SILK_SRC/src -I$LAME_SRC/include -I$LAME_SRC/libmp3lame -I$LAME_CONF"

for d in "$SILK_SRC" "$LAME_SRC" "$LAME_CONF"; do
    [ -d "$d" ] || { printf '缺源码目录: %s\n' "$d" >&2; exit 3; }
done

# ① SILK 解码器静态库（首次或源码更新后）
if [ ! -f "$SILK_SRC/libSKP_SILK_SDK.a" ]; then
    echo "[1/3] build libSKP_SILK_SDK.a"
    ( cd "$SILK_SRC" && "$MAKE" lib SHELL=/system/bin/sh CC="$CLANG" AR="$AR" RANLIB="$RANLIB" \
        CFLAGS="-Wall -O3 -Iinterface -Isrc -Itest" )
fi

# ② LAME（排除只用于解码的 mpglib_interface.c —— 它依赖不存在的 mpglib/ 子目录）
LAME_CS=$(ls "$LAME_SRC"/libmp3lame/*.c | grep -v mpglib_interface.c | tr '\n' ' ')
[ -n "$LAME_CS" ] || { echo "lame 源码为空" >&2; exit 3; }

# ③ JNI 共享库
mkdir -p "$W/lib/arm64-v8a"
echo "[2/3] link liblexinaudio.so"
# shellcheck disable=SC2086
$CLANG --target="$TARGET_TRIPLE" $CFLAGS $INC \
    "$W/tools/native/silk2mp3.c" "$SILK_SRC/libSKP_SILK_SDK.a" $LAME_CS \
    -shared -lm -o "$W/lib/arm64-v8a/liblexinaudio.so"

printf 'ok  %s (%s bytes)\n' "$W/lib/arm64-v8a/liblexinaudio.so" "$(wc -c < "$W/lib/arm64-v8a/liblexinaudio.so" | tr -d ' ')"
sha256sum "$W/lib/arm64-v8a/liblexinaudio.so"

# ④ 可选自测：同一份代码编成 CLI，脱离 JVM 验证解码/编码链路
if [ "${1:-}" = "--test" ]; then
    # /sdcard 是 noexec：可执行文件必须落在应用私有目录
    CLI_DIR=${CLI_DIR:-$HOME/work/lexin-native}
    mkdir -p "$CLI_DIR"
    echo "[3/3] build CLI + self-test"
    # shellcheck disable=SC2086
    $CLANG --target="$TARGET_TRIPLE" $CFLAGS $INC -DSILK2MP3_CLI \
        "$W/tools/native/silk2mp3.c" "$SILK_SRC/libSKP_SILK_SDK.a" $LAME_CS \
        -lm -o "$CLI_DIR/silk2mp3_cli"
    IN=${TEST_SILK:-$HOME/work/tone.silk}
    OUT=${TEST_MP3:-$CLI_DIR/tone.mp3}
    [ -f "$IN" ] || { printf '没有测试样本: %s（设置 TEST_SILK=…）\n' "$IN" >&2; exit 0; }
    "$CLI_DIR/silk2mp3_cli" "$IN" "$OUT" 32
    printf 'mp3 %s (%s bytes)\n' "$OUT" "$(wc -c < "$OUT" | tr -d ' ')"
fi
