package com.textrec.lsp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 模块界面这边的快照解析与数据持有。 */
final class Snap {
    private Snap() {}

    static final class Item {
        final JSONObject o;
        final String proc;
        final String hay; // 小写的可搜索文本：文字、View、页面、栈帧……

        Item(JSONObject o, String proc, String hay) {
            this.o = o;
            this.proc = proc;
            this.hay = hay;
        }
    }

    /** 从列表页带到详情页的当前条目 */
    static volatile Item current;

    /** 各进程的时间线文字（导出用）；每次刷新前先 resetExtras()。 */
    static volatile String timelineText = "";

    static void resetExtras() {
        timelineText = "";
    }

    private static String timelineOf(JSONObject root, String proc) {
        JSONArray tl = root.optJSONArray("timeline");
        if (tl == null || tl.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("进程 ").append(proc).append("：").append(tl.length()).append(" 条");
        long dr = root.optLong("timelineDropped");
        if (dr > 0) sb.append("  ⚠ 关键事件超过 3000 条，最早的 ").append(dr).append(" 条已丢");
        JSONObject kd = root.optJSONObject("timelineKindDropped");
        if (kd != null && kd.length() > 0) {
            sb.append("  ⚠ 采样事件每种、每个任务阶段最多记 150 种不同的（完全相同的合并成 ×N，不占额度），超出没记的种数：");
            java.util.Iterator<String> it = kd.keys();
            while (it.hasNext()) {
                String k = it.next();
                int h = k.indexOf('#');
                String seg = h > 0 ? k.substring(h + 1) : "0";
                sb.append(h > 0 ? k.substring(0, h) : k).append(seg.equals("0") ? "(任务之外)" : "(任务" + seg + ")")
                        .append(' ').append(kd.optInt(k)).append("  ");
            }
        }
        sb.append("\n（前一列是单调时钟毫秒，相对目标进程开始记录；同一毫秒内看序号 #；“✗”开头 = 被「重做」作废的那一次）\n");
        int n = tl.length();
        String[] lines = new String[n];
        boolean[] voided = new boolean[n];
        java.util.HashMap<Integer, Integer> startIdx = new java.util.HashMap<>();
        java.util.regex.Pattern pt = java.util.regex.Pattern.compile("任务(开始|重做) (\\d+)");
        for (int i = 0; i < n; i++) {
            JSONObject e = tl.optJSONObject(i);
            if (e == null) {
                lines[i] = "";
                continue;
            }
            StringBuilder l = new StringBuilder();
            l.append(String.format(Locale.US, "%8d #%-5d %-8s ", e.optLong("t"), e.optLong("seq"), e.optString("kind")))
                    .append(e.optString("text"));
            String tg = e.optString("tag");
            if (e.optInt("n") > 1) l.append("  ×").append(e.optInt("n")).append("（最后一次 +").append(e.optLong("lastT")).append("）");
            if (!tg.isEmpty()) l.append("   <标识 ").append(tg).append('>');
            l.append("   [").append(e.optString("page")).append("]\n");
            lines[i] = l.toString();
            if ("TASK".equals(e.optString("kind"))) {
                java.util.regex.Matcher m = pt.matcher(e.optString("text"));
                if (m.find()) {
                    int no = Integer.parseInt(m.group(2));
                    if ("重做".equals(m.group(1))) {
                        Integer st = startIdx.get(no);
                        if (st != null) for (int k = st; k < i; k++) voided[k] = true;
                    }
                    startIdx.put(no, i);
                }
            }
        }
        for (int i = 0; i < n; i++) sb.append(voided[i] ? "✗" : " ").append(lines[i]);
        return sb.toString();
    }

    /** raw 是 RootShell.pullSnapshots 的输出；infos 追加每个进程的概况。 */
    static List<Item> parse(String raw, List<String> infos) {
        ArrayList<Item> items = new ArrayList<>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (!line.startsWith("{")) continue;
            try {
                JSONObject root = new JSONObject(line);
                String proc = root.optString("process", "?");
                final boolean part = root.has("part"); // 分文件存的一部分（某一种记录 / 时间线）：概况只在主文件里显示一次
                JSONArray rs = root.optJSONArray("records");
                JSONObject st = root.optJSONObject("stats");
                String warn = root.optString("warn", "");
                if (!part) infos.add(proc + "  版本 " + root.optString("appVersion", "?")
                        + "  记录 " + (st == null ? 0 : st.optInt("records"))
                        + (st != null && st.optInt("setOnly") > 0 ? "（含仅设置未绘制 " + st.optInt("setOnly") + "）" : "")
                        + "  设置事件 " + (st == null ? 0 : st.optLong("setEvents"))
                        + "  绘制事件 " + (st == null ? 0 : st.optLong("drawEvents"))
                        + (st != null && st.optLong("droppedByRecordCap") > 0 ? "  ⚠超出记录上限，丢弃 " + st.optLong("droppedByRecordCap") : "")
                        + (st != null && st.optLong("droppedBySetOnlyCap") > 0 ? "  ⚠仅设置记录超出上限，丢弃 " + st.optLong("droppedBySetOnlyCap") + "（可调大“仅设置记录上限”）" : "")
                        + (st != null && st.optLong("droppedByRateLimit") > 0 ? "  ⚠限流丢弃 " + st.optLong("droppedByRateLimit") + " 次抓栈（可调大“每秒抓栈上限”）" : "")
                        + blocked(st)
                        + "  快照 " + RecFormat.time(root.optLong("snapshotTime"))
                        + (warn.isEmpty() ? "" : "\n⚠ " + warn.trim()));
                if (!part) kindInfo(infos, proc, st, root.optBoolean("splitFiles"));
                JSONObject env = root.optJSONObject("env");
                if (!part && env != null) {
                    infos.add(proc + "  环境：" + env.optString("model") + "  Android " + env.optString("release") + "(API " + env.optInt("sdk")
                            + ")  " + env.optString("abi") + "  App " + env.optString("appVersion") + "  模块 " + root.optString("module"));
                }
                if (!part && root.optBoolean("baselineDone")) infos.add(proc + "  基线：已录 " + root.optLong("baselineMs") + "ms（记录里标了“基线里也在调用”的是周期性噪声）");
                String tlx = timelineOf(root, proc);
                if (!tlx.isEmpty()) timelineText = timelineText + tlx + "\n";
                if (rs == null) continue;
                for (int i = 0; i < rs.length(); i++) {
                    JSONObject r = rs.getJSONObject(i);
                    r.put("process", proc);
                    items.add(new Item(r, proc, hay(r)));
                }
            } catch (Throwable t) {
                infos.add("快照解析失败：" + t.getMessage());
            }
        }
        return items;
    }

    /** 每种记录各自的条数；哪一种满了（丢了记录）单独写出来。 */
    private static void kindInfo(List<String> infos, String proc, JSONObject st, boolean split) {
        if (st == null) return;
        JSONObject kc = st.optJSONObject("recordsByKind");
        JSONObject kd = st.optJSONObject("droppedByKindCap");
        if (kc == null) return;
        StringBuilder sb = new StringBuilder(proc).append("  各类记录").append(split ? "（分文件存，各自独立）：" : "（一个文件）：");
        java.util.Iterator<String> it = kc.keys();
        while (it.hasNext()) {
            String k = it.next();
            int n = kc.optInt(k);
            long d = kd == null ? 0 : kd.optLong(k);
            sb.append(k.endsWith(".OUT") ? k.substring(0, k.length() - 4) + "(任务外)" : k).append(' ').append(n);
            if (d > 0) sb.append("（⚠满了，丢了 ").append(d).append(" 条新位置）");
            sb.append("  ");
        }
        if (kd != null) {
            java.util.Iterator<String> di = kd.keys();
            while (di.hasNext()) {
                String k = di.next();
                if (!kc.has(k) && kd.optLong(k) > 0) sb.append(k.endsWith(".OUT") ? k.substring(0, k.length() - 4) + "(任务外)" : k).append(" 0（⚠满了，丢了 ").append(kd.optLong(k)).append(" 条新位置）  ");
            }
        }
        infos.add(sb.toString().trim());
    }

    /** 采集端过滤各挡掉了多少次；全是 0 就不显示。用来判断“记录不够”是不是被某个开关挡掉了。 */
    private static String blocked(JSONObject st) {
        if (st == null) return "";
        StringBuilder sb = new StringBuilder();
        blk(sb, "触发窗口", st.optLong("blockedByWindow"));
        blk(sb, "线程", st.optLong("blockedByThread"));
        blk(sb, "页面", st.optLong("blockedByPage"));
        blk(sb, "控件", st.optLong("blockedByView"));
        blk(sb, "排除名单", st.optLong("blockedByExclude"));
        blk(sb, "值匹配", st.optLong("blockedByValue"));
        long q = st.optLong("quietCounted");
        String out = sb.length() == 0 ? "" : "  已挡掉：" + sb;
        if (q > 0) out += "  持续型降频只计数 " + q + " 次";
        return out;
    }

    private static void blk(StringBuilder sb, String name, long n) {
        if (n <= 0) return;
        if (sb.length() > 0) sb.append(" / ");
        sb.append(name).append(' ').append(n);
    }

    private static String hay(JSONObject r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(r.optString("kind")).append('\n')
                .append(r.optString("view")).append('\n')
                .append(r.optString("viewId")).append('\n')
                .append(r.optString("activity")).append('\n')
                .append(r.optString("page")).append('\n')
                .append(r.optString("firstText")).append('\n')
                .append(r.optString("lastText")).append('\n')
                .append(r.optString("stateDump")).append('\n')
                .append(r.optString("lastTag")).append('\n')
                .append(r.optString("hookInfo")).append('\n')
                .append(r.optString("chain")).append('\n');
        JSONArray ps = r.optJSONArray("parents");
        if (ps != null) for (int i = 0; i < ps.length(); i++) sb.append(ps.optString(i)).append('\n');
        JSONArray ts = r.optJSONArray("texts");
        if (ts != null) for (int i = 0; i < ts.length(); i++) sb.append(ts.optString(i)).append('\n');
        JSONArray sc = r.optJSONArray("screen");
        if (sc != null) for (int i = 0; i < sc.length(); i++) {
            JSONObject so = sc.optJSONObject(i);
            if (so != null) sb.append(so.optString("text")).append('\n');
        }
        sb.append(r.optString("textColor")).append('\n');
        JSONArray pj = r.optJSONArray("paints");
        if (pj != null) for (int i = 0; i < pj.length(); i++) {
            JSONObject po = pj.optJSONObject(i);
            if (po != null) sb.append(po.optString("desc")).append('\n');
        }
        appendStack(sb, r.optJSONObject("setStack"));
        appendStack(sb, r.optJSONObject("drawStack"));
        appendStack(sb, r.optJSONObject("colorStack"));
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendStack(StringBuilder sb, JSONObject s) {
        if (s == null) return;
        JSONArray f = s.optJSONArray("frames");
        if (f != null) for (int i = 0; i < f.length(); i++) sb.append(f.optString(i)).append('\n');
        JSONArray g = s.optJSONArray("sigs");
        if (g != null) for (int i = 0; i < g.length(); i++) sb.append(g.optString(i)).append('\n');
    }
}
