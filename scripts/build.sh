#!/usr/bin/env bash
# 构建，并把安装包固定复制为 dist/zcode-remote-debug.apk 或 dist/zcode-remote-release.apk。
#
# 用法：scripts/build.sh debug|release
# release 没有密钥时会先自动生成（scripts/make-keystore.sh）。

# 被 sh 调用时转交 bash（本脚本用了 bash 语法）。
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_toolchain

usage() {
    printf '用法：%s debug|release\n' "$0" >&2
}

if [[ $# -ne 1 ]]; then usage; exit 2; fi
VARIANT="$1"
case "$VARIANT" in
    debug) TASK=assembleDebug ;;
    release) TASK=assembleRelease ;;
    *) usage; exit 2 ;;
esac

if [[ "$VARIANT" == "release" ]]; then
    "$ZCODE_REMOTE_SCRIPTS_DIR/make-keystore.sh"
fi

run_gradle "$TASK"

BUILT="$ZCODE_REMOTE_ROOT/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
UNSIGNED="$ZCODE_REMOTE_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
if [[ ! -f "$BUILT" ]]; then
    if [[ "$VARIANT" == "release" && -f "$UNSIGNED" ]]; then
        die "release 包没有签名（app-release-unsigned.apk）：keystore.properties 缺失或不完整。先运行 scripts/make-keystore.sh。"
    fi
    die "构建结束了，但找不到安装包：$BUILT"
fi

DIST="$ZCODE_REMOTE_ROOT/dist"
mkdir -p "$DIST"
OUT="$DIST/zcode-remote-$VARIANT.apk"
cp -f "$BUILT" "$OUT"

printf '\n安装包：%s\n大小：%s 字节\nsha256：%s\n' \
    "$OUT" "$(stat -c %s "$OUT")" "$(sha256sum "$OUT" | cut -d' ' -f1)"
printf '静态验收：%s verify-apk.sh %s\n' "$ZCODE_REMOTE_SCRIPTS_DIR" "$OUT"
