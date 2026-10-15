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
                        + "  设置事件 " + (st == null ? 0 : st.optLong("setEvents"))
                        + "  绘制事件 " + (st == null ? 0 : st.optLong("drawEvents"))
                        + (st != null && st.optLong("droppedByRecordCap") > 0 ? "  ⚠超出记录上限，丢弃 " + st.optLong("droppedByRecordCap") : "")
                        + "  快照 " + RecFormat.time(root.optLong("snapshotTime"))
                        + (warn.isEmpty() ? "" : "\n⚠ " + warn.trim()));
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

    private static String hay(JSONObject r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(r.optString("kind")).append('\n')
                .append(r.optString("view")).append('\n')
                .append(r.optString("viewId")).append('\n')
                .append(r.optString("activity")).append('\n')
                .append(r.optString("firstText")).append('\n')
                .append(r.optString("lastText")).append('\n');
        JSONArray ps = r.optJSONArray("parents");
        if (ps != null) for (int i = 0; i < ps.length(); i++) sb.append(ps.optString(i)).append('\n');
        appendStack(sb, r.optJSONObject("setStack"));
        appendStack(sb, r.optJSONObject("drawStack"));
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
