#!/usr/bin/env bash
# 在本机起一个简单的 HTTP 服务（python3 -m http.server），把 dist/ 里的安装包传到手机。
#
# 默认端口 8765、监听所有网卡、只提供 dist/ 目录。启动前打印手机可用的下载地址
# （局域网地址；装了 Tailscale 的话一并打印）。按 Ctrl-C 停止。
#
# 端口被占或只想监听某个地址时用环境变量：
#   PORT=8766 scripts/serve-apk.sh
#   BIND=192.168.1.10 scripts/serve-apk.sh
# 这是个不带任何防护的静态文件服务，只在信得过的网络里临时用，传完就关。

# 被 sh 调用时转交 bash（本脚本用了 bash 语法）。
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_python3

if [[ $# -ne 0 ]]; then
    printf '用法：%s（不带参数；需要时用环境变量 PORT、BIND 覆盖）\n' "$0" >&2
    exit 2
fi

PORT="${PORT:-8765}"
BIND="${BIND:-0.0.0.0}"
DIST="$ZCODE_REMOTE_ROOT/dist"

[[ "$PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( PORT <= 65535 )) || die "端口不合法：$PORT（用 PORT=<端口> 覆盖）"
[[ -d "$DIST" ]] || die "还没有 dist/。先运行 scripts/build.sh debug 或 scripts/build.sh release。"

APKS=()
while IFS= read -r file; do APKS+=("$(basename "$file")"); done < <(find "$DIST" -maxdepth 1 -type f -name '*.apk' | sort)
(( ${#APKS[@]} > 0 )) || die "dist/ 里没有 .apk。先运行 scripts/build.sh debug 或 scripts/build.sh release。"

# 探一下这个地址和端口能不能绑上；能绑上就不输出任何内容，绑不上就输出原因。
bind_problem() {
    python3 -I -c '
import socket, sys
sock = socket.socket(socket.AF_INET)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind((sys.argv[1], int(sys.argv[2])))
except OSError as exc:
    print(exc.strerror or exc)
    sys.exit(1)
' "$BIND" "$PORT" 2>&1 || true
}
PROBLEM="$(bind_problem)"
if [[ -n "$PROBLEM" ]]; then
    die "$BIND:$PORT 绑不上（$PROBLEM）：多半是端口被别的程序占了。
  换个端口：      PORT=8766 $0
  或只听局域网：  BIND=<本机局域网地址> $0（别的程序只听 127.0.0.1 时可以共存）"
fi

# ---------- 找出手机可能访问得到的本机地址 ----------

# 本机处于启用状态的 IPv4 地址，一行一个「网卡<TAB>地址」。
local_ipv4() {
    local candidate
    for candidate in "$(command -v ip 2>/dev/null || true)" /usr/sbin/ip /sbin/ip /bin/ip; do
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            "$candidate" -4 -o addr show up 2>/dev/null | awk '$3 == "inet" { split($4, a, "/"); print $2 "\t" a[1] }'
            return 0
        fi
    done
    if command -v hostname >/dev/null 2>&1; then
        hostname -I 2>/dev/null | tr ' ' '\n' | awk 'NF { print "?\t" $0 }'
    fi
}

# 取 IPv4 地址的第 N 段（N 为 1 到 4）。
octet() {
    local IFS=.
    local -r parts=($1)
    printf '%s\n' "${parts[$(( $2 - 1 ))]}"
}

is_tailscale_address() {
    # 100.64.0.0/10
    [[ "$(octet "$1" 1)" == 100 ]] || return 1
    local second
    second="$(octet "$1" 2)"
    (( second >= 64 && second <= 127 ))
}

is_ignorable_address() {
    local first second
    first="$(octet "$1" 1)"
    second="$(octet "$1" 2)"
    if [[ "$first" == 127 ]]; then return 0; fi                 # 回环
    if [[ "$first" == 169 && "$second" == 254 ]]; then return 0; fi    # 链路本地
    # 198.18.0.0/15：本机代理的 TUN 网卡用的 fake-ip 网段，手机访问不到。
    if [[ "$first" == 198 && ( "$second" == 18 || "$second" == 19 ) ]]; then return 0; fi
    return 1
}

is_container_interface() {
    case "$1" in docker* | br-* | veth* | virbr* | vmnet* | lxcbr* | zt*) return 0 ;; esac
    return 1
}

tailscale_ipv4() {
    local candidate cli=""
    for candidate in "$(command -v tailscale 2>/dev/null || true)" /usr/bin/tailscale /usr/local/bin/tailscale /usr/sbin/tailscale; do
        if [[ -n "$candidate" && -x "$candidate" ]]; then cli="$candidate"; break; fi
    done
    if [[ -n "$cli" ]]; then
        local ip
        ip="$("$cli" ip -4 2>/dev/null | awk 'NR == 1 && NF { print $1 }' || true)"
        if [[ -n "$ip" ]]; then printf '%s\n' "$ip"; return 0; fi
    fi
    # 没有 Tailscale 命令行（或它没在运行），就看有没有叫 tailscale* 的网卡。
    local ifname addr
    while IFS=$'\t' read -r ifname addr; do
        if [[ "$ifname" == tailscale* && -n "$addr" && "$addr" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] \
                && is_tailscale_address "$addr"; then
            printf '%s\n' "$addr"
            return 0
        fi
    done < <(local_ipv4)
    return 0
}

LAN=()   # 每项「网卡<TAB>地址」
while IFS=$'\t' read -r ifname addr; do
    [[ -n "$addr" ]] || continue
    # 只认 IPv4 点分四段；别的地址（比如 hostname -I 可能给出的 IPv6）直接跳过。
    [[ "$addr" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
    if is_ignorable_address "$addr"; then continue; fi
    if is_tailscale_address "$addr"; then continue; fi
    if is_container_interface "$ifname"; then continue; fi
    LAN+=("$ifname"$'\t'"$addr")
done < <(local_ipv4)
TAILSCALE_IP="$(tailscale_ipv4 || true)"

# print_urls <地址>：打印每个安装包和目录页的下载地址。
print_urls() {
    local base="http://$1:$PORT" name
    for name in "${APKS[@]}"; do printf '  %s/%s\n' "$base" "$name"; done
    printf '  目录页 %s/\n' "$base"
}

printf '安装包目录：%s\n' "$DIST"
printf '监听：%s:%s\n\n' "$BIND" "$PORT"
if [[ "$BIND" == "0.0.0.0" ]]; then
    if (( ${#LAN[@]} == 0 )); then
        printf '没找到局域网地址。请自己查看本机 IP（ip addr），再拼下载地址。\n' >&2
    else
        printf '手机和电脑连同一个局域网时，在手机浏览器打开：\n'
        for entry in "${LAN[@]}"; do
            printf '（网卡 %s）\n' "${entry%%$'\t'*}"
            print_urls "${entry##*$'\t'}"
        done
    fi
    if [[ -n "$TAILSCALE_IP" ]]; then
        printf '\n走了 Tailscale 时，在手机浏览器打开：\n'
        print_urls "$TAILSCALE_IP"
    fi
else
    if [[ "$BIND" == 127.* ]]; then
        printf '只监听回环地址，手机访问不到；这只能用来在本机自测。\n' >&2
    else
        printf '在手机浏览器打开（前提是手机能路由到这个地址）：\n'
        print_urls "$BIND"
    fi
fi
printf '\n手机打不开时先确认两台设备在同一网络、电脑防火墙放行了 %s 端口。\n' "$PORT"
printf '按 Ctrl-C 停止。\n\n'

exec python3 -I -m http.server "$PORT" --bind "$BIND" --directory "$DIST"
