package com.textrec.lsp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 标注（1.29.0，模块界面这边用）：导出里的两节「任务对比」和「分叉帧」。
 * 都只用快照里已有的数据（按任务阶段的调用次数、调用栈、基线标记）现算，不依赖目标进程。
 */
final class AnnotReport {
    private AnnotReport() {}

    private static final class Row {
        final JSONObject r;
        final int[] c; // 下标 = 任务序号，0 = 任务之外
        final boolean noise;
        final String grade;

        Row(JSONObject r, int[] c) {
            this.r = r;
            this.c = c;
            this.noise = "noise".equals(r.optString("baseline"));
            this.grade = RecFormat.gradeOf(r);
        }

        int total() {
            int t = 0;
            for (int k = 1; k < c.length; k++) t += c[k];
            return t;
        }

        int present(List<Integer> acts) {
            int n = 0;
            for (int k : acts) if (c[k] > 0) n++;
            return n;
        }
    }

    private static int gradeRank(String g) {
        switch (g) {
            case "a": return 0;
            case "b": return 1;
            case "c": return 2;
            case "d": return 3;
            default: return 4;
        }
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String label(JSONObject r) {
        String kind = r.optString("kind");
        String vid = r.optString("viewId");
        String s = "#" + r.optInt("id") + " " + RecFormat.kindLabel(kind) + " " + RecFormat.simple(r.optString("view"));
        if ("TRACE".equals(kind)) return s + "#" + vid;
        return s + (vid.isEmpty() ? "" : "#" + vid) + " 「" + clip(RecFormat.oneLine(r.optString("lastText")), 24) + "」";
    }

    /** 栈上标了 ◎ 的推荐钩点（方法追踪看调用栈，其他看设置栈 / 绘制栈）；没有返回空串。 */
    private static String bestFrame(JSONObject r) {
        String[] keys = {"drawStack", "setStack"};
        for (String k : keys) {
            JSONObject st = r.optJSONObject(k);
            if (st == null) continue;
            JSONArray f = st.optJSONArray("frames"), fi = st.optJSONArray("frameInfo");
            if (f == null || fi == null) continue;
            for (int i = 0; i < fi.length() && i < f.length(); i++) {
                if (fi.optString(i).contains("◎推荐钩点")) return f.optString(i);
            }
        }
        return "";
    }

    private static int[] counts(JSONObject r, int n) {
        int[] c = new int[n + 1];
        JSONObject sg = r.optJSONObject("segCounts");
        if (sg == null) return c;
        for (int k = 0; k <= n; k++) c[k] = sg.optInt(String.valueOf(k));
        return c;
    }

    private static String countsText(int[] c, List<Integer> acts) {
        StringBuilder sb = new StringBuilder();
        for (int k : acts) sb.append(sb.length() > 0 ? " " : "").append(k).append(':').append(c[k]);
        if (c[0] > 0) sb.append(" 任务外:").append(c[0]);
        return sb.toString();
    }

    private static void line(StringBuilder sb, Row w, List<Integer> acts) {
        JSONObject r = w.r;
        sb.append("  ").append(label(r)).append("  评级 ").append(w.grade.toUpperCase(java.util.Locale.ROOT));
        String role = RecFormat.roleOfRec(r);
        if (!role.startsWith("(")) sb.append("  角色 ").append(role);
        String own = r.optString("ownerTop");
        if (!own.isEmpty()) sb.append("  容器 ").append(own);
        sb.append("  各任务次数 ").append(countsText(w.c, acts)).append('\n');
        String bf = bestFrame(r);
        if (!bf.isEmpty()) sb.append("      ◎ 栈上推荐钩点: ").append(clip(bf, 140)).append('\n');
    }

    private static void list(StringBuilder sb, String title, List<Row> rs, List<Integer> acts, int cap) {
        sb.append(title).append("：").append(rs.size()).append(" 条").append(rs.isEmpty() ? "（没有）" : "").append('\n');
        for (int i = 0; i < rs.size() && i < cap; i++) line(sb, rs.get(i), acts);
        if (rs.size() > cap) sb.append("  … 另有 ").append(rs.size() - cap).append(" 条没列（已按评级、次数排序，前面的更值得看）\n");
    }

    /**
     * 任务对比：每件事独有的记录、比对照任务多出来的记录、两两差集、出现在所有事里的（多半是公共噪声）、一张记录 × 任务的次数矩阵。
     * 目的：不用自己在几千条里比，直接看「只在这个操作里触发」的位置。items 只看任务内桶。
     */
    static String taskCompare(List<Snap.Item> items, List<GuideService.Task> ts) {
        int n = ts.size();
        if (n == 0) return "";
        ArrayList<Row> rows = new ArrayList<>();
        for (Snap.Item it : items) {
            JSONObject r = it.o;
            if (r.optInt("bucket") != 0) continue;
            int[] c = counts(r, n);
            int tot = 0;
            for (int k = 1; k <= n; k++) tot += c[k];
            if (tot == 0) continue;
            rows.add(new Row(r, c));
        }
        if (rows.isEmpty()) return "";
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int g = Integer.compare(gradeRank(a.grade), gradeRank(b.grade));
                return g != 0 ? g : Integer.compare(b.total(), a.total());
            }
        });
        ArrayList<Integer> acts = new ArrayList<>(), controls = new ArrayList<>(), cmp = new ArrayList<>();
        for (int k = 1; k <= n; k++) {
            GuideService.Task t = ts.get(k - 1);
            if (t.baseline) continue;
            acts.add(k);
            if (t.allowEmpty) controls.add(k);
            else cmp.add(k);
        }
        if (acts.isEmpty()) return "";
        int noise = 0;
        for (Row w : rows) if (w.noise) noise++;
        StringBuilder sb = new StringBuilder();
        sb.append("读法：每条记录在清单上每件事里出现几次（来自「按任务阶段的调用次数」，格式 任务序号:次数）。「基线噪声」= 安静基线期间也被调用的位置，已从候选里排掉（本次 ")
                .append(noise).append(" 条）。评级 A（覆写框架方法）/ B（稳定业务）越靠前越适合当钩点。\n");

        // 1. 只在某一件事里出现
        for (int k : acts) {
            ArrayList<Row> only = new ArrayList<>();
            for (Row w : rows) {
                if (w.noise || w.c[k] == 0) continue;
                boolean other = false;
                for (int j = 1; j <= n; j++) if (j != k && w.c[j] > 0) other = true;
                if (!other) only.add(w);
            }
            sb.append('\n');
            list(sb, "【只在「任务" + k + " " + ts.get(k - 1).name + "」里出现】", only, acts, 12);
        }

        // 2. 比对照任务多出来的
        if (!controls.isEmpty()) {
            StringBuilder cn = new StringBuilder();
            for (int c : controls) cn.append(cn.length() > 0 ? "、" : "").append("任务").append(c);
            for (int k : cmp) {
                ArrayList<Row> more = new ArrayList<>();
                for (Row w : rows) {
                    if (w.noise || w.c[k] == 0) continue;
                    boolean inControl = false;
                    for (int c : controls) if (w.c[c] > 0) inControl = true;
                    if (!inControl) more.add(w);
                }
                sb.append('\n');
                list(sb, "【任务" + k + " " + ts.get(k - 1).name + " 比对照（" + cn + "）多出来的】", more, acts, 15);
            }
        }

        // 3. 两两差集（对照任务不参与；事情多时只比相邻的两件）
        if (cmp.size() >= 2) {
            ArrayList<int[]> pairs = new ArrayList<>();
            if (cmp.size() <= 4) {
                for (int a : cmp) for (int b : cmp) if (a != b) pairs.add(new int[]{a, b});
            } else {
                for (int i = 0; i + 1 < cmp.size(); i++) {
                    pairs.add(new int[]{cmp.get(i), cmp.get(i + 1)});
                    pairs.add(new int[]{cmp.get(i + 1), cmp.get(i)});
                }
            }
            sb.append("\n【两两差集：有 A 没有 B】").append(cmp.size() > 4 ? "（事情多，只比相邻的两件）" : "").append('\n');
            for (int[] p : pairs) {
                ArrayList<Row> d = new ArrayList<>();
                for (Row w : rows) if (!w.noise && w.c[p[0]] > 0 && w.c[p[1]] == 0) d.add(w);
                sb.append("▶ 有「任务").append(p[0]).append(" ").append(ts.get(p[0] - 1).name).append("」没有「任务").append(p[1]).append(" ")
                        .append(ts.get(p[1] - 1).name).append("」：").append(d.size()).append(" 条\n");
                for (int i = 0; i < d.size() && i < 8; i++) line(sb, d.get(i), acts);
                if (d.size() > 8) sb.append("  … 另有 ").append(d.size() - 8).append(" 条没列\n");
            }
        }

        // 4. 所有事里都出现
        if (acts.size() >= 2) {
            int all = 0;
            for (Row w : rows) if (w.present(acts) == acts.size()) all++;
            sb.append("\n【每件事里都出现的记录】").append(all).append(" 条（多半是公共渲染 / 周期性调用，找某个操作的钩点时可以忽略）\n");
        }

        // 5. 矩阵：先列出现在最少几件事里的
        ArrayList<Row> mx = new ArrayList<>();
        for (Row w : rows) if (!w.noise) mx.add(w);
        final ArrayList<Integer> fa = acts;
        Collections.sort(mx, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int p = Integer.compare(a.present(fa), b.present(fa));
                if (p != 0) return p;
                int g = Integer.compare(gradeRank(a.grade), gradeRank(b.grade));
                return g != 0 ? g : Integer.compare(b.total(), a.total());
            }
        });
        sb.append("\n【次数矩阵】出现在越少件事里的越靠前（只列前 40 条，已排除基线噪声）：\n");
        for (int i = 0; i < mx.size() && i < 40; i++) {
            Row w = mx.get(i);
            sb.append("  ").append(label(w.r)).append("  [").append(w.grade.toUpperCase(java.util.Locale.ROOT)).append("]  ")
                    .append(countsText(w.c, acts)).append('\n');
        }
        if (mx.size() > 40) sb.append("  … 另有 ").append(mx.size() - 40).append(" 条没列\n");
        return sb.toString();
    }

    private static String[] framesOf(JSONObject r) {
        JSONObject st = r.optJSONObject("drawStack");
        JSONArray f = st == null ? null : st.optJSONArray("frames");
        if (f == null || f.length() == 0) return null;
        String[] out = new String[f.length()];
        for (int i = 0; i < out.length; i++) out[i] = f.optString(i);
        return out;
    }

    /**
     * 分叉帧：同一个被追踪方法有好几条不同的调用栈时，从最外层往里比，找出「每条栈都经过的共同外层」，
     * 以及从哪一帧开始各走各的路。共同外层最里面的那一帧就是分叉点；各分支进入分叉点之后的下一帧，就是区分「是哪个操作触发的」的地方。
     */
    static String forkNotes(List<Snap.Item> items) {
        LinkedHashMap<String, ArrayList<JSONObject>> groups = new LinkedHashMap<>();
        for (Snap.Item it : items) {
            JSONObject r = it.o;
            if (!"TRACE".equals(r.optString("kind"))) continue;
            String key = r.optString("view") + "#" + r.optString("viewId");
            ArrayList<JSONObject> l = groups.get(key);
            if (l == null) {
                l = new ArrayList<>();
                groups.put(key, l);
            }
            l.add(r);
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (java.util.Map.Entry<String, ArrayList<JSONObject>> e : groups.entrySet()) {
            ArrayList<JSONObject> recs = new ArrayList<>();
            ArrayList<String[]> fs = new ArrayList<>();
            for (JSONObject r : e.getValue()) {
                String[] f = framesOf(r);
                if (f == null) continue;
                recs.add(r);
                fs.add(f);
            }
            if (fs.size() < 2) continue;
            boolean allSame = true;
            for (int i = 1; i < fs.size() && allSame; i++) allSame = java.util.Arrays.equals(fs.get(0), fs.get(i));
            if (allSame) continue;
            if (shown >= 12) {
                sb.append("… 还有更多有多条调用栈的钩点没列\n");
                break;
            }
            shown++;
            int common = Integer.MAX_VALUE;
            String[] f0 = fs.get(0);
            for (int i = 1; i < fs.size(); i++) {
                String[] fi = fs.get(i);
                int c = 0;
                while (c < f0.length && c < fi.length && f0[f0.length - 1 - c].equals(fi[fi.length - 1 - c])) c++;
                if (c < common) common = c;
            }
            if (common == Integer.MAX_VALUE) common = 0;
            sb.append("钩点 ").append(e.getKey()).append("：").append(fs.size()).append(" 条不同的调用栈\n");
            if (common == 0) {
                sb.append("  最外层就不同：没有共同外层（可能来自不同线程 / 不同入口）\n");
            } else {
                int fork = f0.length - common; // f0 里分叉点（共同外层最里面的一帧）的下标
                JSONObject st0 = recs.get(0).optJSONObject("drawStack");
                JSONArray fi0 = st0 == null ? null : st0.optJSONArray("frameInfo");
                String tag = fi0 == null ? "" : fi0.optString(fork);
                sb.append("  共同外层 ").append(common).append(" 帧（每条栈都经过）；分叉点 = 共同外层最里面一帧：\n")
                        .append("    ").append(f0[fork]).append(tag.isEmpty() ? "" : "   " + tag).append('\n');
            }
            sb.append("  各分支进入分叉点之后的下一帧：\n");
            for (int i = 0; i < fs.size() && i < 8; i++) {
                String[] f = fs.get(i);
                int idx = f.length - common - 1;
                sb.append("    #").append(recs.get(i).optInt("id")).append(" → ").append(idx >= 0 ? f[idx] : "（这条栈到分叉点就结束了，分叉点自己就是被追踪方法）");
                JSONObject st = recs.get(i).optJSONObject("drawStack");
                JSONArray fi = st == null ? null : st.optJSONArray("frameInfo");
                String tg = fi != null && idx >= 0 ? fi.optString(idx) : "";
                if (!tg.isEmpty()) sb.append("   ").append(tg);
                sb.append('\n');
            }
            if (fs.size() > 8) sb.append("    … 另有 ").append(fs.size() - 8).append(" 条分支没列\n");
        }
        return sb.toString();
    }
}
