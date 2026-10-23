package com.textrec.lsp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 数据树 / 值差异 / 观察点变化 / 候选钩点 / 钩子草稿（1.31.0，模块界面这边用，只读快照里已有的数据现算）。
 *
 * 树上每个节点的「路径」写法固定（和目标进程里 DataTree 一致）：根 this / argN / ret；.字段；[下标]；{键}；(LiveData)；(ref)；(lazy)。
 * 路径是追踪的地址：导出里的值差异、观察点、取值代码、候选钩点都用同一个路径指同一个点。
 */
final class TreeReport {
    private TreeReport() {}

    // ------------------------------------------------------------ 路径小工具

    /** 路径最后一段的起点；解析从后往前，括号 / 方括号 / 花括号按对子找。 */
    static int lastStart(String p) {
        if (p.isEmpty()) return 0;
        char e = p.charAt(p.length() - 1);
        int i;
        if (e == ']') i = p.lastIndexOf('[');
        else if (e == '}') i = p.lastIndexOf('{');
        else if (e == ')') i = p.lastIndexOf('(');
        else i = p.lastIndexOf('.');
        return Math.max(i, 0);
    }

    static String parentOf(String p) {
        int i = lastStart(p);
        return i <= 0 ? "" : p.substring(0, i);
    }

    static String lastToken(String p) {
        int i = lastStart(p);
        return i <= 0 ? p : p.substring(i);
    }

    static boolean isLeaf(String k) {
        return k.equals("bool") || k.equals("num") || k.equals("str") || k.equals("enum") || k.equals("char") || k.equals("null");
    }

    static String segName(int seg, List<GuideService.Task> ts) {
        String n = seg >= 1 && seg <= ts.size() ? ts.get(seg - 1).name : "";
        return "任务" + seg + (n.isEmpty() ? "" : "「" + clip(n, 8) + "」");
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static Map<Integer, List<String>> segVals(JSONObject node) {
        LinkedHashMap<Integer, List<String>> m = new LinkedHashMap<>();
        JSONObject s = node.optJSONObject("s");
        if (s == null) return m;
        java.util.Iterator<String> it = s.keys();
        ArrayList<Integer> ks = new ArrayList<>();
        while (it.hasNext()) {
            try {
                ks.add(Integer.parseInt(it.next()));
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(ks);
        for (int k : ks) {
            JSONArray a = s.optJSONArray(String.valueOf(k));
            ArrayList<String> l = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
            m.put(k, l);
        }
        return m;
    }

    /** 这个叶子节点的值是否随任务不同：任务内（seg>0）至少有两个任务的取值集合不一样。 */
    static boolean differsAcrossTasks(JSONObject node) {
        if (!isLeaf(node.optString("k"))) return false;
        HashSet<String> sets = new HashSet<>();
        for (Map.Entry<Integer, List<String>> e : segVals(node).entrySet()) {
            if (e.getKey() <= 0 || e.getValue().isEmpty()) continue;
            ArrayList<String> l = new ArrayList<>(e.getValue());
            Collections.sort(l);
            sets.add(l.toString());
        }
        return sets.size() >= 2;
    }

    private static String valsText(JSONObject node, List<GuideService.Task> ts, int cap) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<Integer, List<String>> e : segVals(node).entrySet()) {
            if (e.getKey() <= 0) continue;
            if (n++ >= cap) {
                sb.append(" …");
                break;
            }
            sb.append(sb.length() > 0 ? "  " : "").append(segName(e.getKey(), ts)).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 数据树

    private static final class TNode {
        final JSONObject o;
        final String path;
        final ArrayList<TNode> kids = new ArrayList<>();
        int diffBelow;

        TNode(JSONObject o) {
            this.o = o;
            this.path = o.optString("p");
        }
    }

    private static List<TNode> build(JSONObject tree) {
        JSONArray ns = tree.optJSONArray("nodes");
        LinkedHashMap<String, TNode> all = new LinkedHashMap<>();
        ArrayList<TNode> roots = new ArrayList<>();
        if (ns == null) return roots;
        for (int i = 0; i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            if (n == null) continue;
            all.put(n.optString("p"), new TNode(n));
        }
        for (TNode t : all.values()) {
            TNode par = all.get(parentOf(t.path));
            if (par != null && par != t) par.kids.add(t);
            else roots.add(t);
        }
        for (TNode r : roots) countDiff(r);
        return roots;
    }

    private static int countDiff(TNode t) {
        int d = differsAcrossTasks(t.o) ? 1 : 0;
        for (TNode k : t.kids) d += countDiff(k);
        t.diffBelow = d;
        return d;
    }

    private static void printNode(StringBuilder sb, TNode t, String prefix, boolean last, boolean root, List<GuideService.Task> ts) {
        String kind = t.o.optString("k"), type = t.o.optString("t"), v = t.o.optString("v");
        sb.append(prefix).append(root ? "" : last ? "└─ " : "├─ ").append(root ? t.path : lastToken(t.path));
        if (isLeaf(kind)) {
            sb.append(" = ").append(v);
            if (!type.isEmpty() && !kind.equals("str") && !kind.equals("null")) sb.append("  (").append(type).append(')');
        } else if (kind.equals("ref") || kind.equals("view")) {
            sb.append("  ").append(v);
        } else {
            sb.append("  ").append(type.isEmpty() ? kind : type);
            if (kind.equals("list") || kind.equals("map") || kind.equals("array")) sb.append(' ').append(v);
        }
        if (differsAcrossTasks(t.o)) sb.append("   ✦ ").append(valsText(t.o, ts, 4));
        else if (t.o.optInt("c") > 0) sb.append("   ⇄ 记录期间变了 ").append(t.o.optInt("c")).append(" 次");
        if (t.o.optBoolean("w")) sb.append("   ◉观察点");
        if (!isLeaf(kind) && t.diffBelow > 0) sb.append("   ▸内有 ").append(t.diffBelow).append(" 个随任务变化的字段");
        sb.append('\n');
        String np = root ? prefix : prefix + (last ? "   " : "│  ");
        for (int i = 0; i < t.kids.size(); i++) printNode(sb, t.kids.get(i), np, i == t.kids.size() - 1, false, ts);
    }

    static String treeText(List<JSONObject> trees, List<GuideService.Task> ts) {
        if (trees.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("读法：每个被追踪方法一棵树，根是 this / argN（第 N 个参数，从 0 数）/ ret（返回值）；子节点是字段，[i] 是列表 / 数组元素，{键} 是 Map 元素，")
                .append("(LiveData) 是 LiveData 当前的值。叶子写「= 值」；✦ = 这个值在不同任务里不一样（后面列出每个任务里见过的值，最多 4 个）；")
                .append("⇄ = 同一个任务内值就变过；◉ = 观察点（会在「观察点变化」里写出是谁改的）；↩ = 对象之前已经出现过（避免循环）。\n")
                .append("树的名字：「类#方法」= 被追踪方法的 this / 参数 / 返回值；「实体 X」= 任务开始 / 结束 / 点标记时从页面上扫到的 X 对象（Aweme 这类数据实体）；")
                .append("「视图所在 Y」= 文字 / 图标记录所在的 ViewHolder / 自定义控件 / 控件 tag（根叫 holder / view / tag）。后两种不需要写方法追踪，值按任务开始 / 结束各抓一次记在该任务名下，对比两个时刻就能看出这一步改了哪些字段。\n")
                .append("追踪树上任意一点：在配置里写 watch=类#方法:路径（路径照下面抄），重新记录；或不写，值变过的叶子会自动加成观察点。\n");
        for (JSONObject t : trees) {
            sb.append('\n').append("▶ ").append(t.optString("tkey")).append("  （命中 ").append(t.optInt("hits")).append(" 次，展开 ").append(t.optInt("caps")).append(" 次")
                    .append(t.optBoolean("trunc") ? "；节点数到上限，后面的没展开，可调大 treeNodes / 调小 treeDepth" : "").append("）\n");
            List<TNode> roots = build(t);
            for (TNode r : roots) printNode(sb, r, "  ", true, true, ts);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 值差异

    private static JSONObject nodeOf(JSONObject tree, String path) {
        JSONArray ns = tree.optJSONArray("nodes");
        if (ns == null) return null;
        for (int i = 0; i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            if (n != null && n.optString("p").equals(path)) return n;
        }
        return null;
    }

    private static Map<String, JSONObject> nodeMap(JSONObject tree) {
        HashMap<String, JSONObject> m = new HashMap<>();
        JSONArray ns = tree.optJSONArray("nodes");
        if (ns != null) for (int i = 0; i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            if (n != null) m.put(n.optString("p"), n);
        }
        return m;
    }

    /** 某棵树里值随任务不同的叶子节点。 */
    static List<JSONObject> diffNodes(JSONObject tree) {
        ArrayList<JSONObject> out = new ArrayList<>();
        JSONArray ns = tree.optJSONArray("nodes");
        if (ns != null) for (int i = 0; i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            if (n != null && differsAcrossTasks(n)) out.add(n);
        }
        return out;
    }

    static String valueDiff(List<JSONObject> trees, List<GuideService.Task> ts) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (JSONObject t : trees) {
            List<JSONObject> ds = diffNodes(t);
            if (ds.isEmpty()) continue;
            sb.append("\n▶ ").append(t.optString("tkey")).append("：").append(ds.size()).append(" 个字段 / 参数 / 返回值随任务变化\n");
            Map<String, JSONObject> nm = nodeMap(t);
            for (JSONObject n : ds) {
                total++;
                sb.append("  ✦ ").append(n.optString("p")).append("  (").append(n.optString("t")).append(")\n      ").append(valsText(n, ts, 6)).append('\n');
                sb.append("      取值: ").append(accessCode(n.optString("p"), n.optString("t"), n.optString("k"), nm).replace("\n", "\n            ")).append('\n');
            }
        }
        if (total == 0) return "";
        return "读法：下面这些节点的值在不同任务里不一样。区分「点赞 / 取消点赞」「播放 / 暂停」这类操作，要找的往往就是这种状态字段；"
                + "对照任务（第 4 段写「空」）里的值是「没发生这件事时」的样子。「取值」是在钩子里读到这个值的 XposedHelpers 代码（字段名被混淆的话换版本会变）。\n" + sb;
    }

    // ------------------------------------------------------------ 取值代码

    /** 把路径翻成 XposedHelpers 取值代码（多行，放在 try 里用）。nm 用来知道中间节点是不是数组（数组用 Array.get）。 */
    static String accessCode(String path, String type, String kind, Map<String, JSONObject> nm) {
        int cut = path.length();
        for (char ch : new char[]{'.', '[', '{', '('}) {
            int i = path.indexOf(ch);
            if (i >= 0) cut = Math.min(cut, i);
        }
        String root = path.substring(0, cut), tail = path.substring(cut);
        String start;
        if (root.equals("this")) start = "param.thisObject";
        else if (root.equals("ret")) start = "param.getResult()";
        else if (root.startsWith("arg")) start = "param.args[" + root.substring(3) + "]";
        else start = root;
        StringBuilder sb = new StringBuilder();
        String prev = start;
        int n = 0, i = 0;
        String walked = root;
        while (i < tail.length()) {
            char ch = tail.charAt(i);
            int j = i + 1;
            String expr;
            String tok;
            if (ch == '.') {
                while (j < tail.length() && ".[{(".indexOf(tail.charAt(j)) < 0) j++;
                tok = tail.substring(i, j);
                String f = tok.substring(1);
                boolean lastTok = j >= tail.length();
                if (lastTok && kind.equals("bool")) expr = "XposedHelpers.getBooleanField(" + prev + ", \"" + f + "\")";
                else if (lastTok && type.equals("Integer")) expr = "XposedHelpers.getIntField(" + prev + ", \"" + f + "\")";
                else if (lastTok && type.equals("Long")) expr = "XposedHelpers.getLongField(" + prev + ", \"" + f + "\")";
                else if (lastTok && type.equals("Float")) expr = "XposedHelpers.getFloatField(" + prev + ", \"" + f + "\")";
                else if (lastTok && type.equals("Double")) expr = "XposedHelpers.getDoubleField(" + prev + ", \"" + f + "\")";
                else expr = "XposedHelpers.getObjectField(" + prev + ", \"" + f + "\")";
            } else if (ch == '[') {
                j = tail.indexOf(']', i) + 1;
                if (j <= 0) j = tail.length();
                tok = tail.substring(i, j);
                String idx = tok.substring(1, Math.max(1, tok.length() - 1));
                JSONObject pn = nm == null ? null : nm.get(walked);
                if (pn != null && pn.optString("k").equals("array")) expr = "java.lang.reflect.Array.get(" + prev + ", " + idx + ")";
                else expr = "((java.util.List<?>) " + prev + ").get(" + idx + ")";
            } else if (ch == '{') {
                j = tail.indexOf('}', i) + 1;
                if (j <= 0) j = tail.length();
                tok = tail.substring(i, j);
                String key = tok.substring(1, Math.max(1, tok.length() - 1));
                expr = "((java.util.Map<?, ?>) " + prev + ").get(\"" + key.replace("\"", "\\\"") + "\")" + "/* 键按字符串显示，实际类型可能不是 String */";
            } else if (ch == '(') {
                j = tail.indexOf(')', i) + 1;
                if (j <= 0) j = tail.length();
                tok = tail.substring(i, j);
                if (tok.equals("(ref)")) expr = "XposedHelpers.callMethod(" + prev + ", \"get\")";
                else expr = "XposedHelpers.callMethod(" + prev + ", \"getValue\")";
            } else {
                break;
            }
            n++;
            String var = "v" + n;
            boolean typed = expr.startsWith("XposedHelpers.getBooleanField") || expr.startsWith("XposedHelpers.getIntField")
                    || expr.startsWith("XposedHelpers.getLongField") || expr.startsWith("XposedHelpers.getFloatField") || expr.startsWith("XposedHelpers.getDoubleField");
            String decl = !typed ? "Object" : expr.startsWith("XposedHelpers.getBooleanField") ? "boolean" : expr.startsWith("XposedHelpers.getIntField") ? "int"
                    : expr.startsWith("XposedHelpers.getLongField") ? "long" : expr.startsWith("XposedHelpers.getFloatField") ? "float" : "double";
            sb.append(decl).append(' ').append(var).append(" = ").append(expr).append(";\n");
            if (!typed && j < tail.length()) sb.append("if (").append(var).append(" == null) return;\n");
            prev = var;
            walked = path.substring(0, root.length() + j);
            i = j;
        }
        if (n == 0) sb.append("Object v0 = ").append(start).append(";\n");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ 观察点变化

    static String watchText(List<JSONObject> watches, List<GuideService.Task> ts) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (JSONObject w : watches) {
            JSONArray ev = w.optJSONArray("events");
            if (ev == null || ev.length() == 0) continue;
            shown++;
            sb.append("\n◉ ").append(w.optString("tkey")).append(" : ").append(w.optString("path")).append(w.optBoolean("auto") ? "  （自动挑选）" : "  （手写）")
                    .append("  最后读到 ").append(w.optString("last")).append('\n');
            LinkedHashMap<String, Integer> writers = new LinkedHashMap<>();
            for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e == null) continue;
                boolean inside = "inside".equals(e.optString("kind"));
                sb.append("  ").append(String.format(java.util.Locale.ROOT, "%.2fs", e.optLong("t") / 1000.0)).append(' ').append(segName(e.optInt("seg"), ts))
                        .append("  ").append(e.optString("old")).append(" → ").append(e.optString("new")).append("   ")
                        .append(inside ? "【写入发生在 " + e.optString("by") + "（含它调用的东西）】" : "（发生在两次调用之间，被 " + e.optString("by") + " 发现）").append('\n');
                String st = e.optString("stack");
                if (!st.isEmpty()) sb.append("      栈: ").append(clip(st, 400)).append('\n');
                if (inside) {
                    String by = e.optString("by").replace(" 执行期间", "");
                    Integer c = writers.get(by);
                    writers.put(by, c == null ? 1 : c + 1);
                }
            }
            if (w.optInt("dropped") > 0) sb.append("  … 还有 ").append(w.optInt("dropped")).append(" 次变化没记（每个观察点最多 40 条）\n");
            if (!writers.isEmpty()) {
                sb.append("  ▶ 写入者候选（改这个值时正在执行的被追踪方法）：");
                for (Map.Entry<String, Integer> e : writers.entrySet()) sb.append(e.getKey()).append(" ×").append(e.getValue()).append("  ");
                sb.append('\n');
            } else {
                sb.append("  ▶ 变化都发生在两次被追踪调用之间：写入者不在已追踪的方法里，看上面每次变化前后相邻的被追踪方法，把写入者可能所在的类再加进方法追踪。\n");
            }
        }
        if (shown == 0) return "";
        return "读法：每个观察点在任何一个被追踪方法进入前、返回后都重新读一遍。「写入发生在 X」= 进入 X 前和返回后的值不同，X 自己或它调用的东西改的（带当时的调用栈）；"
                + "「发生在两次调用之间」= 改动发生在两次被追踪调用的空档里。\n" + sb;
    }

    // ------------------------------------------------------------ 候选钩点排名

    static final class Cand {
        String name, kind;
        int score;
        final ArrayList<String> why = new ArrayList<>();
        JSONObject r;
        String tkey;
    }

    private static void add(Cand c, int pts, String why) {
        c.score += pts;
        c.why.add((pts >= 0 ? "+" : "") + pts + " " + why);
    }

    private static int bits(int mask, int reps) {
        return Integer.bitCount(mask & ((1 << Math.min(reps, 30)) - 1));
    }

    static List<Cand> candidates(List<Snap.Item> items, List<JSONObject> trees, List<JSONObject> watches, List<GuideService.Task> ts) {
        int n = ts.size();
        ArrayList<Cand> out = new ArrayList<>();
        if (n == 0) return out;
        ArrayList<Integer> acts = new ArrayList<>(), controls = new ArrayList<>();
        int[] reps = new int[n + 1];
        for (int k = 1; k <= n; k++) {
            GuideService.Task t = ts.get(k - 1);
            reps[k] = t.reps;
            if (t.baseline) continue;
            acts.add(k);
            if (t.allowEmpty) controls.add(k);
        }
        if (acts.isEmpty()) return out;
        HashMap<String, JSONObject> treeBy = new HashMap<>();
        for (JSONObject t : trees) treeBy.put(t.optString("tkey"), t);
        HashMap<String, JSONObject> writerFor = new HashMap<>();
        for (JSONObject w : watches) {
            JSONArray ev = w.optJSONArray("events");
            if (ev == null) continue;
            for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e != null && "inside".equals(e.optString("kind"))) writerFor.put(e.optString("by").replace(" 执行期间", ""), w);
            }
        }
        HashMap<String, Cand> frameCands = new HashMap<>();
        for (Snap.Item it : items) {
            JSONObject r = it.o;
            if (!"TRACE".equals(r.optString("kind")) || r.optInt("bucket") != 0) continue;
            if ("noise".equals(r.optString("baseline"))) continue;
            int[] c = new int[n + 1];
            JSONObject sg = r.optJSONObject("segCounts");
            if (sg != null) for (int k = 0; k <= n; k++) c[k] = sg.optInt(String.valueOf(k));
            ArrayList<Integer> present = new ArrayList<>();
            int total = 0;
            for (int k : acts) {
                if (c[k] > 0) {
                    present.add(k);
                    total += c[k];
                }
            }
            if (present.isEmpty()) continue;
            String tkey = r.optString("view") + "#" + r.optString("viewId");
            Cand cd = new Cand();
            cd.kind = "方法追踪";
            cd.name = tkey;
            cd.r = r;
            cd.tkey = tkey;
            if (present.size() == 1 && acts.size() >= 2) add(cd, 30, "只在" + segName(present.get(0), ts) + "里出现");
            if (!controls.isEmpty() && acts.size() > controls.size()) {
                boolean inCtl = false;
                for (int k : controls) if (c[k] > 0) inCtl = true;
                if (!inCtl) add(cd, 20, "对照任务里没有（比对照多出来）");
            }
            if (acts.size() >= 2 && present.size() == acts.size()) add(cd, -15, "每件事里都出现（公共代码）");
            switch (RecFormat.gradeOf(r)) {
                case "a": add(cd, 15, "A 级：覆写框架方法，最稳"); break;
                case "b": add(cd, 10, "B 级：稳定业务"); break;
                case "c": add(cd, 3, "C 级：通用，要过滤"); break;
                default: break;
            }
            JSONObject tr = treeBy.get(tkey);
            if (tr != null) {
                int d = diffNodes(tr).size();
                if (d > 0) add(cd, 10 + Math.min(5, d), "它的参数 / 字段有 " + d + " 个值随任务变化（状态字段）");
            }
            JSONObject w = writerFor.get(tkey);
            if (w != null) add(cd, 25, "观察点显示它执行期间改了 " + w.optString("path"));
            JSONObject tm = r.optJSONObject("timing");
            if (tm != null) {
                long mn = tm.optLong("min");
                if (mn <= 300) add(cd, 10, "离用户输入只有 " + mn + "ms（事件驱动）");
                else if (mn <= 1000) add(cd, 4, "离用户输入 " + mn + "ms");
                else if (mn > 2000) add(cd, -8, "离用户输入很久（" + mn + "ms，周期 / 异步刷新）");
            }
            JSONObject ps = r.optJSONObject("passes");
            if (ps != null) {
                boolean any = false, full = true, partial = false;
                StringBuilder pt = new StringBuilder();
                for (int k : present) {
                    if (reps[k] <= 1) continue;
                    any = true;
                    int b = bits(ps.optInt(String.valueOf(k)), reps[k]);
                    if (b < reps[k]) {
                        full = false;
                        partial = true;
                    }
                    pt.append(pt.length() > 0 ? "、" : "").append("任务").append(k).append(' ').append(b).append('/').append(reps[k]);
                }
                if (any && full) add(cd, 8, "每遍都触发（" + pt + "）");
                else if (any && partial) add(cd, -10, "偶发（" + pt + " 遍）");
            }
            if (!r.optString("ownerTop").isEmpty()) add(cd, 5, "查到持有它的容器 " + r.optString("ownerTop"));
            if (present.size() > 0 && total / present.size() > 200) add(cd, -10, "调用极频繁（平均每件事 " + total / present.size() + " 次）");
            JSONObject te = r.optJSONObject("tech");
            if (te != null) {
                JSONArray fl = te.optJSONArray("flags");
                if (fl != null) for (int i = 0; i < fl.length(); i++) {
                    String f = fl.optJSONObject(i).optString("f");
                    if (f.equals("static")) add(cd, -3, "静态方法（没有 this）");
                    else if (f.equals("lambda")) add(cd, -4, "lambda 类名不稳");
                    else if (f.equals("default")) add(cd, -4, "$default 合成方法");
                    else if (f.equals("suspend")) add(cd, -3, "挂起函数（返回值不一定是最终值）");
                    else if (f.equals("boolean")) add(cd, 3, "返回 boolean：可直接改判断");
                }
            }
            JSONObject sk = r.optJSONObject("drawStack");
            JSONArray fi = sk == null ? null : sk.optJSONArray("frameInfo");
            if (fi != null) {
                for (int i = 0; i < fi.length(); i++) {
                    if (fi.optString(i).contains("〔事件分发")) {
                        add(cd, 5, "调用栈里有事件分发（同步于点击 / 触摸）");
                        break;
                    }
                }
            }
            out.add(cd);
            // 栈上游：◎ 推荐钩点帧（不是它自己）也作为候选，找事件入口用
            String bf = AnnotReport.bestFrame(r);
            if (!bf.isEmpty()) {
                int p = bf.indexOf('(');
                String fn = p > 0 ? bf.substring(0, p) : bf;
                int d = fn.lastIndexOf('.');
                if (d > 0) {
                    String fkey = fn.substring(0, d) + "#" + fn.substring(d + 1);
                    if (!fkey.equals(tkey)) {
                        Cand fc = frameCands.get(fkey);
                        if (fc == null) {
                            fc = new Cand();
                            fc.kind = "栈上游";
                            fc.name = fkey;
                            fc.tkey = fkey;
                            fc.r = r;
                            frameCands.put(fkey, fc);
                            if (present.size() == 1 && acts.size() >= 2) add(fc, 30, "所在栈只在" + segName(present.get(0), ts) + "里出现");
                            if (acts.size() >= 2 && present.size() == acts.size()) add(fc, -15, "每件事里都出现");
                            add(fc, 6, "是 " + clip(tkey, 40) + " 调用栈上标 ◎ 的推荐钩点（更靠近事件入口）");
                            String[] g = RecFormat.gradeOf(r).equals("d") ? new String[]{"0", ""} : new String[]{"8", "栈帧没混淆"};
                            if (!g[1].isEmpty()) add(fc, Integer.parseInt(g[0]), g[1]);
                        }
                    }
                }
            }
        }
        out.addAll(frameCands.values());
        Collections.sort(out, new Comparator<Cand>() {
            @Override
            public int compare(Cand a, Cand b) {
                return Integer.compare(b.score, a.score);
            }
        });
        return out;
    }

    static String candidateText(List<Cand> cs, int cap) {
        if (cs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("读法：分数 = 下面每一项证据相加（正分越多越像「这个操作独有、稳定、事件驱动、能读到状态」的钩点；负分是公共代码 / 偶发 / 难钩的扣分）。已排除基线噪声。")
                .append("「栈上游」= 被追踪方法调用栈上标 ◎ 的稳定业务帧，更靠近事件入口，需要时可以改钩它。\n\n");
        for (int i = 0; i < cs.size() && i < cap; i++) {
            Cand c = cs.get(i);
            sb.append(i + 1).append(". [").append(c.score).append(" 分] ").append(c.kind).append("  ").append(c.name);
            JSONObject r = c.r;
            if (c.kind.equals("方法追踪")) {
                sb.append("  评级 ").append(RecFormat.gradeOf(r).toUpperCase(java.util.Locale.ROOT)).append("  #").append(r.optInt("id"));
                String own = r.optString("ownerTop");
                if (!own.isEmpty()) sb.append("  容器 ").append(own);
            }
            sb.append('\n').append("     ").append(String.join("；", c.why)).append('\n');
            if (c.kind.equals("方法追踪")) {
                JSONObject te = r.optJSONObject("tech");
                JSONArray fl = te == null ? null : te.optJSONArray("flags");
                if (fl != null) {
                    for (int k = 0; k < fl.length(); k++) {
                        String f = fl.optJSONObject(k).optString("f");
                        if (f.equals("boolean") || f.equals("void") || f.equals("suspend") || f.equals("lambda") || f.equals("static") || f.equals("overload")) {
                            sb.append("     可钩性: ").append(fl.optJSONObject(k).optString("t")).append('\n');
                        }
                    }
                }
            }
        }
        if (cs.size() > cap) sb.append("… 另有 ").append(cs.size() - cap).append(" 个候选没列\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 钩子草稿

    private static String javaType(String sig) {
        String t = sig.trim();
        switch (t) {
            case "int": case "long": case "boolean": case "float": case "double": case "short": case "byte": case "char":
                return t + ".class";
            default:
                if (t.endsWith("[]")) return "\"" + t + "\" /* 数组类型要写 JVM 名，如 [Ljava.lang.String; */";
                return "\"" + t + "\"";
        }
    }

    static String hookDrafts(List<Cand> cs, List<JSONObject> trees, int cap) {
        HashMap<String, JSONObject> treeBy = new HashMap<>();
        for (JSONObject t : trees) treeBy.put(t.optString("tkey"), t);
        StringBuilder sb = new StringBuilder();
        sb.append("读法：下面是按候选排名生成的 Xposed 钩子骨架（lpparam 是 handleLoadPackage 的参数）。类名 / 字段名被混淆的，换版本要重新找，注释里有结构指纹可以用来重新定位。")
                .append("取值代码来自数据树里「值随任务变化」的节点，先把它们打印出来确认再写判断逻辑。\n");
        int n = 0;
        for (Cand c : cs) {
            if (n >= cap) break;
            n++;
            int h = c.tkey.indexOf('#');
            String cn = c.tkey.substring(0, h), mn = c.tkey.substring(h + 1);
            JSONObject r = c.r;
            JSONObject te = c.kind.equals("方法追踪") ? r.optJSONObject("tech") : null;
            JSONObject fp = c.kind.equals("方法追踪") ? r.optJSONObject("fp") : null;
            sb.append("\n// ── 候选 ").append(n).append("  ").append(c.name).append("  [").append(c.score).append(" 分]\n");
            if (fp != null) {
                sb.append("// 结构指纹 ").append(fp.optString("hash")).append("：父类=").append(fp.optString("super").isEmpty() ? "无" : fp.optString("super"))
                        .append(" 接口=").append(fp.optJSONArray("ifaces") == null ? "[]" : fp.optJSONArray("ifaces").toString())
                        .append(" 字段类型=").append(fp.optString("fields")).append(" 方法签名=").append(fp.optJSONArray("sigs") == null ? "[]" : fp.optJSONArray("sigs").toString())
                        .append(fp.optString("outer").isEmpty() ? "" : " 外层类=" + fp.optString("outer")).append("（? = 混淆名；换版本后可按这些条件在 dex 里重新找，如 DexKit）\n");
            }
            boolean obf = Stacks.isObfuscated(cn, null);
            if (obf) sb.append("// 注意：类名 ").append(cn).append(" 疑似混淆，换版本会变\n");
            JSONArray sigs = te == null ? null : te.optJSONArray("sigs");
            int overloads = te == null ? 0 : te.optInt("overloads");
            boolean single = overloads == 1 && sigs != null && sigs.length() == 1;
            String body = hookBody(c, treeBy.get(c.tkey), te);
            if (single) {
                String s0 = sigs.optString(0);
                String ps = s0.substring(0, s0.indexOf(" → ")).trim();
                StringBuilder args = new StringBuilder();
                if (!ps.isEmpty()) for (String p : ps.split(",")) args.append(javaType(p)).append(", ");
                sb.append("XposedHelpers.findAndHookMethod(\"").append(cn).append("\", lpparam.classLoader, \"").append(mn).append("\", ").append(args)
                        .append("new XC_MethodHook() {\n");
            } else {
                sb.append("XposedBridge.hookAllMethods(XposedHelpers.findClass(\"").append(cn).append("\", lpparam.classLoader), \"").append(mn).append("\", new XC_MethodHook() {\n");
                if (overloads > 1) sb.append("    // 同名重载 ").append(overloads).append(" 个，下面按 param.args.length 区分\n");
            }
            sb.append("    @Override\n    protected void afterHookedMethod(MethodHookParam param) throws Throwable {\n").append(body).append("    }\n});\n");
        }
        if (n == 0) return "";
        return sb.toString();
    }

    private static String hookBody(Cand c, JSONObject tree, JSONObject te) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        if (tree != null) {
            Map<String, JSONObject> nm = nodeMap(tree);
            List<JSONObject> ds = diffNodes(tree);
            if (!ds.isEmpty()) {
                sb.append("        try {\n");
                int k = 0;
                for (JSONObject d : ds) {
                    if (k++ >= 3) break;
                    sb.append("            // ").append(d.optString("p")).append("  值随任务变化\n");
                    String code = accessCode(d.optString("p"), d.optString("t"), d.optString("k"), nm);
                    String[] ls = code.split("\n");
                    sb.append("            {\n");
                    for (String l : ls) sb.append("                ").append(l).append('\n');
                    sb.append("                XposedBridge.log(\"[hook] ").append(d.optString("p")).append(" = \" + v").append(Math.max(1, countVars(code))).append(");\n");
                    sb.append("            }\n");
                }
                sb.append("        } catch (Throwable ignored) {\n        }\n");
                any = true;
            }
        }
        if (te != null) {
            JSONArray fl = te.optJSONArray("flags");
            if (fl != null) for (int i = 0; i < fl.length(); i++) {
                String f = fl.optJSONObject(i).optString("f");
                if (f.equals("boolean")) sb.append("        // 返回 boolean：param.setResult(true / false) 可以直接改判断\n");
                else if (f.equals("void")) sb.append("        // 返回 void：想拦截要用 beforeHookedMethod + param.setResult(null) 跳过原方法\n");
                else if (f.equals("lambda")) sb.append("        // lambda 类：类名不稳，改钩它实现的接口方法，并按外层类过滤 param.thisObject\n");
                else if (f.equals("suspend")) sb.append("        // 挂起函数：after 里的返回值不一定是最终结果\n");
            }
        }
        if (!any && sb.length() == 0) sb.append("        // 这个方法没有记到随任务变化的参数 / 字段：先 XposedBridge.log 打印 param.args / param.thisObject 确认再写逻辑\n");
        return sb.toString();
    }

    private static int countVars(String code) {
        int n = 0;
        for (String l : code.split("\n")) if (l.matches("^(Object|boolean|int|long|float|double) v\\d+ = .*")) n++;
        return n;
    }

    /** 树里值随任务变化的节点个数（给导出概况用）。 */
    static int diffTotal(List<JSONObject> trees) {
        int n = 0;
        for (JSONObject t : trees) n += diffNodes(t).size();
        return n;
    }

    static Set<String> keys(List<JSONObject> trees) {
        TreeSet<String> s = new TreeSet<>();
        for (JSONObject t : trees) s.add(t.optString("tkey"));
        return s;
    }
}
