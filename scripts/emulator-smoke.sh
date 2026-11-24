#!/usr/bin/env bash
# 交付前的实跑：在无界面的模拟器里把安装包装上、启动、用 VIEW 意图打开链接，逐项检查并留下截图。
#
# 用法：scripts/emulator-smoke.sh <apk路径>
# 依次做：
#   启动模拟器 → 同包名的旧安装先卸载 → adb install -r → aapt2 读出包名和入口 Activity →
#   清空 logcat → 启动入口 → 等待 → 截图 smoke-01-launch.png → 确认应用在前台 →
#   用 VIEW 意图打开 https://zcode.z.ai/remote/v4?app_version=4.0.0 → 等待 →
#   截图 smoke-02-view-intent.png、导出界面层级 smoke-02-ui.xml →
#   查 logcat 里有没有本应用的 FATAL EXCEPTION → 查有没有「ZCodeRemote: pageLoaded host=zcode.z.ai」且
#   状态码是 2xx（5xx 的错误文档也会照常记一行 pageLoaded，只认这行不认状态码会把后端坏了也判过）→
#   关闭模拟器 → 逐项打印通过 / 不通过。
# 产物都在 artifacts/emulator/ 下。logcat-smoke.log 是最后导出的完整 logcat，排查用。
#
# 返回码：0 全部通过；
#   1 准备阶段就失败了（安装包读不出来、模拟器起不来、装不上、入口启动不了），后面的步骤不再做；
#   2 本应用崩溃；3 应用不在前台；4 没有 2xx 的 pageLoaded；5 其它项不通过。
# 2、3、4 同时出现时只报最靠前的一个。
#
# 可以重复运行：开头由 emulator-boot.sh 关掉上一次的模拟器；结束时（出错或被中断也一样）由 emulator-kill.sh
# 关掉本次启动的模拟器。上一轮留下的截图、界面层级、logcat 开头就先删掉，不会把旧的当成这次的。
#
# 可以用环境变量覆盖：AVD_NAME、ANDROID_HOME、SMOKE_URL、
#   SMOKE_SETTLE_SECONDS（启动入口后先等几秒，默认 10）、
#   SMOKE_PAGE_TIMEOUT（打开链接后最多等几秒新的 pageLoaded 日志，默认 60）、
#   EMULATOR_BOOT_TIMEOUT（转交给 emulator-boot.sh，默认 300）、ZCODE_REMOTE_BUILD_TOOLS（build-tools 版本目录）。

if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -uo pipefail
export LC_ALL=C.UTF-8

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARTIFACT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/artifacts/emulator"

export AVD_NAME="${AVD_NAME:-zcode-remote-smoke}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
SMOKE_URL="${SMOKE_URL:-https://zcode.z.ai/remote/v4?app_version=4.0.0}"
PAGE_LOADED_LOG="ZCodeRemote: pageLoaded host=zcode.z.ai"
BOOT_SCRIPT="$SCRIPT_DIR/emulator-boot.sh"
KILL_SCRIPT="$SCRIPT_DIR/emulator-kill.sh"
ADB_BIN="$ANDROID_HOME/platform-tools/adb"
BOOT_TIMEOUT="${EMULATOR_BOOT_TIMEOUT:-300}"
SETTLE_SECONDS="${SMOKE_SETTLE_SECONDS:-10}"
PAGE_TIMEOUT="${SMOKE_PAGE_TIMEOUT:-60}"
REMOTE_UI_XML="/data/local/tmp/zcode-smoke-ui.xml"

SHOT1="$ARTIFACT_DIR/smoke-01-launch.png"
SHOT2="$ARTIFACT_DIR/smoke-02-view-intent.png"
UI_XML="$ARTIFACT_DIR/smoke-02-ui.xml"
LOGCAT_FILE="$ARTIFACT_DIR/logcat-smoke.log"

APK="" PKG="" PKG_RE="" ACTIVITY="" AAPT2=""
SERIAL="" BOOT_SECONDS=""
BOOTED=0      # 本次有没有试着启动模拟器；试过，收尾就要关。
FINISHED=0    # 有没有走过收尾。
IN_SIGNAL=0   # 收尾时又来了信号，不重复处理。
INTERRUPTED_BY=""   # 被哪个信号中断的。
TMP_DIR=""

log() { printf '[emulator-smoke] %s\n' "$*"; }
step() { printf '→ %s\n' "$*"; }
detail() { printf '    %s\n' "$*"; }
oneline() { tr '\n' ' ' <<<"$1" | sed 's/  */ /g; s/^ //; s/ $//'; }

# ---------------------------------------------------------------- 逐项记录

# 检查项。数组里的顺序就是打印顺序。
ITEMS=(boot uninstall install apkinfo launch shot1 fg1 view shot2 uidump fg2 crash pageloaded kill)
declare -A TITLE=()
TITLE[boot]="启动模拟器（AVD $AVD_NAME）"
TITLE[uninstall]="卸载同包名的旧安装"
TITLE[install]="adb install -r"
TITLE[apkinfo]="aapt2 读出包名和入口 Activity"
TITLE[launch]="清空 logcat 并启动入口 Activity"
TITLE[shot1]="等待后截图 smoke-01-launch.png"
TITLE[fg1]="应用在前台"
TITLE[view]="用 VIEW 意图打开链接"
TITLE[shot2]="等待后截图 smoke-02-view-intent.png"
TITLE[uidump]="导出界面层级 smoke-02-ui.xml"
TITLE[fg2]="打开链接后应用仍在前台"
TITLE[crash]="logcat 里没有本应用的 FATAL EXCEPTION"
TITLE[pageloaded]="logcat 里有「$PAGE_LOADED_LOG」且 status 是 2xx"
TITLE[kill]="关闭模拟器"
declare -A STATE=() NOTE=()
for item in "${ITEMS[@]}"; do STATE[$item]="SKIP"; NOTE[$item]=""; done

mark() { STATE["$1"]="$2"; NOTE["$1"]="${3:-}"; }

print_summary() {
    local n=0 item label failed=() code="$1"
    printf '\n======== 实跑结果：%s（%s）========\n' "${PKG:-（没读出包名）}" "${APK:-}"
    for item in "${ITEMS[@]}"; do
        n=$((n + 1))
        case "${STATE[$item]}" in
            PASS) label="[通过]" ;;
            FAIL) label="[不通过]"; failed+=("${TITLE[$item]}") ;;
            *) label="[未执行]" ;;
        esac
        printf '%s %2d. %s%s\n' "$label" "$n" "${TITLE[$item]}" "${NOTE[$item]:+：${NOTE[$item]}}"
    done
    if [ "$code" = 0 ]; then
        printf '结论：通过。%d 项全部通过。\n' "${#ITEMS[@]}"
    elif [ "$code" -ge 128 ]; then
        printf '结论：不通过。脚本被信号中断（SIG%s），后面的步骤没有做。\n' "${INTERRUPTED_BY:-?}"
    elif [ "$code" = 1 ]; then
        printf '结论：不通过。准备阶段就失败了，后面的步骤没有做。\n'
    else
        printf '结论：不通过（%d 项：%s）。\n' "${#failed[@]}" "$(IFS='、'; echo "${failed[*]}")"
    fi
    printf '本次实跑：模拟器启动 %s 秒，总共 %s 秒。截图和界面层级在 %s 下。\n' \
        "${BOOT_SECONDS:-?}" "$((SECONDS - SCRIPT_STARTED))" "$ARTIFACT_DIR"
}

# 0 全过；2 崩溃；3 不在前台；4 没有 pageLoaded；5 其它项不通过。
compute_exit_code() {
    local item
    if [ "${STATE[crash]}" = FAIL ]; then printf '2'; return; fi
    if [ "${STATE[fg1]}" = FAIL ] || [ "${STATE[fg2]}" = FAIL ]; then printf '3'; return; fi
    if [ "${STATE[pageloaded]}" = FAIL ]; then printf '4'; return; fi
    for item in "${ITEMS[@]}"; do
        if [ "${STATE[$item]}" = FAIL ]; then printf '5'; return; fi
    done
    printf '0'
}

# ---------------------------------------------------------------- 收尾

cleanup() {
    if [ "$BOOTED" = 1 ]; then
        "$KILL_SCRIPT" 2>&1 | sed 's/^/    /'
        BOOTED=0
    fi
    if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then rm -rf "$TMP_DIR"; fi
    TMP_DIR=""
}

# 正常收尾：关模拟器是最后一项检查，要记进结果里。
finish() {
    local code="$1" kill_ok=0 leftovers
    if [ "$BOOTED" = 1 ]; then
        step "14/14 关闭模拟器"
        if "$KILL_SCRIPT" 2>&1 | sed 's/^/    /'; then kill_ok=1; fi
        BOOTED=0
        # 再独立查一遍：/proc 里还有没有命令行带「-avd <名字>」的 emulator / qemu 进程。
        leftovers="$(leftover_emulator_pids | tr '\n' ' ')"
        if [ "$kill_ok" = 1 ] && [ -z "${leftovers// /}" ]; then
            mark kill PASS "模拟器进程已退出"
        else
            mark kill FAIL "emulator-kill.sh 没能关干净，还在跑的进程号：${leftovers:-（没查到）}"
        fi
    fi
    print_summary "$code"
    FINISHED=1
    cleanup
    trap - EXIT INT TERM HUP
    exit "$code"
}

# 准备阶段就失败：后面的步骤做不了。
abort() {
    log "错误：$*"
    finish 1
}

# 没走到 finish 就退出了（脚本内部出错等）：兜底关掉本次启动的模拟器。
on_exit() {
    local rc=$?
    trap - EXIT INT TERM HUP
    if [ "$FINISHED" != 1 ]; then
        log "脚本没能走到收尾（退出码 $rc），兜底关掉本次启动的模拟器。"
        print_summary 1 >&2
        cleanup
    fi
    exit "$rc"
}

on_signal() {
    local sig="$1" code="$2"
    [ "$IN_SIGNAL" = 0 ] || return 0
    IN_SIGNAL=1
    INTERRUPTED_BY="$sig"
    printf '\n'
    log "收到 SIG$sig，先关掉本次启动的模拟器再退出。"
    finish "$code"
}

# ---------------------------------------------------------------- adb 小工具

# 第一个参数是最多等几秒，其余原样交给 adb（已指定串号）。
adb_in() {
    local t="$1"
    shift
    timeout "$t" "$ADB_BIN" -s "$SERIAL" "$@"
}

shq() { local s="$1"; s="${s//\'/\'\\\'\'}"; printf "'%s'" "$s"; }

# 在设备上执行一条命令：每个参数都加上单引号，链接里的 ? 和 = 不会被设备的 shell 吃掉。
shell_q() {
    local t="$1" a args=()
    shift
    for a in "$@"; do args+=("$(shq "$a")"); done
    adb_in "$t" shell "${args[*]}"
}

# 本应用有几个进程在跑（包名，或包名:xxx 这样的附属进程）。
app_process_count() {
    adb_in 30 shell ps -A -o NAME= 2>/dev/null | tr -d '\r' | grep -c -E "^$PKG_RE(:|$)"
}

# 现在栈顶的 Activity 是不是本应用的。
# 变量 top_desc 记下栈顶到底是谁，app_procs 记下本应用的进程数；进程数是 0 说明它已经死了（崩溃后系统会把界面重新拉起来）。
top_desc="" app_procs=0
check_foreground() {
    local acts
    acts="$(adb_in 30 shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
        | grep -E '^ *(topResumedActivity|mResumedActivity)=' | head -n 1)"
    top_desc="$(sed -n 's/^ *\(topResumedActivity\|mResumedActivity\)=ActivityRecord{[^}]* u[0-9]* \([^ ]*\) .*/\2/p' <<<"$acts")"
    app_procs="$(app_process_count)"
    grep -q -E "(topResumedActivity|mResumedActivity)=.* u[0-9]+ $PKG_RE/" <<<"$acts" && [ "$app_procs" -gt 0 ]
}

# 截屏存成 PNG，并确认文件确实是个 PNG。
take_screenshot() {
    local out="$1"
    adb_in 60 exec-out screencap -p >"$out" 2>/dev/null
    [ -s "$out" ] && [ "$(od -An -tx1 -N8 "$out" | tr -d ' \n')" = "89504e470d0a1a0a" ]
}

# 设备上的 logcat 里现在有几行 pageLoaded。读不到记 0。
page_loaded_count() {
    local c
    c="$(adb_in 30 shell "logcat -d -v threadtime 2>/dev/null | grep -c -F -- $(shq "$PAGE_LOADED_LOG")" 2>/dev/null \
        | tr -d '[:space:]')"
    if [[ "$c" =~ ^[0-9]+$ ]]; then printf '%s' "$c"; else printf '0'; fi
}

# 导出界面层级。界面一直在动的时候 uiautomator 会等不到空闲而失败，所以多试几次。
dump_ui() {
    local attempt out
    for attempt in 1 2 3; do
        out="$(shell_q 90 uiautomator dump "$REMOTE_UI_XML" 2>&1 | tr -d '\r')"
        rm -f "$UI_XML"
        if grep -q "dumped to" <<<"$out" && adb_in 30 pull "$REMOTE_UI_XML" "$UI_XML" >/dev/null 2>&1 \
            && grep -q "<hierarchy" "$UI_XML"; then
            adb_in 30 shell rm -f "$REMOTE_UI_XML" >/dev/null 2>&1
            return 0
        fi
        detail "第 $attempt 次导出没成功：$(oneline "$(tail -n 1 <<<"$out")")"
        sleep 2
    done
    return 1
}

# 从日志文件里找出本应用的 FATAL EXCEPTION 整段（到 Process: 那一行为止）。找到了返回 0。
scan_fatal() {
    awk -v preg="$PKG_RE" '
        /FATAL EXCEPTION/ { inblk = 1; n = 0; blk = $0 "\n"; next }
        inblk {
            blk = blk $0 "\n"; n++
            if ($0 ~ /Process: /) {
                if ($0 ~ ("Process: " preg "([,:]|$)")) { printf "%s\n", blk; found = 1 }
                inblk = 0
            } else if (n > 80) inblk = 0
        }
        END { exit found ? 0 : 1 }' "$1"
}

# /proc 里命令行带「-avd <名字>」的 emulator / qemu 进程号，每行一个。只用来核对，不用来杀。
leftover_emulator_pids() {
    local p cmd exe
    for p in /proc/[0-9]*; do
        [ -r "$p/cmdline" ] || continue
        cmd="$(tr '\0' ' ' <"$p/cmdline" 2>/dev/null)" || continue
        exe="${cmd%% *}"
        case "${exe##*/}" in
            emulator|qemu-system-*) ;;
            *) continue ;;
        esac
        case " $cmd" in *" -avd $AVD_NAME "*) printf '%s\n' "${p#/proc/}" ;; esac
    done
    return 0
}

# 系统的包管理器能不能用了（刚开机那几秒可能还没好）。
wait_pm_ready() {
    local waited=0
    while ! adb_in 10 shell pm list packages >/dev/null 2>&1; do
        [ "$waited" -lt 60 ] || return 1
        sleep 2
        waited=$((waited + 2))
    done
    return 0
}

# build-tools 里版本最高、带 aapt2 的目录。
find_aapt2() {
    local want="${ZCODE_REMOTE_BUILD_TOOLS:-}" found=""
    if [ -n "$want" ] && [ -x "$ANDROID_HOME/build-tools/$want/aapt2" ]; then
        printf '%s\n' "$ANDROID_HOME/build-tools/$want/aapt2"
        return 0
    fi
    found="$(ls -1d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V \
        | while IFS= read -r dir; do [ -x "${dir}aapt2" ] && printf '%s\n' "${dir}aapt2"; done | tail -n 1)"
    if [ -n "$found" ]; then printf '%s\n' "$found"; fi
}

# ---------------------------------------------------------------- 准备

SCRIPT_STARTED=$SECONDS

if [ $# -ne 1 ] || [ -z "$1" ]; then
    printf '用法：%s <apk路径>\n' "$0" >&2
    exit 1
fi
if [ ! -x "$ADB_BIN" ]; then log "找不到 adb：$ADB_BIN"; exit 1; fi
if [ ! -x "$BOOT_SCRIPT" ] || [ ! -x "$KILL_SCRIPT" ]; then
    log "找不到或没有执行权限：$BOOT_SCRIPT、$KILL_SCRIPT"
    exit 1
fi
for v in "$SETTLE_SECONDS" "$PAGE_TIMEOUT" "$BOOT_TIMEOUT"; do
    if ! [[ "$v" =~ ^[0-9]+$ ]]; then log "秒数要用非负整数，现在是「$v」"; exit 1; fi
done
APK="$(realpath -e -- "$1" 2>/dev/null)"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then log "找不到安装包：$1"; exit 1; fi
AAPT2="$(find_aapt2)"
if [ -z "$AAPT2" ]; then log "在 $ANDROID_HOME/build-tools 下找不到 aapt2"; exit 1; fi

# 从这里起，只要脚本退出（出错、被中断）都要兜底关掉本次启动的模拟器。
trap on_exit EXIT
trap 'on_signal HUP 129' HUP
trap 'on_signal INT 130' INT
trap 'on_signal TERM 143' TERM

mkdir -p "$ARTIFACT_DIR"
# 上一轮的产物先删掉，免得这次的步骤没走到，却留下旧的截图。
rm -f "$SHOT1" "$SHOT2" "$UI_XML" "$LOGCAT_FILE"

log "安装包：$APK"
log "  大小 $(stat -c %s "$APK") 字节，sha256 $(sha256sum "$APK" | cut -d' ' -f1)"
log "实跑链接：$SMOKE_URL"

# 包名和入口 Activity 从安装包里读。「同包名先卸载」要用包名，所以这一步放在最前面，结果记在第 4 项里。
if ! badging="$("$AAPT2" dump badging "$APK" 2>&1)"; then
    mark apkinfo FAIL "aapt2 dump badging 失败：$(oneline "$badging")"
    abort "安装包读不出来。"
fi
PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
ACTIVITY="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
if [ -z "$PKG" ]; then
    mark apkinfo FAIL "badging 里没有 package: name"
    abort "读不出包名。"
fi
PKG_RE="${PKG//./\\.}"
log "包名：$PKG；入口 Activity：${ACTIVITY:-（badging 里没写，装上后再问系统）}"

# 安装包复制一份到临时目录、换成纯英文名再交给 adb，路径里的中文和空格都碰不到 adb。
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/zcode-emulator-smoke.XXXXXX")" || abort "建不了临时目录。"
APK_TMP="$TMP_DIR/smoke.apk"
cp -f -- "$APK" "$APK_TMP" || abort "复制安装包失败。"

# ---------------------------------------------------------------- 1. 启动模拟器

step "1/14 启动模拟器（最多等 $BOOT_TIMEOUT 秒）"
mem_avail_mb="$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo 2>/dev/null)"
if [[ "$mem_avail_mb" =~ ^[0-9]+$ ]] && [ "$mem_avail_mb" -lt 4000 ]; then
    log "提醒：宿主机可用内存只有约 $mem_avail_mb MB，模拟器自己要 4 GB 左右；同时有别的重活在跑的话可能被系统杀掉。"
fi
BOOTED=1
if boot_out="$(EMULATOR_BOOT_TIMEOUT="$BOOT_TIMEOUT" "$BOOT_SCRIPT" 2>&1)"; then
    printf '%s\n' "$boot_out" | grep -v '^$' | sed 's/^/    /'
    BOOT_SECONDS="$(sed -n 's/^BOOT_SECONDS=//p' <<<"$boot_out" | tail -n 1)"
    SERIAL="$(sed -n 's/^SERIAL=//p' <<<"$boot_out" | tail -n 1)"
    mark boot PASS "用了 ${BOOT_SECONDS:-?} 秒，串号 ${SERIAL:-?}"
else
    boot_rc=$?
    printf '%s\n' "$boot_out" | grep -v '^$' | sed 's/^/    /'
    mark boot FAIL "emulator-boot.sh 退出码 $boot_rc"
    abort "模拟器没起来。"
fi
if [ -z "$SERIAL" ]; then
    mark boot FAIL "emulator-boot.sh 没给出串号"
    abort "拿不到模拟器串号。"
fi

# ---------------------------------------------------------------- 2. 同包名先卸载

step "2/14 卸载同包名的旧安装"
if ! wait_pm_ready; then
    mark uninstall FAIL "等了 60 秒包管理器还没准备好"
    abort "包管理器没准备好，装不了。"
fi
if adb_in 60 shell pm list packages 2>/dev/null | tr -d '\r' | grep -qxF "package:$PKG"; then
    out="$(adb_in 120 uninstall "$PKG" 2>&1 | tr -d '\r')"
    if grep -q "Success" <<<"$out"; then
        mark uninstall PASS "已卸载旧安装 $PKG"
    else
        mark uninstall FAIL "$(oneline "$out")"
    fi
else
    mark uninstall PASS "原本就没装 $PKG"
fi

# ---------------------------------------------------------------- 3. 安装

step "3/14 adb install -r"
out="$(adb_in 300 install -r "$APK_TMP" 2>&1 | tr -d '\r')"
if grep -q "Success" <<<"$out"; then
    mark install PASS "$(basename "$APK")"
else
    mark install FAIL "$(oneline "$out")"
    abort "安装包没装上。"
fi

# ---------------------------------------------------------------- 4. 包名和入口 Activity

step "4/14 aapt2 读出包名和入口 Activity"
if [ -z "$ACTIVITY" ]; then
    # 没有 launchable-activity 这一行时，问系统哪个 Activity 带启动图标。
    ACTIVITY="$(shell_q 30 cmd package resolve-activity --brief -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tr -d '\r' | grep "/" | tail -n 1 | cut -d/ -f2-)"
fi
if [ -n "$ACTIVITY" ]; then
    mark apkinfo PASS "包名 $PKG，入口 $ACTIVITY"
else
    mark apkinfo FAIL "读不到入口 Activity"
    abort "不知道该启动哪个 Activity。"
fi

# ---------------------------------------------------------------- 5. 清空 logcat，启动入口

step "5/14 清空 logcat 并启动入口 Activity"
clear_out="$(adb_in 30 logcat -c -b all 2>&1 | tr -d '\r')"
if [ -n "$clear_out" ]; then detail "清空 logcat 时有提示：$(oneline "$clear_out")"; fi
start_out="$(shell_q 90 am start -W -n "$PKG/$ACTIVITY" 2>&1 | tr -d '\r')"
if grep -q "^Status: ok" <<<"$start_out" && ! grep -q -E "^Error|Exception" <<<"$start_out"; then
    total_ms="$(sed -n 's/^TotalTime: //p' <<<"$start_out" | head -n 1)"
    mark launch PASS "已启动，首帧 ${total_ms:-?} ms"
else
    mark launch FAIL "$(oneline "$start_out")"
    abort "入口 Activity 没启动起来。"
fi

# ---------------------------------------------------------------- 6. 等待，截图

step "6/14 等 $SETTLE_SECONDS 秒后截图"
sleep "$SETTLE_SECONDS"
if take_screenshot "$SHOT1"; then
    mark shot1 PASS "$(basename "$SHOT1")，$(stat -c %s "$SHOT1") 字节"
else
    mark shot1 FAIL "截屏失败或不是 PNG"
fi
# 先留一份 logcat：后面等得久的话，开头几行可能会被冲掉。
adb_in 60 logcat -d -b all -v threadtime >"$TMP_DIR/logcat-phase1.txt" 2>/dev/null

# ---------------------------------------------------------------- 7. 应用在前台

step "7/14 确认应用在前台"
if check_foreground; then
    mark fg1 PASS "栈顶是 $top_desc"
else
    mark fg1 FAIL "栈顶是 ${top_desc:-（读不到栈顶 Activity）}，本应用进程数 $app_procs"
fi

# ---------------------------------------------------------------- 8. 用 VIEW 意图打开链接

step "8/14 用 VIEW 意图打开链接"
# 先记下现在有几行 pageLoaded，发完意图后多出来的就是这次打开链接带来的。
loaded_before="$(page_loaded_count)"
# 先按包名发：只用应用自己声明了接收这个链接的入口，不会落到模拟器自带的浏览器上。
view_out="$(shell_q 90 am start -W -a android.intent.action.VIEW -d "$SMOKE_URL" -p "$PKG" 2>&1 | tr -d '\r')"
if grep -q "^Status: ok" <<<"$view_out" && ! grep -q -E "^Error|Exception" <<<"$view_out"; then
    mark view PASS "VIEW 意图按包名 $PKG 交给了应用自己声明的入口"
else
    detail "按包名发 VIEW 意图，应用没有接：$(oneline "$(grep -m 1 -E '^Error|Exception' <<<"$view_out")")"
    # 应用没声明接收这个链接的入口（VIEW intent-filter 在清单里丢了或被放宽）：这本身就是不通过，
    # verify-apk.sh 的静态检查会拦；这里仍用 -n 把链接直接交给入口 Activity，让后面的截图和日志留现场。
    view_out="$(shell_q 90 am start -W -n "$PKG/$ACTIVITY" -a android.intent.action.VIEW -d "$SMOKE_URL" 2>&1 \
        | tr -d '\r')"
    if grep -q "^Status: ok" <<<"$view_out" && ! grep -q -E "^Error|Exception" <<<"$view_out"; then
        mark view FAIL "应用没有声明接收这个链接的入口（VIEW intent-filter 缺失或被放宽）；-n 只为留现场，不作数"
    else
        mark view FAIL "应用没有声明接收这个链接的入口（VIEW intent-filter 缺失或被放宽）：$(oneline "$view_out")"
    fi
fi

# ---------------------------------------------------------------- 9. 等待，截图

step "9/14 等页面加载（最多 $PAGE_TIMEOUT 秒）后截图"
# 有新的一行 pageLoaded 就不等了；一直没有就等到头，好让截图和界面层级留现场。
waited=0
now="$loaded_before"
while [ "$waited" -lt "$PAGE_TIMEOUT" ]; do
    sleep 2
    waited=$((waited + 2))
    now="$(page_loaded_count)"
    [ "$now" -gt "$loaded_before" ] && break
done
if [ "$now" -gt "$loaded_before" ]; then
    detail "打开链接 $waited 秒后出现了新的 pageLoaded 日志。"
else
    detail "等了 $waited 秒没有出现新的 pageLoaded 日志，继续往下做。"
fi
sleep 3
if take_screenshot "$SHOT2"; then
    mark shot2 PASS "$(basename "$SHOT2")，$(stat -c %s "$SHOT2") 字节"
else
    mark shot2 FAIL "截屏失败或不是 PNG"
fi

# ---------------------------------------------------------------- 10. 界面层级

step "10/14 导出界面层级"
if dump_ui; then
    if grep -q "package=\"$PKG\"" "$UI_XML"; then
        mark uidump PASS "$(basename "$UI_XML")，$(stat -c %s "$UI_XML") 字节"
    else
        mark uidump FAIL "导出了，但里面没有包名 $PKG 的节点"
    fi
else
    mark uidump FAIL "uiautomator dump 试了三次都没成功"
fi

# ---------------------------------------------------------------- 11. 应用仍在前台

step "11/14 确认应用仍在前台"
if check_foreground; then
    mark fg2 PASS "栈顶是 $top_desc"
else
    mark fg2 FAIL "栈顶是 ${top_desc:-（读不到栈顶 Activity）}，本应用进程数 $app_procs"
fi

# ---------------------------------------------------------------- 12. FATAL EXCEPTION

step "12/14 查 logcat 里有没有本应用的 FATAL EXCEPTION"
adb_in 60 logcat -d -b all -v threadtime >"$LOGCAT_FILE" 2>/dev/null
fatal_blocks="" saw_am_crash=0 log_lines=0
for f in "$TMP_DIR/logcat-phase1.txt" "$LOGCAT_FILE"; do
    [ -s "$f" ] || continue
    log_lines=$((log_lines + $(wc -l <"$f")))
    # 开头那份和最后那份内容有重叠，同一段崩溃只留一份。
    if blocks="$(scan_fatal "$f")"; then
        while IFS= read -r line; do
            if [ -n "$line" ] && ! grep -qxF -- "$line" <<<"$fatal_blocks"; then fatal_blocks+="$line"$'\n'; fi
        done <<<"$blocks"
    fi
    if grep -q -E "am_crash: \[[0-9]+,[0-9]+,$PKG_RE," "$f"; then saw_am_crash=1; fi
done
if [ "$log_lines" = 0 ]; then
    mark crash FAIL "导出的 logcat 是空的，没法判断"
elif [ -n "$fatal_blocks" ] || [ "$saw_am_crash" = 1 ]; then
    mark crash FAIL "有 FATAL EXCEPTION（完整 logcat 在 $LOGCAT_FILE）"
    if [ -n "$fatal_blocks" ]; then printf '%s\n' "$fatal_blocks" | head -n 25 | sed 's/^/    /'; fi
    if [ "$saw_am_crash" = 1 ]; then detail "events 日志里也有本应用的 am_crash 记录。"; fi
else
    mark crash PASS "没有"
fi

# ---------------------------------------------------------------- 13. pageLoaded

step "13/14 查有没有「$PAGE_LOADED_LOG」且 status 是 2xx"
# 两份 logcat 都查：开头留下的那份防的是后面的日志把这一行冲掉。
# 只认行首那段不够：页面自己会把 5xx 的错误文档照常走完加载，照样记一行 pageLoaded，所以要认状态码。
hits="$(cat "$TMP_DIR/logcat-phase1.txt" "$LOGCAT_FILE" 2>/dev/null \
    | grep -F -- "$PAGE_LOADED_LOG" | grep -E 'status=2[0-9]{2}' | sort -u | tail -n 3)"
if [ -n "$hits" ]; then
    mark pageloaded PASS "$(printf '%s\n' "$hits" | tail -n 1 | sed 's/^.*\(ZCodeRemote: pageLoaded\)/\1/')"
else
    mark pageloaded FAIL "logcat 里没有状态码为 2xx 的这一行（完整 logcat 在 $LOGCAT_FILE）"
fi

# ---------------------------------------------------------------- 14. 关模拟器，逐项打印

finish "$(compute_exit_code)"
