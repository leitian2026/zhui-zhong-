#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
核对「配置之间的关系」和代码实际行为有没有对上（CI 里跑，防止关系表只凭记忆手写而漂移）。

做法：扫描运行时代码（目标 App 里的钩子、过滤、时间线、链路……），对每个配置字段，找出代码里「引用它的那一行」同时还引用了哪些别的配置字段。
同一行里一起出现，说明代码是把它们放在一个判断里的（if (!c.a || !c.b) return）。如果这个「别的字段」没有写在它的前提里（tools/field_meta.txt 的 parent 列，
含祖先的前提），就报出来，让人决定：
  - 确实是前提：到 field_meta.txt 的 parent 列补上（检查器、首页变灰、文档字段表都会自动跟上）；
  - 不构成前提（只是顺手一起用）：写进 tools/relations_reviewed.txt，附一句原因，以后不再报。
有没有核对过，由 relations_reviewed.txt 说了算：没核对过的组合会让 CI 失败。

另一个方向：表里声明了前提 (字段, 前提)，但代码里两者从没在同一行出现过（前提多半挡在更早的 if / 钩子安装条件里，机器看不出来），
必须在 tools/relations_evidence.txt 里登记「字段 前提  文件:行号  # 证据」，说明是人对着代码核对过的；
tools/relations_evidence.txt 里还留着一段「待核对」的存量（早期凭文档写的），新加的前提不允许再进存量。

用法：python3 tools/audit_relations.py            核对（任何一类问题都退出码 1）
      python3 tools/audit_relations.py --verbose  同时列出存量里还没核对过代码的前提
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/com/textrec/lsp")
META = os.path.join(ROOT, "tools/field_meta.txt")
REVIEWED = os.path.join(ROOT, "tools/relations_reviewed.txt")
EVIDENCE = os.path.join(ROOT, "tools/relations_evidence.txt")

# 不是运行时判断的文件：配置本身、检查器、首页、文档、导出
SKIP = {"RecConfig.java", "ConfigSchema.java", "MainActivity.java", "ConfigCheck.java", "CheckText.java", "CheckFields.java", "CheckTrace.java",
        "CheckTasks.java", "CheckCross.java", "Presets.java", "PasteBlocks.java", "FormModel.java", "TargetStatus.java", "KnownNames.java",
        "Relations.java", "Store.java", "RecordsActivity.java", "DetailActivity.java", "Actions.java", "Snap.java"}
# 几乎所有判断里都有，不算前提
GLOBAL = {"enabled", "epoch"}
AGG_LIMIT = 4  # 一行里同时出现 4 个以上配置字段 = 汇总 / 打印行，不是判断，跳过


def meta():
    keys, parents = [], {}
    for line in open(META, encoding="utf8").read().split("\n"):
        if not line.strip() or line.startswith("#"):
            continue
        p = [x.strip() for x in line.split("|")]
        keys.append(p[0])
        terms = [t.strip().rstrip("!") for t in re.split(r"[&/]", p[3])] if p[3] else []
        parents[p[0]] = terms
    return keys, parents


def closure(k, parents, seen=None):
    seen = seen if seen is not None else set()
    for t in parents.get(k, []):
        if t not in seen:
            seen.add(t)
            closure(t, parents, seen)
    return seen


def load_reviewed():
    out = set()
    if not os.path.exists(REVIEWED):
        return out
    for line in open(REVIEWED, encoding="utf8").read().split("\n"):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        a, b = line.split()[:2]
        out.add((a, b))
    return out


def load_evidence():
    """返回 (已核对 {(字段, 前提): 证据}, 存量待核对 {(字段, 前提)})。"""
    ok, todo = {}, set()
    if not os.path.exists(EVIDENCE):
        return ok, todo
    for line in open(EVIDENCE, encoding="utf8").read().split("\n"):
        body, _, comment = line.partition("#")
        parts = body.split()
        if len(parts) < 2:
            continue
        if parts[0] == "TODO":
            todo.add((parts[1], parts[2]))
        else:
            ok[(parts[0], parts[1])] = (parts[2] if len(parts) > 2 else "") + " " + comment.strip()
    return ok, todo


def main():
    verbose = "--verbose" in sys.argv
    keys, parents = meta()
    kset = set(keys)
    reviewed = load_reviewed()
    ref = re.compile(r"\b[A-Za-z_]\w*\.(" + "|".join(sorted(map(re.escape, keys), key=len, reverse=True)) + r")\b(?!\s*\()")
    found = {}      # (key, other) -> (file, line, text)
    cooccur = set()  # 出现过同一行的 (a, b)
    for path in sorted(glob.glob(os.path.join(SRC, "*.java"))):
        name = os.path.basename(path)
        if name in SKIP:
            continue
        for no, line in enumerate(open(path, encoding="utf8").read().split("\n"), 1):
            s = line.strip()
            if s.startswith(("//", "*", "/*")) or "cj.put(" in s or "sb.append(" in s or "setProperty(" in s:
                continue
            code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', s)
            code = code.split("//")[0]
            hits = sorted(set(m.group(1) for m in ref.finditer(code)) - GLOBAL)
            if len(hits) < 2 or len(hits) >= AGG_LIMIT:
                continue
            for a in hits:
                for b in hits:
                    if a == b:
                        continue
                    cooccur.add((a, b))
                    if a in GLOBAL or b in GLOBAL:
                        continue
                    if b in closure(a, parents) or a in closure(b, parents):
                        continue
                    found.setdefault((a, b), (name, no, s[:120]))
    bad = [(k, v) for k, v in sorted(found.items()) if (k[0], k[1]) not in reviewed and (k[1], k[0]) not in reviewed]
    if bad:
        print("以下配置字段在代码里的同一个判断里一起出现，但关系表（field_meta.txt 的 parent 列）里没有声明，也没有在 relations_reviewed.txt 里登记过：\n")
        for (a, b), (f, n, text) in bad:
            print("  %s  <->  %s\n      %s:%d  %s" % (a, b, f, n, text))
        print("\n逐个看一下：确实是前提 → 在 tools/field_meta.txt 的 parent 列补上；不构成前提 → 在 tools/relations_reviewed.txt 里加一行「字段A 字段B  # 原因」。")
    evidence, todo = load_evidence()
    unverified = []
    for k in keys:
        for t in parents.get(k, []):
            if (k, t) in cooccur or (t, k) in cooccur:
                continue
            if (k, t) in evidence or (k, t) in todo:
                continue
            unverified.append((k, t))
    if unverified:
        print("\n表里声明了这些前提，但代码里两者没有出现在同一行，也没有在 tools/relations_evidence.txt 里登记过核对证据：\n")
        for k, t in unverified:
            print("  %s 的前提 %s" % (k, t))
        print("\n对着代码找到「没有 %s 就不会走到用 %s 的地方」的那一处（if 判断 / 钩子安装条件），在 tools/relations_evidence.txt 里加一行「字段 前提  文件:行号  # 证据」；找不到就说明这个前提写错了，改 field_meta.txt。" % ("前提", "字段"))
    stale = [x for x in todo if x in evidence or x in cooccur]
    if verbose or todo:
        left = sorted(x for x in todo if x not in evidence)
        print("\n存量里还没核对过代码的前提：%d 个%s" % (len(left), "（--verbose 列出）" if not verbose else ""))
        if verbose:
            for k, t in left:
                print("  %s 的前提 %s" % (k, t))
    if bad or unverified:
        sys.exit(1)
    print("关系核对通过：%d 个配置字段，%d 组同行出现的组合都已声明或登记" % (len(kset), len(found)))


if __name__ == "__main__":
    main()
