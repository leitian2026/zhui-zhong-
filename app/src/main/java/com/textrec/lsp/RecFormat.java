package com.textrec.lsp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 把一条记录格式化成人能读的文字（详情页、txt 导出共用）。 */
final class RecFormat {
    private RecFormat() {}

    static String time(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    static String kindLabel(String kind) {
        if ("TEXTVIEW".equals(kind)) return "TextView";
        if ("COMPOSE".equals(kind)) return "Compose";
        if ("CANVAS".equals(kind)) return "自绘";
        return kind;
    }

    static String simple(String cls) {
        if (cls == null) return "";
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }

    /** 记录概况（不含栈） */
    static String header(Snap.Item it) {
        JSONObject r = it.o;
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(r.optInt("id")).append("  ").append(kindLabel(r.optString("kind")))
                .append("  进程 ").append(it.proc).append('\n');
        sb.append("绘制 ").append(r.optInt("drawCount")).append(" 次 / 设置 ").append(r.optInt("setCount"))
                .append(" 次   首次 ").append(time(r.optLong("first")))
                .append("  末次 ").append(time(r.optLong("last"))).append('\n');
        String id = r.optString("viewId");
        sb.append("View: ").append(r.optString("view")).append(id.isEmpty() ? "" : "   id=" + id).append('\n');
        String act = r.optString("activity");
        sb.append("页面: ").append(act.isEmpty() ? "(启动中，尚无 Activity)" : act).append('\n');
        JSONArray ps = r.optJSONArray("parents");
        if (ps != null && ps.length() > 0) {
            sb.append("父级: ");
            for (int i = 0; i < ps.length(); i++) {
                if (i > 0) sb.append("  >  ");
                sb.append(ps.optString(i));
            }
            sb.append('\n');
        }
        sb.append("文字: 首次「").append(oneLine(r.optString("firstText"))).append("」 最近「")
                .append(oneLine(r.optString("lastText"))).append("」\n");
        return sb.toString();
    }

    /** 一栏：设置栈或绘制栈。stack 为 null 时显示 note。 */
    static String column(String title, JSONObject stack, String note) {
        StringBuilder sb = new StringBuilder();
        if (stack == null) {
            sb.append("【").append(title).append("】\n").append(note == null ? "" : note).append('\n');
            return sb.toString();
        }
        sb.append("【").append(title).append("】线程 ").append(stack.optString("thread")).append('\n');
        JSONArray f = stack.optJSONArray("frames");
        if (f != null) for (int i = 0; i < f.length(); i++) sb.append("  at ").append(f.optString(i)).append('\n');
        int more = stack.optInt("more");
        if (more > 0) sb.append("  … 另有 ").append(more).append(" 帧\n");
        JSONArray g = stack.optJSONArray("sigs");
        sb.append("\n可 hook 的方法签名：\n");
        if (g == null || g.length() == 0) {
            sb.append("  (栈顶没有 App 帧，或正在解析)\n");
        } else {
            for (int i = 0; i < g.length(); i++) sb.append("  ").append(g.optString(i)).append('\n');
        }
        return sb.toString();
    }

    static String setColumn(Snap.Item it) {
        JSONObject r = it.o;
        return column("设置栈 setText", r.optJSONObject("setStack"), r.optString("setNote"));
    }

    static String drawColumn(Snap.Item it) {
        return column("绘制栈 draw", it.o.optJSONObject("drawStack"), "");
    }

    static String full(Snap.Item it) {
        return header(it) + "\n" + setColumn(it) + "\n" + drawColumn(it);
    }

    /** 只要可 hook 的签名（设置侧、绘制侧各一段） */
    static String sigsOnly(Snap.Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(it.o.optString("view")).append("  文字「").append(oneLine(it.o.optString("lastText"))).append("」\n");
        appendSigs(sb, "设置侧", it.o.optJSONObject("setStack"));
        appendSigs(sb, "绘制侧", it.o.optJSONObject("drawStack"));
        return sb.toString();
    }

    private static void appendSigs(StringBuilder sb, String label, JSONObject s) {
        sb.append(label).append(":\n");
        JSONArray g = s == null ? null : s.optJSONArray("sigs");
        if (g == null || g.length() == 0) {
            sb.append("  (无)\n");
            return;
        }
        for (int i = 0; i < g.length(); i++) sb.append("  ").append(g.optString(i)).append('\n');
    }
}
