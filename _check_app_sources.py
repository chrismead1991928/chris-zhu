"""App 资源与源码一致性自检。

检查项：
1. Kotlin 源文件是否残留 UTF-8 BOM（kapt 会因此报
   "Expecting a top level declaration"）。
2. 5 个 strings.xml 是否都是合法 XML，且没有重复的 name 属性。
3. 各语言文件与默认 values/strings.xml 的 key 集合是否一致。
4. 同一个 key 在不同语言中的格式化占位符（%1$d 等）是否一致。
5. strings.xml 中是否存在源码里从未引用的「死文案」。

第 4 项的意义：菜谱上限、价格这类数字改成了引用常量的占位符，
如果某种语言漏写 %2$d，运行时 stringResource 传参就会错位或直接抛异常，
而这类错误只在切到该语言时才会暴露，编译期查不出来。

第 5 项的意义：功能删除后，它的文案很容易被留在 strings.xml 里。
死文案不会报错，但会一直占着 5 个语言文件、误导后续维护者以为功能还在，
并且在功能回退时让人分不清哪些文案仍有效。此检查确保删除是彻底的。

用法：python _check_app_sources.py
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

PROJECT = os.path.dirname(os.path.abspath(__file__))
SRC_DIR = os.path.join(PROJECT, "app", "src")
JAVA_DIR = os.path.join(SRC_DIR, "main", "java")
RES_DIR = os.path.join(SRC_DIR, "main", "res")

BOM = b"\xef\xbb\xbf"

# 位置参数 %1$s / %1$d，按出现顺序取出序号
POSITIONAL_ARG = re.compile(r"%(?P<index>\d+)\$[a-zA-Z]")

# 代码中的引用：R.string.foo 或 @string/foo
KOTLIN_REF = re.compile(r"R\.string\.([A-Za-z0-9_]+)")
XML_REF = re.compile(r"@string/([A-Za-z0-9_]+)")

failures = []


def check_kotlin_bom():
    checked = 0
    for root, _dirs, files in os.walk(JAVA_DIR):
        for name in files:
            if not name.endswith(".kt"):
                continue
            checked += 1
            path = os.path.join(root, name)
            with open(path, "rb") as fh:
                head = fh.read(3)
            if head == BOM:
                failures.append(f"BOM in {path}")
    print(f"[1] Kotlin BOM check: {checked} files scanned")


def check_strings():
    files = {
        "default": os.path.join(RES_DIR, "values", "strings.xml"),
        "zh-rCN": os.path.join(RES_DIR, "values-zh-rCN", "strings.xml"),
        "ja": os.path.join(RES_DIR, "values-ja", "strings.xml"),
        "de": os.path.join(RES_DIR, "values-de", "strings.xml"),
        "fr": os.path.join(RES_DIR, "values-fr", "strings.xml"),
    }

    key_sets = {}
    arg_map = {}
    for label, path in files.items():
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            failures.append(f"{label} strings.xml is not valid XML: {exc}")
            continue

        keys = []
        args = {}
        for node in tree.getroot():
            if node.tag != "string":
                continue
            name = node.attrib.get("name")
            keys.append(name)
            # 只比较「哪些序号被用到」，不比较顺序与类型：
            # 各语言语序不同，%2$d 出现在 %1$d 之前是正常的
            args[name] = sorted({int(m.group("index")) for m in POSITIONAL_ARG.finditer(node.text or "")})

        duplicates = sorted({k for k in keys if keys.count(k) > 1})
        if duplicates:
            failures.append(f"{label}: duplicate string names {duplicates}")

        key_sets[label] = set(keys)
        arg_map[label] = args
        print(f"[2] {label}: {len(keys)} strings, {len(key_sets[label])} unique")

    base = key_sets.get("default")
    if base:
        for label, keys in key_sets.items():
            if label == "default":
                continue
            missing = sorted(base - keys)
            extra = sorted(keys - base)
            if missing:
                failures.append(f"{label} missing keys: {missing}")
            if extra:
                failures.append(f"{label} extra keys: {extra}")

    # 第 4 项：占位符一致性
    if base:
        base_args = arg_map.get("default", {})
        checked = 0
        for label, keys in key_sets.items():
            if label == "default":
                continue
            for name in sorted(base & keys):
                expected = base_args.get(name, [])
                actual = arg_map[label].get(name, [])
                if expected != actual:
                    failures.append(
                        f"{label}: '{name}' format args {actual} != default {expected}"
                    )
                else:
                    checked += 1
        print(f"[4] Format args checked across locales: {checked} keys matched")


def check_unused_strings():
    """第 5 项：找出源码从未引用的 string key。

    扫描范围是整个 app/src（含 debug 源集），同时识别
    Kotlin 的 R.string.foo 与 XML 的 @string/foo 两种引用方式。
    """
    default_strings = os.path.join(RES_DIR, "values", "strings.xml")
    try:
        root = ET.parse(default_strings).getroot()
    except ET.ParseError:
        # XML 非法的情形已由第 2 项报告，这里不再重复
        return

    declared = {node.attrib.get("name") for node in root if node.tag == "string"}

    referenced = set()
    for dirpath, _dirs, files in os.walk(SRC_DIR):
        for name in files:
            ext = os.path.splitext(name)[1].lower()
            if ext not in (".kt", ".java", ".xml"):
                continue
            path = os.path.join(dirpath, name)
            # strings.xml 自身会包含 name= 属性，不应计入「引用」，
            # 否则每个 key 都会因为自己声明自己而被判定为已使用
            if name == "strings.xml":
                continue
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    text = fh.read()
            except (UnicodeDecodeError, OSError):
                continue
            referenced.update(KOTLIN_REF.findall(text))
            referenced.update(XML_REF.findall(text))

    unused = sorted(declared - referenced)
    print(f"[5] Unused string keys: {len(unused)} of {len(declared)} declared")
    for name in unused:
        failures.append(f"unused string key: {name}")


check_kotlin_bom()
check_strings()
check_unused_strings()

print()
if failures:
    print("FAILED:")
    for item in failures:
        print("  -", item)
    sys.exit(1)

print("ALL CHECKS PASSED")
