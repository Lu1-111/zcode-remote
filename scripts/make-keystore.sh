#!/usr/bin/env bash
# 生成 release 签名密钥。只用 JDK 自带的 keytool，不依赖别的工具。
#
#   密钥：keystore/zcode-remote-release.p12（PKCS12，RSA 4096，有效期 30 年）
#   口令：keystore.properties（四个键：storeFile、storePassword、keyAlias、keyPassword）
# 两个文件都不入库（见 .gitignore），权限都是 600。
#
# 已经有密钥时什么都不做。只存在其一时报错退出，绝不悄悄换钥匙：
# 钥匙一换，手机上已装的包就无法覆盖升级，只能卸载重装，App 里的数据全丢。
# 确实要重新开始：先把这两个文件手动移走，再运行本脚本。

# 被 sh 调用时转交 bash（本脚本用了 bash 语法）。
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_toolchain

if [[ $# -ne 0 ]]; then
    printf '用法：%s（不接受参数）\n' "$0" >&2
    exit 2
fi

KEYSTORE_REL="keystore/zcode-remote-release.p12"
KEYSTORE_FILE="$ZCODE_REMOTE_ROOT/$KEYSTORE_REL"
PROPS_FILE="$ZCODE_REMOTE_ROOT/keystore.properties"
ALIAS="zcode-remote"
VALIDITY_DAYS=10950    # 30 年。证书到期后装不了新包，所以给足。

# keytool 的输出固定成英文，方便回读。
KEYTOOL=(-J-Duser.language=en -J-Duser.country=US)

# 相对路径按工程根目录起算；绝对路径原样返回。
resolve_from_root() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *) printf '%s\n' "$ZCODE_REMOTE_ROOT/$1" ;;
    esac
}

if [[ -f "$PROPS_FILE" ]]; then
    # 只读 storeFile 这一行，不碰口令。
    STORE="$(sed -n 's/^storeFile[[:space:]]*=[[:space:]]*//p' "$PROPS_FILE" | head -n 1)"
    if [[ -n "$STORE" && -f "$(resolve_from_root "$STORE")" ]]; then
        printf '已有 release 密钥，沿用：%s\n' "$(resolve_from_root "$STORE")"
        exit 0
    fi
    die "keystore.properties 存在，但它指向的密钥文件不在（storeFile=$STORE）。
release 密钥丢了就不能再覆盖升级已装的包，本脚本不会替你重新生成。
请把原密钥放回原处；确认要换新钥匙，就手动删掉 keystore.properties 再运行。"
fi
if [[ -e "$KEYSTORE_FILE" ]]; then
    die "密钥文件已存在但没有 keystore.properties（口令丢了等于密钥作废）：
  $KEYSTORE_FILE
请手动处理后再运行。"
fi

# 口令：24 字节随机数的十六进制，共 48 个字符，只含 0-9a-f。
# 不含空格、引号、反斜杠、#、!，写进 .properties 不需要转义。
PASS="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
[[ ${#PASS} -eq 48 ]] || die "生成随机口令失败。"

# 之后新建的文件一律 600、新目录 700：文件落盘的那一刻起就只有本人可读。
umask 077
mkdir -p "$ZCODE_REMOTE_ROOT/keystore"
chmod 700 "$ZCODE_REMOTE_ROOT/keystore"

# 口令经环境变量交给 keytool，不出现在命令行里（命令行别的进程用 ps 就看得到）。
if ! ZR_KEYSTORE_PASS="$PASS" "$JAVA_HOME/bin/keytool" "${KEYTOOL[@]}" \
        -genkeypair -noprompt \
        -keystore "$KEYSTORE_FILE" -storetype PKCS12 \
        -alias "$ALIAS" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
        -validity "$VALIDITY_DAYS" \
        -dname "CN=ZCode Remote, OU=ZCode Remote, O=lululu, C=CN" \
        -storepass:env ZR_KEYSTORE_PASS -keypass:env ZR_KEYSTORE_PASS; then
    rm -f "$KEYSTORE_FILE"
    die "keytool 生成密钥失败。"
fi

{
    printf 'storeFile=%s\n' "$KEYSTORE_REL"
    printf 'storePassword=%s\n' "$PASS"
    printf 'keyAlias=%s\n' "$ALIAS"
    printf 'keyPassword=%s\n' "$PASS"
} > "$PROPS_FILE"
chmod 600 "$KEYSTORE_FILE" "$PROPS_FILE"

# 回读一遍：keytool 能用这份口令打开，才说明文件是完好的。
FINGERPRINT="$(ZR_KEYSTORE_PASS="$PASS" "$JAVA_HOME/bin/keytool" "${KEYTOOL[@]}" \
    -list -v -keystore "$KEYSTORE_FILE" -storepass:env ZR_KEYSTORE_PASS -alias "$ALIAS" \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1)"
if [[ -z "$FINGERPRINT" ]]; then
    die "密钥已生成，但回读失败。请检查：
  密钥 $KEYSTORE_FILE
  口令 $PROPS_FILE"
fi

printf '已生成 release 签名密钥。\n'
printf '  密钥：%s（权限 %s）\n' "$KEYSTORE_FILE" "$(stat -c %a "$KEYSTORE_FILE")"
printf '  口令：%s（权限 %s）\n' "$PROPS_FILE" "$(stat -c %a "$PROPS_FILE")"
printf '  别名：%s    有效期：%s 天\n' "$ALIAS" "$VALIDITY_DAYS"
printf '  证书 SHA-256：%s\n' "$FINGERPRINT"
printf '\n请把这两个文件另行备份。它们不入库（.gitignore）；丢了就再也无法覆盖升级已装的包。\n'
printf '验收时可用 scripts/verify-apk.sh 核对安装包里的证书指纹是否与上面一致。\n'
