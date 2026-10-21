package com.textrec.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视图树对比（1.27.0，模块界面这边用，不进目标进程）：把相邻两份视图树逐个控件对一遍，只写差别。
 * 用来回答「有作者声明的视频和没有的，到底多了 / 少了哪个控件、哪个从可见变成了不占位」。
 *
 * 对不上的做法：控件按「父路径 + 类名 + id + 同级同名的第几个」认，不按子序号认，
 * 所以中间多插一个控件不会让后面全部错位。只比 ViewDump 导出的树（kind = tree），点击那种平铺列表不比。
 */
final class ViewDiff {
    private ViewDiff() {}

    private static final class Node {
        String path, cls, id, vis, lp, text, lines;
        int x, y, w, h;
        boolean hasBox;
    }

    private static final Pattern LINE = Pattern.compile("^(\\s*)(?:\\[(\\d+)\\] )?(\\S+?)(?: #(\\S+))? (V|I\\(占位隐藏\\)|G\\(不占位\\))(.*)$");
    private static final Pattern BOX = Pattern.compile("@\\((-?\\d+),(-?\\d+)\\) (\\d+)x(\\d+)");
    private static final Pattern LP = Pattern.compile("lp\\[([^\\]]*)\\]");
    private static final Pattern TX = Pattern.compile("「(.*)」");
    private static final Pattern LN = Pattern.compile("行数(\\d+)/最多(\\S+)");

    /** texts / titles 一一对应；只有两份以上才有对比。 */
    static String diff(List<String> titles, List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < texts.size(); i++) {
            sb.append("对比 ").append(titles.get(i)).append("  →  ").append(titles.get(i + 1)).append('\n');
            sb.append(pair(texts.get(i), texts.get(i + 1))).append('\n');
        }
        return sb.toString();
    }

    private static LinkedHashMap<String, Node> parse(String text) {
        LinkedHashMap<String, Node> out = new LinkedHashMap<>();
        boolean in = false;
        ArrayList<String> stack = new ArrayList<>(); // 每一层当前的路径
        HashMap<String, Integer> occ = new HashMap<>();
        for (String raw : text.split("\n")) {
            if (raw.startsWith("从上 ")) {
                in = true;
                continue;
            }
            if (!in) continue;
            if (raw.trim().isEmpty()) break;
            if (raw.trim().startsWith("↳") || raw.trim().startsWith("（还有") || raw.trim().startsWith("…")) continue;
            Matcher m = LINE.matcher(raw);
            if (!m.matches()) continue;
            int depth = m.group(1).length() / 2;
            String cls = m.group(3);
            String id = m.group(4) == null ? "" : m.group(4);
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            String key = simple + (id.isEmpty() ? "" : "#" + id);
            while (stack.size() > depth) stack.remove(stack.size() - 1);
            String parent = stack.isEmpty() ? "" : stack.get(stack.size() - 1);
            String base = parent + "/" + key;
            int k = occ.containsKey(base) ? occ.get(base) + 1 : 0;
            occ.put(base, k);
            String path = k == 0 ? base : base + "(" + (k + 1) + ")";
            stack.add(path);
            Node n = new Node();
            n.path = path;
            n.cls = simple;
            n.id = id;
            n.vis = m.group(5).substring(0, 1);
            String rest = m.group(6);
            Matcher b = BOX.matcher(rest);
            if (b.find()) {
                n.hasBox = true;
                n.x = Integer.parseInt(b.group(1));
                n.y = Integer.parseInt(b.group(2));
                n.w = Integer.parseInt(b.group(3));
                n.h = Integer.parseInt(b.group(4));
            }
            Matcher l = LP.matcher(rest);
            n.lp = l.find() ? l.group(1) : "";
            Matcher t = TX.matcher(rest);
            n.text = t.find() ? t.group(1) : null;
            Matcher g = LN.matcher(rest);
            n.lines = g.find() ? g.group(1) + "/" + g.group(2) : "";
            out.put(path, n);
        }
        return out;
    }

    private static String shortPath(String p) {
        String[] seg = p.split("/");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(1, seg.length - 3);
        if (from > 1) sb.append("…/");
        for (int i = from; i < seg.length; i++) {
            if (i > from) sb.append('/');
            sb.append(seg[i]);
        }
        return sb.toString();
    }

    private static String vis(String v) {
        return v.equals("V") ? "可见" : v.equals("I") ? "占位隐藏" : "不占位";
    }

    private static String pair(String a, String b) {
        Map<String, Node> A = parse(a), B = parse(b);
        if (A.isEmpty() || B.isEmpty()) return "  （其中一份没有找到树，不比）\n";
        StringBuilder add = new StringBuilder(), gone = new StringBuilder(), chg = new StringBuilder();
        int moved = 0, same = 0;
        for (Map.Entry<String, Node> e : B.entrySet()) {
            Node nb = e.getValue();
            Node na = A.get(e.getKey());
            if (na == null) {
                add.append("  ＋ ").append(shortPath(nb.path)).append("  ").append(vis(nb.vis))
                        .append(nb.hasBox ? "  " + nb.w + "x" + nb.h : "")
                        .append(nb.lp.isEmpty() ? "" : "  lp[" + nb.lp + "]")
                        .append(nb.text == null ? "" : "  「" + nb.text + "」").append('\n');
                continue;
            }
            StringBuilder d = new StringBuilder();
            if (!na.vis.equals(nb.vis)) d.append("  可见性 ").append(vis(na.vis)).append("→").append(vis(nb.vis));
            if (na.hasBox && nb.hasBox && (na.w != nb.w || na.h != nb.h)) d.append("  尺寸 ").append(na.w).append('x').append(na.h).append("→").append(nb.w).append('x').append(nb.h);
            if (!na.lp.equals(nb.lp)) d.append("  布局参数 [").append(na.lp).append("]→[").append(nb.lp).append(']');
            if (!na.lines.equals(nb.lines)) d.append("  行数/最多 ").append(na.lines).append("→").append(nb.lines);
            String ta = na.text == null ? "" : na.text, tb = nb.text == null ? "" : nb.text;
            if (!ta.equals(tb)) d.append("  文字 「").append(ta).append("」→「").append(tb).append("」");
            if (d.length() > 0) {
                chg.append("  ～ ").append(shortPath(nb.path)).append(d).append('\n');
            } else if (na.hasBox && nb.hasBox && (na.x != nb.x || na.y != nb.y)) {
                moved++;
            } else {
                same++;
            }
        }
        for (Map.Entry<String, Node> e : A.entrySet()) {
            if (B.containsKey(e.getKey())) continue;
            Node na = e.getValue();
            gone.append("  － ").append(shortPath(na.path)).append("  原来").append(vis(na.vis))
                    .append(na.hasBox ? "  " + na.w + "x" + na.h : "")
                    .append(na.text == null ? "" : "  「" + na.text + "」").append('\n');
        }
        StringBuilder sb = new StringBuilder();
        if (add.length() == 0 && gone.length() == 0 && chg.length() == 0) {
            sb.append("  结构、可见性、尺寸、布局参数、文字都没变");
        } else {
            sb.append(add).append(gone).append(chg);
        }
        sb.append("  （没变 ").append(same).append(" 个；只有坐标变了 ").append(moved).append(" 个，没列）\n");
        return sb.toString();
    }
}
