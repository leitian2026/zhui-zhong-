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
        if (dr > 0) sb.append("  ⚠ 超过 3000 条上限，最早的 ").append(dr).append(" 条已丢（环形保留最近的）");
        sb.append("  （前一列是单调时钟毫秒，相对目标进程开始记录；同一毫秒内看序号 #）\n");
        for (int i = 0; i < tl.length(); i++) {
            JSONObject e = tl.optJSONObject(i);
            if (e == null) continue;
            sb.append(String.format(Locale.US, "%8d #%-5d %-8s ", e.optLong("t"), e.optLong("seq"), e.optString("kind")))
                    .append(e.optString("text"));
            String tg = e.optString("tag");
            if (!tg.isEmpty()) sb.append("   <标识 ").append(tg).append('>');
            sb.append("   [").append(e.optString("page")).append("]\n");
        }
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
                JSONArray rs = root.optJSONArray("records");
                JSONObject st = root.optJSONObject("stats");
                String warn = root.optString("warn", "");
                infos.add(proc + "  版本 " + root.optString("appVersion", "?")
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
                JSONObject env = root.optJSONObject("env");
                if (env != null) {
                    infos.add(proc + "  环境：" + env.optString("model") + "  Android " + env.optString("release") + "(API " + env.optInt("sdk")
                            + ")  " + env.optString("abi") + "  App " + env.optString("appVersion") + "  模块 " + root.optString("module"));
                }
                if (root.optBoolean("baselineDone")) infos.add(proc + "  基线：已录 " + root.optLong("baselineMs") + "ms（记录里标了“基线里也在调用”的是周期性噪声）");
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
