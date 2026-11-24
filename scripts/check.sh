#!/usr/bin/env bash
# 跑单元测试和 Android Lint（gradlew testDebugUnitTest lintDebug）。退出码就是结果：
# 两者都过是 0，否则是 gradlew 的非零退出码。

# 被 sh 调用时转交 bash（本脚本用了 bash 语法）。
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_toolchain

run_gradle testDebugUnitTest lintDebug
rc=$?

if [[ $rc -eq 0 ]]; then
    printf '\n通过：单元测试和 Lint 都没有失败。\n'
else
    printf '\n未通过（退出码 %s）。\n' "$rc" >&2
fi
printf '报告：%s/app/build/reports/tests/testDebugUnitTest/index.html\n' "$ZCODE_REMOTE_ROOT"
printf '      %s/app/build/reports/lint-results-debug.html\n' "$ZCODE_REMOTE_ROOT"
exit "$rc"
