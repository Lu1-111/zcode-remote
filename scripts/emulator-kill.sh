#!/usr/bin/env bash
# 关掉 AVD zcode-remote-smoke 的模拟器。只关这一个 AVD，不碰别的模拟器，也不动 adb 服务。
#
# 用法：scripts/emulator-kill.sh
# 顺序：
#   1. 请 adb 转告模拟器自己退出（adb emu kill），最多等 30 秒；
#   2. 还没退，就对 artifacts/emulator/emulator.pid 里记下的进程号发 TERM，最多等 10 秒；
#   3. 还没退，给它所在的整个进程组发 TERM，等 5 秒，再不行发 KILL；
#   4. 模拟器退出后，确认它带起来的进程（netsimd 等，和它同在一个进程组）也都退了，没退的补一刀。
# 进程号先核对过确实是本 AVD 的模拟器才动手，防止它已经被别的程序占用。不用 pkill。
#
# 可以用环境变量覆盖：AVD_NAME、ANDROID_HOME、EMULATOR_KILL_GRACE（第 1 步最多等的秒数，默认 30）。
# 返回码：0 这个 AVD 已经没有模拟器在跑；1 没能关掉；2 用法或工具不对。
#
# 认进程、认串号的两个函数 find_serials / pid_is_our_emulator 和 emulator-boot.sh 里的是同一份，
# 改一边要同时改另一边。emulator-boot.sh 启动失败或被中断时也是调用这个脚本来收拾的。

if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -uo pipefail
export LC_ALL=C.UTF-8

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARTIFACT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/artifacts/emulator"

AVD_NAME="${AVD_NAME:-zcode-remote-smoke}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
GRACE_ADB="${EMULATOR_KILL_GRACE:-30}"
ADB_BIN="$ANDROID_HOME/platform-tools/adb"
PID_FILE="$ARTIFACT_DIR/emulator.pid"
SERIAL_FILE="$ARTIFACT_DIR/emulator.serial"
ADB_TIMEOUT=20
GRACE_TERM=10
GRACE_GROUP=5
GRACE_KILL=5
GRACE_CHILD=5

EMU_PID=""

log() { printf '[emulator-kill] %s\n' "$*"; }

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

# 模拟器是用 setsid 拉起来的，自己就是进程组的组长；它带起来的 netsimd 等都在这个组里。
# 组里除了组长以外还活着的进程，每行一个。
group_members() {
    local p fields
    for p in /proc/[0-9]*; do
        [ -r "$p/stat" ] || continue
        # 「pid (comm) 后面才是固定格式」：去掉 pid 和可能带空格的 comm，剩下 state ppid pgrp session …
        fields="$(sed 's/^.*) //' "$p/stat" 2>/dev/null)" || continue
        [ -n "$fields" ] || continue
        # shellcheck disable=SC2206  # 就是要按空格拆开
        set -- $fields
        # 剩下的依次是 state、ppid、pgrp、session…；这里看的是进程组。
        [ "${3:-}" = "$EMU_PID" ] || continue
        [ "${p#/proc/}" = "$EMU_PID" ] && continue
        [ "$1" = Z ] && continue
        printf '%s\n' "${p#/proc/}"
    done
    return 0
}

# 只给模拟器进程本身发信号，让它自己按顺序关掉带起来的进程。
signal_pid() {
    pid_is_our_emulator "$EMU_PID" && kill -"$1" "$EMU_PID" 2>/dev/null
}

# 给整个进程组发信号。模拟器是组长时才发；进程号先核对过，不会误伤别的组。
signal_group() {
    local sig="$1" pgid
    pid_is_our_emulator "$EMU_PID" || return 0
    pgid="$(ps -o pgid= -p "$EMU_PID" 2>/dev/null | tr -d '[:space:]')"
    [ "$pgid" = "$EMU_PID" ] && kill -"$sig" -- "-$EMU_PID" 2>/dev/null
}

# 进程没了，并且 adb 里也看不到这个 AVD 的串号。
still_running() {
    pid_is_our_emulator "$EMU_PID" && return 0
    [ -n "$(find_serials)" ] && return 0
    return 1
}

wait_until_gone() {
    local limit="$1" waited=0
    still_running || return 0
    while [ "$waited" -lt "$limit" ]; do
        sleep 1
        waited=$((waited + 1))
        still_running || return 0
    done
    return 1
}

# 模拟器退出后，把它带起来的进程收干净：通常它们马上跟着退，这里最多等 5 秒，没退的发 TERM，再不退发 KILL。
sweep_group() {
    local waited=0 left p
    [ -n "$EMU_PID" ] || return 0
    left="$(group_members)"
    while [ -n "$left" ] && [ "$waited" -lt "$GRACE_CHILD" ]; do
        sleep 1
        waited=$((waited + 1))
        left="$(group_members)"
    done
    if [ -n "$left" ]; then
        log "模拟器已经退出，但它带起来的进程还没退（进程号 $(printf '%s ' $left)），发 TERM"
        for p in $left; do kill -TERM "$p" 2>/dev/null; done
        sleep 3
        left="$(group_members)"
        if [ -n "$left" ]; then
            for p in $left; do kill -KILL "$p" 2>/dev/null; done
            sleep 1
        fi
    fi
    left="$(group_members)"
    if [ -n "$left" ]; then
        log "它带起来的进程没能全部退出：$(printf '%s ' $left)"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------- 检查参数

if [ $# -gt 0 ]; then
    printf '用法：%s（不带参数，配置用环境变量）\n' "$0" >&2
    exit 2
fi
if ! [[ "$GRACE_ADB" =~ ^[0-9]+$ ]]; then
    log "EMULATOR_KILL_GRACE 要是整数秒，现在是「$GRACE_ADB」"
    exit 2
fi
if [ ! -x "$ADB_BIN" ]; then
    log "找不到 adb：$ADB_BIN。先用 sdkmanager 装「platform-tools」。"
    exit 2
fi

# ---------------------------------------------------------------- 找到要关的模拟器

pid=""
[ -r "$PID_FILE" ] && pid="$(tr -d '[:space:]' <"$PID_FILE" 2>/dev/null)"
if [ -n "$pid" ] && pid_is_our_emulator "$pid"; then
    EMU_PID="$pid"
else
    # 没记过，或者记下的进程号已经是别的程序的了：这份记录作废。
    rm -f "$PID_FILE"
fi
mapfile -t serials < <(find_serials)

if [ -z "$EMU_PID" ] && [ "${#serials[@]}" -eq 0 ]; then
    rm -f "$SERIAL_FILE"
    log "没有 $AVD_NAME 的模拟器在跑，不用关。"
    exit 0
fi
[ -n "$EMU_PID" ] && log "在跑的模拟器：进程 $EMU_PID"
[ "${#serials[@]}" -gt 0 ] && log "adb 看到的串号：${serials[*]}"

# 1. adb emu kill
for serial in ${serials[@]+"${serials[@]}"}; do
    out="$(timeout "$ADB_TIMEOUT" "$ADB_BIN" -s "$serial" emu kill 2>&1 | tr -d '\r')"
    log "adb -s $serial emu kill → ${out:-（没有输出）}"
done
if ! wait_until_gone "$GRACE_ADB"; then
    if [ -n "$EMU_PID" ]; then
        # 2. TERM 给模拟器进程本身
        log "adb emu kill 之后 $GRACE_ADB 秒还没退，发 TERM（进程 $EMU_PID）"
        signal_pid TERM
        if ! wait_until_gone "$GRACE_TERM"; then
            # 3. TERM 给整个进程组，再不行 KILL
            log "TERM 之后 $GRACE_TERM 秒还没退，给整个进程组发 TERM"
            signal_group TERM
            if ! wait_until_gone "$GRACE_GROUP"; then
                log "还没退，发 KILL（进程 $EMU_PID 和它带起来的进程）"
                signal_group KILL
                wait_until_gone "$GRACE_KILL" || true
            fi
        fi
    else
        log "不知道它的进程号（$PID_FILE 没有，或已作废），adb emu kill 也没能让它退出。"
    fi
fi

# 4. 确认模拟器和它带起来的进程都没了
if still_running; then
    log "没能关掉 $AVD_NAME 的模拟器。"
    exit 1
fi
sweep_group || exit 1
rm -f "$PID_FILE" "$SERIAL_FILE"
log "$AVD_NAME 的模拟器已经退出。"
exit 0
