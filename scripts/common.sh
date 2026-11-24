# 公共函数。只被 scripts/ 下的其它脚本 source，不单独执行。
#
# 约定：脚本都从自身位置推算工程根目录，结果与「在哪个目录下调用」无关。

# 工程路径里有中文。Java 按 locale 决定文件名编码，环境里是 LANG=C 时会把路径弄坏，
# 所以无论谁来调用，这里统一钉成 UTF-8。
export LC_ALL=C.UTF-8

ZCODE_REMOTE_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ZCODE_REMOTE_ROOT="$(cd "$ZCODE_REMOTE_SCRIPTS_DIR/.." && pwd)"

source "$ZCODE_REMOTE_SCRIPTS_DIR/toolchain.env"

die() {
    printf '错误：%s\n' "$*" >&2
    exit 1
}

# 工具链齐不齐；缺了就带着修法退出。
require_toolchain() {
    [[ -x "$JAVA_HOME/bin/java" ]] || die "找不到 java：$JAVA_HOME/bin/java。换机器请改 scripts/toolchain.env。"
    [[ -x "$JAVA_HOME/bin/keytool" ]] || die "找不到 keytool：$JAVA_HOME/bin/keytool。"
    [[ -d "$ANDROID_HOME" ]] || die "找不到 Android SDK：$ANDROID_HOME。换机器请改 scripts/toolchain.env。"
}

require_python3() {
    command -v python3 >/dev/null 2>&1 || die "需要 python3，但 PATH 里找不到。"
}

# 在工程根目录跑 gradlew，参数就是 Gradle 的任务和选项，返回 gradlew 的退出码。
#
# 默认加 --no-daemon：这台机器 14 GB 内存、交换区常满，常驻的 Gradle daemon 要占 2 GB 以上。
# 想留着 daemon 做增量构建（无改动时 0.6 秒对 7 秒）：ZCODE_REMOTE_KEEP_DAEMON=1 scripts/build.sh debug
run_gradle() {
    local args=(--console=plain)
    [[ "${ZCODE_REMOTE_KEEP_DAEMON:-0}" == "1" ]] || args+=(--no-daemon)
    (cd "$ZCODE_REMOTE_ROOT" && ./gradlew "$@" "${args[@]}")
}

# build-tools 里版本最高的那个目录（aapt2、apksigner 都在里面）。
# 要指定版本：ZCODE_REMOTE_BUILD_TOOLS=36.0.0 scripts/verify-apk.sh …
build_tools_dir() {
    local want="${ZCODE_REMOTE_BUILD_TOOLS:-}" dir=""
    if [[ -n "$want" ]]; then
        dir="$ANDROID_HOME/build-tools/$want"
        [[ -x "$dir/aapt2" ]] || die "ZCODE_REMOTE_BUILD_TOOLS 指定的目录里没有 aapt2：$dir"
        printf '%s\n' "$dir"
        return
    fi
    dir="$(ls -1d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V \
        | while IFS= read -r d; do [[ -x "${d}aapt2" ]] && printf '%s\n' "${d%/}"; done | tail -n 1)"
    [[ -n "$dir" ]] || die "在 $ANDROID_HOME/build-tools 下找不到带 aapt2 的版本目录。"
    printf '%s\n' "$dir"
}
