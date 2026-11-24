#!/usr/bin/env bash
# 无界面启动安卓模拟器（AVD：zcode-remote-smoke），等到系统开机完成才返回 0。
#
# 用法：scripts/emulator-boot.sh
# 返回后模拟器留在后台运行，用 scripts/emulator-kill.sh 关掉。运行记录都在 artifacts/emulator/ 下：
#   emulator.pid     模拟器进程号
#   emulator.serial  模拟器串号（emulator-5554 这样）
#   emulator.log     模拟器自己的输出
#
# 可以用环境变量覆盖：
#   AVD_NAME                AVD 名字，默认 zcode-remote-smoke
#   ANDROID_HOME            Android SDK 目录，默认 ~/Android/Sdk
#   EMULATOR_BOOT_TIMEOUT   等开机的秒数，默认 300
#   EMULATOR_EXTRA_ARGS     追加给 emulator 的参数，按空格拆开
#
# 开机完成的标志是 sys.boot_completed 变成 1。这时标准输出最后三行是
#   SERIAL=…、EMU_PID=…、BOOT_SECONDS=…（启动用了多少秒，从拉起模拟器算到查到开机完成）。
# 返回码：0 开机完成；1 用法或工具不对；2 硬件加速不可用；3 模拟器中途退出；4 开机超时；
#         129/130/143 等待期间被挂断/中断/终止。除 0 以外都会先关掉本次启动的模拟器。
#
# 可以重复运行：每次开跑前先关掉上一次留下的同一个 AVD 的模拟器。
# 只关这一个 AVD，不动 adb 服务；怎么关都在 emulator-kill.sh 里。

if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -uo pipefail
export LC_ALL=C.UTF-8

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARTIFACT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/artifacts/emulator"
KILL_SCRIPT="$SCRIPT_DIR/emulator-kill.sh"

AVD_NAME="${AVD_NAME:-zcode-remote-smoke}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BOOT_TIMEOUT="${EMULATOR_BOOT_TIMEOUT:-300}"
EMULATOR_EXTRA_ARGS="${EMULATOR_EXTRA_ARGS:-}"
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
ADB_BIN="$ANDROID_HOME/platform-tools/adb"
PID_FILE="$ARTIFACT_DIR/emulator.pid"
SERIAL_FILE="$ARTIFACT_DIR/emulator.serial"
LOG_FILE="$ARTIFACT_DIR/emulator.log"
ADB_TIMEOUT=20

EMU_PID=""

log() { printf '[emulator-boot] %s\n' "$*"; }

# adb 目前认得的模拟器串号，每行一个（只列状态为 device 的）。
emulator_serials() {
    timeout "$ADB_TIMEOUT" "$ADB_BIN" devices 2>/dev/null | tr -d '\r' \
        | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-[0-9]+$/ { print $1 }'
}

# 正在跑本 AVD 的模拟器串号，每行一个；没有就什么都不输出。
find_serials() {
    local serial name
    for serial in $(emulator_serials); do
        name="$(timeout "$ADB_TIMEOUT" "$ADB_BIN" -s "$serial" emu avd name 2>/dev/null \
            | tr -d '\r' | sed -n '1p')"
        [ "$name" = "$AVD_NAME" ] && printf '%s\n' "$serial"
    done
    return 0
}

# 这个进程号是不是本 AVD 的模拟器。看的是 /proc 里的命令行，防止进程号被别的程序复用。
# find_serials 和这个函数和 emulator-kill.sh 里的是同一份，改一边要同时改另一边。
pid_is_our_emulator() {
    local pid="$1" cmd exe
    [[ "$pid" =~ ^[0-9]+$ ]] && [ -r "/proc/$pid/cmdline" ] || return 1
    cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)" || return 1
    exe="${cmd%% *}"
    case "${exe##*/}" in
        emulator|qemu-system-*) ;;
        *) return 1 ;;
    esac
    case " $cmd" in
        *" -avd $AVD_NAME "*) return 0 ;;
    esac
    return 1
}

# 本次启动的进程现在的状态：0 是本 AVD 的模拟器；1 进程已经没了；2 还在，但认不出是本 AVD 的模拟器。
# 2 会出现在刚拉起的一两毫秒里：子进程要先经过 setsid 和 emulator 启动器才变成 qemu，这期间命令行里还没有 -avd。
emulator_state() {
    local state
    pid_is_our_emulator "$EMU_PID" && return 0
    if [ ! -e "/proc/$EMU_PID" ]; then return 1; fi
    state="$(sed -n 's/^.*) \([A-Z]\) .*/\1/p' "/proc/$EMU_PID/stat" 2>/dev/null)"
    [ "$state" = Z ] && return 1
    return 2
}

# 刚拉起时等它变成模拟器，最多等 limit 秒。0 认出来了；1 进程没了；2 等到头还认不出。
wait_until_emulator() {
    local limit="$1" waited=0
    while :; do
        emulator_state
        case $? in
            0) return 0 ;;
            1) return 1 ;;
        esac
        [ "$waited" -lt $((limit * 10)) ] || return 2
        sleep 0.1
        waited=$((waited + 1))
    done
}

# 本次启动的模拟器由 emulator-kill.sh 收拾（它认 emulator.pid，也认 AVD 名字）。
stop_own_emulator() {
    # 没法用 adb 转告时（比如还没开机），不必白等默认的 30 秒。
    EMULATOR_KILL_GRACE=10 "$KILL_SCRIPT"
}

boot_failed() {
    local code="$1" reason="$2"
    log "启动失败：$reason"
    log "emulator.log 的最后 40 行（去掉了很长的 adb 公钥那一行）："
    tail -n 40 "$LOG_FILE" 2>/dev/null | grep -v "qemu.adb.pubkey" | sed 's/^/    /'
    stop_own_emulator
    trap - INT TERM HUP
    exit "$code"
}

on_signal() {
    local sig="$1" code="$2"
    printf '\n'
    log "收到 SIG$sig，先关掉本次启动的模拟器再退出"
    stop_own_emulator
    trap - INT TERM HUP
    exit "$code"
}

# ---------------------------------------------------------------- 检查参数和工具

if [ $# -gt 0 ]; then
    printf '用法：%s（不带参数，配置用环境变量）\n' "$0" >&2
    exit 1
fi
if ! [[ "$BOOT_TIMEOUT" =~ ^[0-9]+$ ]] || [ "$BOOT_TIMEOUT" -lt 1 ]; then
    log "EMULATOR_BOOT_TIMEOUT 要是正整数秒，现在是「$BOOT_TIMEOUT」"
    exit 1
fi
if [ ! -x "$EMULATOR_BIN" ]; then
    log "找不到 emulator：$EMULATOR_BIN。先用 sdkmanager 装「emulator」。"
    exit 1
fi
if [ ! -x "$ADB_BIN" ]; then
    log "找不到 adb：$ADB_BIN。先用 sdkmanager 装「platform-tools」。"
    exit 1
fi
if [ ! -x "$KILL_SCRIPT" ]; then
    log "找不到或没有执行权限：$KILL_SCRIPT"
    exit 1
fi
AVD_HOME="${ANDROID_AVD_HOME:-${ANDROID_SDK_HOME:-$HOME}/.android/avd}"
if [ ! -f "$AVD_HOME/$AVD_NAME.ini" ]; then
    log "没有这个 AVD：$AVD_NAME（找过 $AVD_HOME/$AVD_NAME.ini）。先建："
    log "  avdmanager create avd -n $AVD_NAME -k \"system-images;android-35;default;x86_64\""
    exit 1
fi

trap 'on_signal HUP 129' HUP
trap 'on_signal INT 130' INT
trap 'on_signal TERM 143' TERM

mkdir -p "$ARTIFACT_DIR"

# 硬件加速：没有它模拟器几乎跑不动，直接停下。
accel_out="$(timeout 60 "$EMULATOR_BIN" -accel-check 2>&1)"
accel_rc=$?
printf '%s\n' "$accel_out" | sed 's/^/[emulator-boot]   /'
if [ "$accel_rc" -ne 0 ] || ! grep -q "is installed and usable" <<<"$accel_out"; then
    log "硬件加速不可用（emulator -accel-check 退出码 $accel_rc），不启动。"
    exit 2
fi
log "硬件加速可用。"

# 上一次没关干净的同一个 AVD，先关掉。
if ! "$KILL_SCRIPT"; then
    log "上一次的模拟器关不掉，没法再启动同一个 AVD。"
    trap - INT TERM HUP
    exit 1
fi
rm -f "$SERIAL_FILE"

# ---------------------------------------------------------------- 启动

# 用 setsid 让模拟器独占一个进程组：它带起来的 netsimd 等都在这个组里，emulator-kill.sh 按组收拾得干净。
: > "$LOG_FILE"
# EMULATOR_EXTRA_ARGS 就是要按空格拆开。
# shellcheck disable=SC2086
if command -v setsid >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    setsid "$EMULATOR_BIN" -avd "$AVD_NAME" \
        -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
        $EMULATOR_EXTRA_ARGS </dev/null >"$LOG_FILE" 2>&1 &
else
    # shellcheck disable=SC2086
    "$EMULATOR_BIN" -avd "$AVD_NAME" \
        -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
        $EMULATOR_EXTRA_ARGS </dev/null >"$LOG_FILE" 2>&1 &
fi
EMU_PID=$!
printf '%s\n' "$EMU_PID" >"$PID_FILE"
log "已拉起模拟器：AVD $AVD_NAME，进程 $EMU_PID，输出写到 $LOG_FILE"
wait_until_emulator 10
case $? in
    0) ;;
    1) boot_failed 3 "模拟器进程 $EMU_PID 刚拉起就退出了。" ;;
    *) boot_failed 3 "拉起 10 秒后，进程 $EMU_PID 的命令行里仍然认不出是 AVD $AVD_NAME 的模拟器。" ;;
esac

# ---------------------------------------------------------------- 等开机

started_at=$SECONDS
last_report=0
unknown_since=-1
while :; do
    emulator_state
    case $? in
        0) unknown_since=-1 ;;
        1) boot_failed 3 "模拟器进程 $EMU_PID 在开机完成前退出了。" ;;
        *)
            # 认不出了：先宽限 3 秒（万一它正在重新执行别的程序），还不行就当失败。
            [ "$unknown_since" -ge 0 ] || unknown_since=$SECONDS
            [ $((SECONDS - unknown_since)) -lt 3 ] || boot_failed 3 "进程 $EMU_PID 已经不是 AVD $AVD_NAME 的模拟器了。"
            ;;
    esac
    elapsed=$((SECONDS - started_at))
    if [ "$elapsed" -ge "$BOOT_TIMEOUT" ]; then
        boot_failed 4 "等了 $BOOT_TIMEOUT 秒还没开机完成。"
    fi
    serial="$(find_serials | sed -n '1p')"
    if [ -n "$serial" ]; then
        boot_completed="$(timeout "$ADB_TIMEOUT" "$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null \
            | tr -d '[:space:]')"
        if [ "$boot_completed" = "1" ]; then
            break
        fi
    fi
    if [ $((elapsed - last_report)) -ge 15 ]; then
        last_report=$elapsed
        log "已等 $elapsed 秒，${serial:+串号 $serial，}还没开机完成…"
    fi
    sleep 2
done
boot_seconds=$((SECONDS - started_at))
printf '%s\n' "$serial" >"$SERIAL_FILE"

trap - INT TERM HUP
log "开机完成：用了 $boot_seconds 秒（AVD $AVD_NAME，串号 $serial，进程 $EMU_PID）。"
printf 'SERIAL=%s\nEMU_PID=%s\nBOOT_SECONDS=%s\n' "$serial" "$EMU_PID" "$boot_seconds"
exit 0
