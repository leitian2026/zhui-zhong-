#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 RecConfig.java（类型 / 默认值 / 范围）+ tools/field_meta.txt（分组 / 父开关 / 说明）生成：
  1. app/src/main/java/com/textrec/lsp/ConfigSchema.java   配置检查器和界面共用的字段表
  2. 文档里 <!-- GEN:xxx --> ... <!-- /GEN --> 之间的字段表（ai_manual.md / ai_guide.md）

用法：
  python3 tools/gen_schema.py           重新生成并写回文件
  python3 tools/gen_schema.py --check   只检查是否已是最新（CI 用，不一致就退出码 1）

新增 / 删除 / 改名配置项时：改 RecConfig.java（字段 + fill()），在 tools/field_meta.txt 加一行，然后跑这个脚本。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REC = os.path.join(ROOT, "app/src/main/java/com/textrec/lsp/RecConfig.java")
META = os.path.join(ROOT, "tools/field_meta.txt")
OUT = os.path.join(ROOT, "app/src/main/java/com/textrec/lsp/ConfigSchema.java")
DOCS = [os.path.join(ROOT, "app/src/main/assets/ai_manual.md"), os.path.join(ROOT, "app/src/main/assets/ai_guide.md")]

GROUP_TITLE = {
    "target": "目标与总开关", "content": "记录内容（文字 / 图标 / 状态）", "range": "记录范围（关键字）",
    "trace": "方法追踪", "chain": "链路追踪", "filter": "采集过滤", "limits": "记录容量与抓栈",
    "timeline": "时间线与任务", "view": "视图树与点哪查哪", "compose": "Compose", "internal": "内部 / 已废弃",
}
GROUP_ORDER = ["target", "content", "range", "trace", "filter", "view", "timeline", "chain", "compose", "limits", "internal"]


def read(p):
    with open(p, encoding="utf8") as f:
        return f.read()


def parse_rec():
    src = read(REC)
    fields = {}
    order = []
    for m in re.finditer(r"^\s*public\s+(boolean|int|long|String)\s+(\w+)\s*=\s*(.+?);\s*(?://.*)?$", src, re.M):
        t, name, d = m.group(1), m.group(2), m.group(3).strip()
        if name in fields:
            continue
        fields[name] = {"type": t, "def": d}
        order.append(name)
    fill = src[src.index("private static void fill"):]
    for m in re.finditer(r"c\.(\w+)\s*=\s*clamp\(num\(p,\s*\"(\w+)\",\s*c\.\w+\),\s*(-?\d+),\s*(-?\d+)\)", fill):
        name = m.group(2)
        if name in fields:
            fields[name]["min"] = int(m.group(3))
            fields[name]["max"] = int(m.group(4))
    keys_in_fill = set(re.findall(r"\"(\w+)\"", fill))
    return fields, order, keys_in_fill


def parse_meta():
    meta = {}
    for ln, line in enumerate(read(META).split("\n"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        p = line.split("|")
        if len(p) != 8:
            sys.exit("field_meta.txt 第 %d 行要有 8 段（key|group|level|parent|requires|title|desc|help）：%s" % (ln, line))
        key, group, level, parent, req, title, desc, help_ = [x.strip() for x in p]
        if not desc:
            sys.exit("field_meta.txt 第 %d 行：desc 不能为空" % ln)
        if key in meta:
            sys.exit("field_meta.txt 第 %d 行：%s 重复" % (ln, key))
        if group not in GROUP_TITLE:
            sys.exit("field_meta.txt 第 %d 行：未知 group %s" % (ln, group))
        if level not in ("1", "2", "3"):
            sys.exit("field_meta.txt 第 %d 行：level 只能是 1/2/3" % ln)
        meta[key] = {"group": group, "level": int(level), "parent": parent, "requires": req, "title": title, "desc": desc, "help": help_}
    return meta


def jstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def norm_def(f):
    d = f["def"]
    if f["type"] == "String":
        return d[1:-1] if d.startswith('"') and d.endswith('"') else d
    return d.rstrip("Ll")


def build():
    fields, order, in_fill = parse_rec()
    meta = parse_meta()
    problems = []
    for k in order:
        if k not in meta:
            problems.append("RecConfig 有字段 %s，但 tools/field_meta.txt 里没有它" % k)
        if k not in in_fill and k not in ("traceOnly",):
            problems.append("RecConfig 字段 %s 没有出现在 fill()（粘贴 / 读文件时不会读它）" % k)
    for k in meta:
        if k not in fields:
            problems.append("tools/field_meta.txt 有 %s，但 RecConfig 里没有这个字段" % k)
    for k, m in meta.items():
        p = m["parent"].rstrip("!")
        if p and p not in fields:
            problems.append("%s 的 parent %s 不是 RecConfig 字段" % (k, p))
        if m["requires"] and m["requires"] not in ("traceMethods",):
            problems.append("%s 的 requires 只支持 traceMethods" % k)
    for k in order:
        f = fields[k]
        if f["type"] == "int" and "min" not in f:
            problems.append("int 字段 %s 在 fill() 里没有 clamp(num(...), 最小, 最大)，检查器不知道范围" % k)
    if problems:
        sys.exit("\n".join("✗ " + x for x in problems))

    keys = sorted(order, key=lambda k: (GROUP_ORDER.index(meta[k]["group"]), meta[k]["level"], order.index(k)))
    lines = []
    lines.append("package com.textrec.lsp;")
    lines.append("")
    lines.append("/**")
    lines.append(" * 配置字段表（自动生成，不要手改）。")
    lines.append(" * 来源：RecConfig.java（类型 / 默认值 / 范围）+ tools/field_meta.txt（分组 / 父开关 / 说明）。")
    lines.append(" * 重新生成：python3 tools/gen_schema.py；CI 里用 --check 保证它和源码一致。")
    lines.append(" */")
    lines.append("final class ConfigSchema {")
    lines.append("    private ConfigSchema() {}")
    lines.append("")
    lines.append("    static final class F {")
    lines.append("        final String key, type, def, group, parent, requires, title, desc, help;")
    lines.append("        final boolean hasRange, parentInverse;")
    lines.append("        final long min, max;")
    lines.append("        final int level;")
    lines.append("")
    lines.append("        F(String key, String type, String def, boolean hasRange, long min, long max, String group, int level, String parent, String requires, String title, String desc, String help) {")
    lines.append("            this.key = key;")
    lines.append("            this.type = type;")
    lines.append("            this.def = def;")
    lines.append("            this.hasRange = hasRange;")
    lines.append("            this.min = min;")
    lines.append("            this.max = max;")
    lines.append("            this.group = group;")
    lines.append("            this.level = level;")
    lines.append("            this.parentInverse = parent.endsWith(\"!\");")
    lines.append("            this.parent = parent.endsWith(\"!\") ? parent.substring(0, parent.length() - 1) : parent;")
    lines.append("            this.requires = requires;")
    lines.append("            this.title = title;")
    lines.append("            this.desc = desc;")
    lines.append("            this.help = help;")
    lines.append("        }")
    lines.append("    }")
    lines.append("")
    lines.append("    /** 全部配置字段（按分组、常用程度排序）。type：bool / int / long / string。 */")
    lines.append("    static final F[] ALL = {")
    for k in keys:
        f, m = fields[k], meta[k]
        t = {"boolean": "bool", "int": "int", "long": "long", "String": "string"}[f["type"]]
        has = "min" in f
        lines.append("            new F(%s, %s, %s, %s, %dL, %dL, %s, %d, %s, %s, %s, %s, %s)," % (
            jstr(k), jstr(t), jstr(norm_def(f)), "true" if has else "false", f.get("min", 0), f.get("max", 0),
            jstr(m["group"]), m["level"], jstr(m["parent"]), jstr(m["requires"]), jstr(m["title"]), jstr(m["desc"]), jstr(m["help"])))
    lines.append("    };")
    lines.append("")
    lines.append("    static F get(String key) {")
    lines.append("        for (F f : ALL) if (f.key.equals(key)) return f;")
    lines.append("        return null;")
    lines.append("    }")
    lines.append("}")
    java = "\n".join(lines) + "\n"

    def table(maxlevel):
        out = []
        for g in GROUP_ORDER:
            ks = [k for k in keys if meta[k]["group"] == g and meta[k]["level"] <= maxlevel and g != "internal"]
            if not ks:
                continue
            out.append("**%s**" % GROUP_TITLE[g])
            out.append("")
            out.append("| 字段 | 类型 | 默认 | 范围 | 说明 |")
            out.append("|---|---|---|---|---|")
            for k in ks:
                f, m = fields[k], meta[k]
                t = {"boolean": "true/false", "int": "整数", "long": "整数", "String": "文字"}[f["type"]]
                d = norm_def(f)
                if f["type"] == "String":
                    d = "空" if d == "" else "`%s`" % d
                else:
                    d = "`%s`" % d
                rng = "%d～%d" % (f["min"], f["max"]) if "min" in f else ""
                desc = m["desc"]
                if m["parent"]:
                    desc += "（父开关 `%s`%s）" % (m["parent"].rstrip("!"), " 为 false 时才有效" if m["parent"].endswith("!") else " 关着时无效")
                if m["requires"]:
                    desc += "（需要写了 `traceMethods`）"
                out.append("| `%s` | %s | %s | %s | %s |" % (k, t, d, rng, desc.replace("|", "/")))
            out.append("")
        return "\n".join(out).rstrip() + "\n"

    return java, {"fields-common": table(2), "fields-all": table(3)}


def splice(text, blocks):
    for name, body in blocks.items():
        pat = re.compile(r"(<!-- GEN:%s -->\n)(.*?)(<!-- /GEN:%s -->)" % (re.escape(name), re.escape(name)), re.S)
        text = pat.sub(lambda m: m.group(1) + "\n" + body + "\n" + m.group(3), text)
    return text


def main():
    check = "--check" in sys.argv
    java, blocks = build()
    stale = []
    if (read(OUT) if os.path.exists(OUT) else "") != java:
        stale.append(OUT)
        if not check:
            with open(OUT, "w", encoding="utf8", newline="\n") as f:
                f.write(java)
    for d in DOCS:
        if not os.path.exists(d):
            continue
        old = read(d)
        new = splice(old, blocks)
        if new != old:
            stale.append(d)
            if not check:
                with open(d, "w", encoding="utf8", newline="\n") as f:
                    f.write(new)
    if check and stale:
        print("以下文件和 RecConfig.java / field_meta.txt 不一致，请运行 python3 tools/gen_schema.py 并提交：")
        for s in stale:
            print("  " + os.path.relpath(s, ROOT))
        sys.exit(1)
    print("OK" if check else "已生成：" + (", ".join(os.path.relpath(s, ROOT) for s in stale) or "（无变化）"))


if __name__ == "__main__":
    main()
