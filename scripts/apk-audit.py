#!/usr/bin/env python3
"""安装包静态验收的判定逻辑。入口是 scripts/verify-apk.sh，一般不直接调用。

只用标准库。清单用 build-tools 里的 aapt2 读取。
原则：任何一项解析不了、判断不了、检查过程出了错，都按「不通过」处理——验收脚本宁可误报，不可放过。

用法：apk-audit.py --apk <安装包> --aapt2 <aapt2 路径> --apksigner <apksigner 路径>
退出码：0 全部通过；1 有不通过项，或安装包、清单读不了；2 用法错误。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

EXPECTED_PACKAGE = "com.lululu.zcoderemote"
MAIN_ACTIVITY = "com.lululu.zcoderemote.MainActivity"

# 主 Activity 的 intent-filter 必须恰好是这三个（方案第 11 条的「分享/打开链接」入口 + 启动图标）：
# 一个不多、一个不少、范围不放宽。签名 = (动作集合, 类别集合, data 各属性的取值集合)。
EXPECTED_MAIN_FILTERS = (
    (("android.intent.action.MAIN",), ("android.intent.category.LAUNCHER",), ()),
    (
        ("android.intent.action.VIEW",),
        ("android.intent.category.BROWSABLE", "android.intent.category.DEFAULT"),
        (("host", ("zcode.z.ai",)), ("pathPrefix", ("/remote/v4",)), ("scheme", ("https",))),
    ),
    (
        ("android.intent.action.SEND",),
        ("android.intent.category.DEFAULT",),
        (("mimeType", ("text/plain",)),),
    ),
)

# dataExtractionRules 里 cloud-backup 和 device-transfer 两段都必须整域排除的存储域（方案第 9 条）。
BACKUP_RULE_SECTIONS = ("cloud-backup", "device-transfer")
BACKUP_EXCLUDED_DOMAINS = ("root", "file", "database", "sharedpref")

# 允许申请的权限。要增删就改这里，并同步 docs/ARCHITECTURE.md「安全红线」。
ALLOWED_PERMISSIONS = ("android.permission.INTERNET", "android.permission.CAMERA")

# 白名单之外唯一的例外，是 AndroidX（androidx.core）打包时自动加入的一条应用私有权限：
#   「本包名.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION」。
# 来由：它由本 App 自己声明、签名级（protectionLevel=0x2），只有和本 App 用同一张证书签名的程序
# 才可能持有，也不对应任何系统资源。它是 AndroidX 用来保护「不导出的动态接收器」的加固措施，
# 不是对外申请的能力，所以放行它是准确的，不算放宽。来由同样写在 docs/ARCHITECTURE.md。
#   - 放行要两个条件同时满足：名称精确相等，且清单里由本 App 声明、protectionLevel 是签名级（0x2）。
#   - 不要用 tools:node="remove" 把它去掉：那会削弱 Android 13 以下 RECEIVER_NOT_EXPORTED 的保护。
#   - 以后再出现别的 AndroidX 自动加入的私有权限，这里不会放行：验收判不通过并指名是哪一条，
#     由人决定要不要加例外。
SELF_PROTECTION_PERMISSION = EXPECTED_PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
PROTECTION_LEVEL_SIGNATURE = 0x2

# 算作「组件」的清单元素。
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


class AuditError(Exception):
    """aapt2 的输出读不了或看不懂。"""


def run(command: list[str]) -> subprocess.CompletedProcess:
    # 工程路径里有中文。JDK 17 在 C/POSIX locale 下处理不了这样的文件名（会变成问号后报找不到文件），
    # 所以这里钉成 C.UTF-8：工具的输出是英文，路径按 UTF-8 处理。
    env = dict(os.environ, LC_ALL="C.UTF-8")
    try:
        return subprocess.run(
            command, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env
        )
    except OSError as exc:
        raise AuditError(f"运行 {command[0]} 失败：{exc}") from exc


def aapt2_output(command: list[str]) -> str:
    proc = run(command)
    if proc.returncode != 0:
        raise AuditError(
            f"{' '.join(command[:4])} 退出码 {proc.returncode}：{(proc.stderr or proc.stdout).strip()[:300]}"
        )
    return proc.stdout


# ---------- aapt2 输出解析 ----------

def parse_value(raw: str) -> tuple[str, object]:
    """把 aapt2 打印的属性值归成 (类型, 值)。新旧两版 aapt2 的写法都认：
    新版布尔直接印 true/false、整数印十进制或十六进制；旧版带 (type 0x12) 前缀和 (line=N) 后缀。"""
    raw = re.sub(r"\s*\(line=\d+\)\s*$", "", raw.strip())
    typed = re.match(r"^\(type 0x[0-9a-fA-F]+\)(.*)$", raw)
    if typed:
        raw = typed.group(1).strip()
    if raw.startswith('"'):
        end = raw.find('"', 1)
        if end < 0:
            raise AuditError(f"属性值的引号没有配对：{raw[:80]}")
        return "string", raw[1:end]
    if raw.startswith("@"):
        return "reference", raw
    if raw in ("true", "false"):
        return "boolean", raw == "true"
    if re.fullmatch(r"-?0x[0-9a-fA-F]+", raw):
        return "integer", int(raw, 16)
    if re.fullmatch(r"-?[0-9]+", raw):
        return "integer", int(raw, 10)
    return "other", raw


def truthy(attr: tuple[str, object] | None) -> bool | None:
    """布尔类属性的真假。属性不存在返回 None；类型不对（比如指向一个资源）也返回 None，调用方按判断不了处理。"""
    if attr is None:
        return None
    kind, data = attr
    if kind == "boolean":
        return bool(data)
    if kind == "integer":
        return int(data) != 0
    return None


def local_name(raw_name: str) -> str:
    """http://schemas.android.com/apk/res/android:name(0x01010003) → name；package → package。"""
    return re.sub(r"\(0x[0-9a-fA-F]+\)$", "", raw_name.strip()).rsplit(":", 1)[-1]


class Node:
    def __init__(self, tag: str, line: str | None) -> None:
        self.tag = tag
        self.line = line
        self.attrs: dict[str, tuple[str, object]] = {}
        self.children: list[Node] = []

    def attr(self, name: str) -> tuple[str, object] | None:
        return self.attrs.get(name)

    def string_attr(self, name: str) -> str | None:
        attr = self.attr(name)
        return str(attr[1]) if attr is not None else None

    def walk(self):
        yield self
        for child in self.children:
            yield from child.walk()


ELEMENT_RE = re.compile(r"^E: (\S+)(?: \(line=(\d+)\))?$")
ATTR_RE = re.compile(r"^A: (.+?)=(.+)$")
RESOURCE_HEADER_RE = re.compile(r"^\s*resource (0x[0-9a-fA-F]+) \S+$")
RESOURCE_XML_PATH_RE = re.compile(r"\bres/\S+\.xml\b")


def parse_tree(text: str) -> list[Node]:
    """解析 aapt2 dump xmltree 的输出。靠缩进还原层级：元素行的缩进比它的父元素深，
    属性行紧跟在所属元素（或它的前一个属性）后面，缩进比所属元素深一级。"""
    roots: list[Node] = []
    stack: list[tuple[int, Node]] = []
    for raw in text.splitlines():
        stripped = raw.strip()
        # N: 命名空间声明，C: 注释，T: 元素的文本内容（例如网络安全配置里的 <domain>），***: 标题行。
        if not stripped or stripped.startswith(("N:", "C:", "T:", "***")):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        element = ELEMENT_RE.match(stripped)
        if element:
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = Node(element.group(1), element.group(2))
            (stack[-1][1].children if stack else roots).append(node)
            stack.append((indent, node))
            continue
        attr = ATTR_RE.match(stripped)
        if attr and stack:
            stack[-1][1].attrs[local_name(attr.group(1))] = parse_value(attr.group(2))
            continue
        raise AuditError(f"aapt2 dump xmltree 的输出里有看不懂的行：{stripped[:80]}")
    return roots


def parse_badging(text: str) -> dict:
    info: dict = {"permissions": [], "implied_permissions": [], "debuggable": False,
                  "cleartext_permitted": None, "package": None, "version_code": "?",
                  "version_name": "?", "min_sdk": "?", "target_sdk": "?"}
    for line in text.splitlines():
        package = re.match(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", line)
        if package:
            info["package"], info["version_code"], info["version_name"] = package.groups()
        elif re.match(r"^(minSdkVersion|sdkVersion):", line):
            info["min_sdk"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("targetSdkVersion:"):
            info["target_sdk"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("uses-permission: name='"):
            info["permissions"].append(line[len("uses-permission: name='"):].split("'")[0])
        elif line.startswith("uses-implied-permission: name='"):
            info["implied_permissions"].append(line[len("uses-implied-permission: name='"):].split("'")[0])
        elif line == "application-debuggable":
            info["debuggable"] = True
        elif line.startswith("application-cleartext-permitted:"):
            info["cleartext_permitted"] = line.split(":", 1)[1].strip()
    if info["package"] is None:
        raise AuditError("badging 输出里没有 package 行，不是有效的安装包。")
    return info


def find_manifest_application(roots: list[Node]) -> tuple[Node, Node]:
    manifests = [node for node in roots if node.tag == "manifest"]
    if len(manifests) != 1:
        raise AuditError(f"清单里应当只有一个 <manifest>，实际读到 {len(manifests)} 个。")
    manifest = manifests[0]
    applications = [node for node in manifest.children if node.tag == "application"]
    if len(applications) != 1:
        raise AuditError(f"清单里应当只有一个 <application>，实际读到 {len(applications)} 个。")
    return manifest, applications[0]


# ---------- 检查项 ----------
# 每个检查函数返回 (是否通过, 说明)。判断不了的情况一律返回不通过，并在说明里写清原因。

def check_package(badging: dict) -> tuple[bool, str]:
    actual = badging["package"]
    return actual == EXPECTED_PACKAGE, f"期望 {EXPECTED_PACKAGE}，实际 {actual}"


def check_not_debuggable(badging: dict, application: Node) -> tuple[bool, str]:
    problems = []
    if badging["debuggable"]:
        problems.append("badging 输出里有 application-debuggable")
    flag = truthy(application.attr("debuggable"))
    if flag:
        problems.append("清单里 android:debuggable=true")
    elif flag is None and application.attr("debuggable") is not None:
        problems.append(f"清单里 android:debuggable 的值判断不了：{application.attr('debuggable')[1]}")
    return not problems, "；".join(problems) or "不可调试"


def check_allow_backup(application: Node) -> tuple[bool, str]:
    attr = application.attr("allowBackup")
    flag = truthy(attr)
    if flag is False:
        return True, "android:allowBackup=false"
    if attr is None:
        return False, "没有声明 android:allowBackup（系统默认允许备份）"
    if flag is True:
        return False, "android:allowBackup=true"
    return False, f"android:allowBackup 的值判断不了：{attr[1]}"


def declared_signature_permission(manifest: Node, name: str) -> bool:
    """本 App 是否把 name 声明成了自己的签名级权限（protectionLevel 恰为 0x2）。"""
    for node in manifest.walk():
        if node.tag != "permission" or node.string_attr("name") != name:
            continue
        attr = node.attr("protectionLevel")
        return attr is not None and attr[0] == "integer" and int(attr[1]) == PROTECTION_LEVEL_SIGNATURE
    return False


def check_permissions(badging: dict, manifest: Node) -> tuple[bool, str]:
    requested = list(dict.fromkeys(badging["permissions"] + badging["implied_permissions"]))
    offenders = [
        name for name in requested
        if name not in ALLOWED_PERMISSIONS
        and not (name == SELF_PROTECTION_PERMISSION and declared_signature_permission(manifest, name))
    ]
    if offenders:
        described = []
        for name in offenders:
            if name == SELF_PROTECTION_PERMISSION:
                described.append(f"{name}（AndroidX 的应用私有权限，但清单里不是由本 App 声明的签名级权限）")
            elif name.startswith(EXPECTED_PACKAGE + "."):
                described.append(f"{name}（应用私有权限，要不要加入例外由人决定）")
            else:
                described.append(name)
        return False, "白名单外：" + "、".join(described)
    # 通过时把系统权限和应用私有权限分开说，不混在一起。
    system = [name for name in requested if name != SELF_PROTECTION_PERMISSION]
    detail = "申请的系统权限：" + ("、".join(system) if system else "没有申请任何权限")
    if SELF_PROTECTION_PERMISSION in requested:
        detail += f"；另有应用私有权限（AndroidX 自动加入，签名级）：{SELF_PROTECTION_PERMISSION}"
    return True, detail


def exported_components(application: Node) -> list[tuple[str, str | None, bool, str | None]]:
    exported = []
    for node in application.walk():
        if node.tag not in COMPONENT_TAGS or truthy(node.attr("enabled")) is False:
            continue
        has_filter = any(child.tag == "intent-filter" for child in node.children)
        attr = node.attr("exported")
        if attr is None:
            # 没写 android:exported 时，系统的默认值是：带 intent-filter 的导出，其余不导出。
            is_exported = has_filter
        else:
            flag = truthy(attr)
            # 值判断不了（比如指向一个资源）就按导出算：宁可多报，不可漏报。
            is_exported = True if flag is None else flag
        exported.append((node.tag, node.string_attr("name"), is_exported, node.line))
    return exported


def check_exported_components(manifest: Node, application: Node) -> tuple[bool, str]:
    in_application = set(id(item) for item in application.walk())
    stray = sorted({node.tag for node in manifest.walk()
                    if node.tag in COMPONENT_TAGS and id(node) not in in_application})
    components = exported_components(application)
    main = [item for item in components if item[0] == "activity" and item[1] == MAIN_ACTIVITY]
    unexpected = [item for item in components
                  if item[2] and not (item[0] == "activity" and item[1] == MAIN_ACTIVITY)]
    problems = []
    if stray:
        problems.append("有组件不在 <application> 里：" + "、".join(stray))
    if len(main) != 1 or not main[0][2]:
        problems.append(f"主 Activity {MAIN_ACTIVITY} 不存在、不唯一或没有导出")
    for tag, name, _, line in unexpected:
        problems.append(f"多出导出组件 <{tag}> {name}（清单第 {line} 行）")
    return not problems, "；".join(problems) or f"导出组件只有 {MAIN_ACTIVITY}"


def filter_signature(node: Node) -> tuple:
    """把一个 intent-filter 归成可比对的签名：动作、类别的集合，加上 data 各属性的取值集合。

    清单里多个 <data> 各写一个属性时，系统按「同一过滤器内属性的并集」匹配；
    这里同样把属性并在一起，所以「scheme=https + host=zcode.z.ai + pathPrefix=/remote/v4」
    分三个 <data> 写和一个 <data> 写全算同一个签名。"""
    actions = tuple(sorted(child.string_attr("name") or "" for child in node.children
                           if child.tag == "action"))
    categories = tuple(sorted(child.string_attr("name") or "" for child in node.children
                             if child.tag == "category"))
    data: dict[str, set[str]] = {}
    for child in node.children:
        if child.tag != "data":
            continue
        for name, value in child.attrs.items():
            data.setdefault(name, set()).add(str(value[1]))
    return actions, categories, tuple(sorted((k, tuple(sorted(v))) for k, v in data.items()))


def describe_filter(signature: tuple) -> str:
    actions, categories, data = signature
    parts = ["action=" + "、".join(actions) if actions else "action=（无）"]
    if categories:
        parts.append("category=" + "、".join(categories))
    parts.append("data=" + ("、".join(f"{k}={'|'.join(v)}" for k, v in data) if data else "（无）"))
    return " ".join(parts)


def check_main_intent_filters(application: Node) -> tuple[bool, str]:
    """主 Activity 的 intent-filter 恰好是约定那三个（EXPECTED_MAIN_FILTERS）。

    「打开链接」「分享」的入口一旦在清单改动中丢失或被放宽（比如 VIEW 多收一个 host），
    只有这道静态门能拦住：实跑冒烟的 VIEW 意图失败时只说明入口不对，不会指出原因。"""
    main = next((node for node in application.children
                 if node.tag == "activity" and node.string_attr("name") == MAIN_ACTIVITY), None)
    if main is None:
        return False, f"清单里没有 {MAIN_ACTIVITY}"
    actual = [filter_signature(child) for child in main.children if child.tag == "intent-filter"]
    expected = list(EXPECTED_MAIN_FILTERS)
    problems = []
    for extra in [sig for sig in actual if sig not in expected]:
        problems.append("多出或被放宽的 intent-filter：" + describe_filter(extra))
    for missing in expected:
        if missing not in actual:
            problems.append("缺少约定的 intent-filter：" + describe_filter(missing))
    if problems:
        return False, "；".join(problems)
    return True, "MAIN/LAUNCHER；VIEW 恰为 https+zcode.z.ai+/remote/v4；SEND 恰为 text/plain"


def backup_rules_problems(apk: str, aapt2: str, rules: tuple[str, object]) -> list[str]:
    """核对 android:dataExtractionRules 指向的规则：两段都把约定存储域整域排除。
    返回问题列表；核对不了也算问题。"""
    try:
        if rules[0] != "reference":
            return [f"android:dataExtractionRules 不是资源引用，没法核对：{rules[1]}"]
        files = referenced_resource_files(apk, aapt2, str(rules[1]))
        if not files:
            return [f"在资源表里找不到 android:dataExtractionRules 指向的资源（{rules[1]}）"]
        problems: list[str] = []
        for path in files:
            tree = parse_tree(aapt2_output([aapt2, "dump", "xmltree", "--file", path, apk]))
            roots = [root for root in tree if root.tag == "data-extraction-rules"]
            if len(roots) != 1:
                problems.append(f"{path} 里应当只有一个 <data-extraction-rules>，实际读到 {len(roots)} 个")
                continue
            sections = {node.tag: node for node in roots[0].children if node.tag in BACKUP_RULE_SECTIONS}
            for tag in BACKUP_RULE_SECTIONS:
                section = sections.get(tag)
                if section is None:
                    problems.append(f"{path} 里缺少 <{tag}> 段")
                    continue
                excluded = {node.string_attr("domain"): node.string_attr("path")
                            for node in section.children if node.tag == "exclude"}
                for domain in BACKUP_EXCLUDED_DOMAINS:
                    if excluded.get(domain) != ".":
                        problems.append(f"{path} 的 <{tag}> 里没有 <exclude domain={domain!r} path='.' />")
                # 这两段里只该有排除：出现 <include> 之类会把数据圈回来，出现别的元素说明规则改了样。
                for node in section.children:
                    if node.tag != "exclude":
                        problems.append(f"{path} 第 {node.line} 行有约定之外的 <{node.tag}>（只允许 exclude）")
        return problems
    except Exception as exc:  # 核对不了就是问题，不能放过。
        return [f"备份规则核对不了（{type(exc).__name__}: {exc}）"]


def check_backup_rules(application: Node, apk: str, aapt2: str) -> tuple[bool, str]:
    rules = application.attr("dataExtractionRules")
    if rules is None:
        return False, "清单里没有 android:dataExtractionRules（部分厂商设备上 allowBackup=false 拦不住设备迁移）"
    problems = backup_rules_problems(apk, aapt2, rules)
    if problems:
        return False, "；".join(problems)
    return True, "cloud-backup 和 device-transfer 两段都整域排除了 root/file/database/sharedpref"


def referenced_resource_files(apk: str, aapt2: str, reference: str) -> list[str]:
    """把清单里的资源引用（如 @0x7f0d0000，networkSecurityConfig、dataExtractionRules 都用）
    解析成 APK 里的文件路径。"""
    resources = aapt2_output([aapt2, "dump", "resources", apk])
    files: list[str] = []
    current = None
    for line in resources.splitlines():
        header = RESOURCE_HEADER_RE.match(line)
        if header:
            current = header.group(1)
            continue
        path = RESOURCE_XML_PATH_RE.search(line)
        if path and current and int(current, 16) == int(reference[1:], 16):
            files.append(path.group(0))
    return files


def nsc_cleartext_problems(apk: str, aapt2: str, config: tuple[str, object]) -> list[str]:
    """核对 android:networkSecurityConfig 指向的配置里有没有放开明文。返回问题列表；核对不了也算问题。"""
    try:
        if config[0] != "reference":
            return [f"android:networkSecurityConfig 不是资源引用，没法核对：{config[1]}"]
        files = referenced_resource_files(apk, aapt2, str(config[1]))
        if not files:
            return [f"在资源表里找不到 android:networkSecurityConfig 指向的资源（{config[1]}）"]
        problems = []
        for path in files:
            tree = parse_tree(aapt2_output([aapt2, "dump", "xmltree", "--file", path, apk]))
            for node in (item for root in tree for item in root.walk()):
                attr = node.attr("cleartextTrafficPermitted")
                if attr is None:
                    continue
                permitted = truthy(attr)
                if permitted:
                    problems.append(f"{path} 第 {node.line} 行 <{node.tag}> 里 cleartextTrafficPermitted=true")
                elif permitted is None:
                    problems.append(f"{path} 第 {node.line} 行 cleartextTrafficPermitted 的值判断不了：{attr[1]}")
        return problems
    except Exception as exc:  # 核对不了就是问题，不能放过。
        return [f"网络安全配置核对不了（{type(exc).__name__}: {exc}）"]


def check_cleartext(badging: dict, application: Node, apk: str, aapt2: str) -> tuple[bool, str]:
    problems = []
    attr = application.attr("usesCleartextTraffic")
    flag = truthy(attr)
    if flag:
        problems.append("清单里 android:usesCleartextTraffic=true")
    elif attr is not None and flag is None:
        problems.append(f"android:usesCleartextTraffic 的值判断不了：{attr[1]}")
    elif attr is None and badging["target_sdk"].isdigit() and int(badging["target_sdk"]) < 28:
        problems.append("没有声明 android:usesCleartextTraffic，且 targetSdk 低于 28（系统默认允许明文）")
    if badging["cleartext_permitted"] == "true":
        problems.append("badging 输出里 application-cleartext-permitted:true")
    config = application.attr("networkSecurityConfig")
    if config is not None:
        problems += nsc_cleartext_problems(apk, aapt2, config)
    if problems:
        return False, "；".join(problems)
    return True, "明文流量已禁止；" + ("网络安全配置里也没有放开" if config is not None else "没有网络安全配置")


def check_signature(apk: str, apksigner: str) -> tuple[bool, str]:
    proc = run([apksigner, "verify", "--verbose", "--print-certs", apk])
    if proc.returncode != 0:
        # 报错里带关键字的行比堆栈末行有用：没有签名、签名坏了还是文件读不了，一眼能看出来。
        lines = [line.strip() for line in (proc.stdout + proc.stderr).splitlines() if line.strip()]
        key_lines = [line for line in lines if re.search(r"ERROR|DOES NOT VERIFY|Exception|MISSING|error:", line)]
        return False, "apksigner verify 没通过：" + "；".join(key_lines[:3] or lines[-1:])[:300]
    digest = re.search(r"Signer #1 certificate SHA-256 digest: (\S+)", proc.stdout)
    return True, f"签名证书 SHA-256：{digest.group(1) if digest else '（没读到）'}"


def human_size(size: int) -> str:
    return f"{size} 字节（{size / 1024 / 1024:.2f} MiB）"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="安装包静态验收的判定逻辑。一般经 scripts/verify-apk.sh 调用。")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner", required=True)
    args = parser.parse_args(argv)

    try:
        badging = parse_badging(aapt2_output([args.aapt2, "dump", "badging", args.apk]))
        manifest, application = find_manifest_application(
            parse_tree(aapt2_output([args.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", args.apk]))
        )
    except AuditError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        print("验收没能完成：安装包或清单读不了。")
        return 1

    checks = [
        ("包名", lambda: check_package(badging)),
        ("不是可调试包", lambda: check_not_debuggable(badging, application)),
        ("allowBackup 为 false", lambda: check_allow_backup(application)),
        ("权限只在白名单内", lambda: check_permissions(badging, manifest)),
        ("没有允许明文流量", lambda: check_cleartext(badging, application, args.apk, args.aapt2)),
        ("除主 Activity 外没有导出组件", lambda: check_exported_components(manifest, application)),
        ("主 Activity 的 intent-filter 与方案一致", lambda: check_main_intent_filters(application)),
        ("备份规则排除全部存储域", lambda: check_backup_rules(application, args.apk, args.aapt2)),
        ("安装包已签名", lambda: check_signature(args.apk, args.apksigner)),
    ]
    results = []
    for title, check in checks:
        try:
            ok, detail = check()
        except Exception as exc:
            # 检查本身出了意外：这一项按不通过处理，并留下原因。
            ok, detail = False, f"检查过程出错，按不通过处理：{type(exc).__name__}: {exc}"
        results.append((title, ok, detail))

    for title, ok, detail in results:
        # 「通过」和「不通过」补成同样的显示宽度，逐项对齐。
        print(f"{'通过' if ok else '不通过'}    {title}：{detail}")

    failed = [title for title, ok, _ in results if not ok]
    print()
    print(f"安装包：{args.apk}")
    print(f"版本号：{badging['version_name']}（versionCode {badging['version_code']}）")
    print(f"系统版本：最低 {badging['min_sdk']}，目标 {badging['target_sdk']}")
    print(f"安装包大小：{human_size(os.path.getsize(args.apk))}")
    print()
    if failed:
        print(f"验收不通过，{len(failed)} 项不符：" + "、".join(failed))
        return 1
    print(f"验收通过，{len(results)} 项全部相符。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
