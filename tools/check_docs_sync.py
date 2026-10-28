#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文档和代码同步检查（CI 里跑）：改了功能却没更新文档，这里让它失败。机器能判断的有这几条：
  1. 当前 versionName 在 README.md 里必须有「## 版本号」一节，且不是空话；
  2. 当前 versionName 在 ai_guide.md「版本变化」里必须有「### 版本号」一节，且不是空话（写「无变化」也行，但要写）；
  3. 记录页「粘贴命令」代码里认的每个 key，ai_guide.md 第 9 章里都要写到；
  4. 预设、字段表由别的脚本 / 测试生成和校验（gen_schema.py / ConfigCheckTest），这里不重复。
写得好不好机器判断不了，由《开发须知.md》要求人 / AI 自己负责；这里只保证「没写」一定会被发现。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read(p):
    with open(os.path.join(ROOT, p), encoding="utf8") as f:
        return f.read()


def section(text, heading_re):
    m = re.search(r"^(#{2,3}) " + heading_re + r".*$", text, re.M)
    if not m:
        return None
    level = len(m.group(1))
    rest = text[m.end():]
    nxt = re.search(r"^#{1,%d} " % level, rest, re.M)
    return rest[:nxt.start()] if nxt else rest


def main():
    problems = []
    gradle = read("app/build.gradle")
    m = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", gradle)
    if not m:
        sys.exit("读不到 app/build.gradle 里的 versionName")
    ver = m.group(1)

    s = section(read("README.md"), re.escape(ver))
    if s is None:
        problems.append("README.md 里没有「## %s」这一节：每次发版（改 versionName）都要在 README 顶部写这一版改了什么" % ver)
    elif len(re.sub(r"\s", "", s)) < 20:
        problems.append("README.md 的「## %s」一节几乎是空的，写清楚这版改了什么" % ver)

    guide = read("app/src/main/assets/ai_guide.md")
    vs = section(guide, r"12\. 版本变化")
    if vs is None:
        problems.append("ai_guide.md 里没有「## 12. 版本变化」一节")
    else:
        g = section(vs, re.escape(ver))
        if g is None:
            problems.append("ai_guide.md「版本变化」里没有「### %s」：每次发版都要写「AI 写配置时需要知道什么变了」，没有变化也写一句「无变化」" % ver)
        elif len(re.sub(r"\s", "", g)) < 4:
            problems.append("ai_guide.md「版本变化」的「### %s」是空的" % ver)

    rec = read("app/src/main/java/com/textrec/lsp/RecordsActivity.java")
    i = rec.index("private void runCmd(")
    keys = sorted(set(re.findall(r'case "(\w+)"', rec[i:i + 12000])))
    s9 = section(guide, r"9\. 记录页「粘贴命令」") or ""
    for k in keys:
        if ("`%s`" % k) not in s9 and ("`%s=" % k) not in s9 and (" %s=" % k) not in s9:
            problems.append("记录页粘贴命令认 %s=，但 ai_guide.md 第 9 章没写到它" % k)

    if problems:
        print("文档没有跟上代码：\n")
        for p in problems:
            print("  ✗ " + p)
        print("\n（规则见《开发须知.md》）")
        sys.exit(1)
    print("文档同步检查通过：版本 %s，粘贴命令 %d 个 key" % (ver, len(keys)))


if __name__ == "__main__":
    main()
