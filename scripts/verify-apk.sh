#!/usr/bin/env bash
# 安装包静态验收：scripts/verify-apk.sh <apk路径>
#
# 用 build-tools 里的 aapt2 读清单，逐项打印「通过」或「不通过」；任何一项不符就退出非零。
# 检查项与判定逻辑都在 scripts/apk-audit.py，改规则去那里改。
#
# 退出码：0 全部通过；1 有不通过项，或安装包、清单读不了；2 用法错误。

# 被 sh 调用时转交 bash（本脚本用了 bash 语法）。
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage() {
    printf '用法：%s <apk路径>\n' "$0" >&2
}
if [[ $# -ne 1 ]]; then usage; exit 2; fi

APK="$1"
if [[ ! -f "$APK" ]]; then
    printf '错误：找不到安装包：%s\n' "$APK" >&2
    usage
    exit 2
fi

require_toolchain
require_python3

BUILD_TOOLS="$(build_tools_dir)"
python3 -I "$ZCODE_REMOTE_SCRIPTS_DIR/apk-audit.py" \
    --apk "$APK" --aapt2 "$BUILD_TOOLS/aapt2" --apksigner "$BUILD_TOOLS/apksigner"
