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
        if ("ICON".equals(kind)) return "图标";
        if ("TRACE".equals(kind)) return "方法追踪";
        return kind;
    }

    /** ● 在屏幕上  ○ 不在屏幕上  ? 未知（自绘/Compose 没有 View 可查） */
    static String visMark(JSONObject r) {
        String v = r.optString("vis");
        if ("yes".equals(v)) return "●";
        if ("no".equals(v)) return "○";
        return "?";
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
        String kind = r.optString("kind");
        if ("TRACE".equals(kind)) {
            sb.append("调用 ").append(r.optInt("drawCount")).append(" 次（同一个调用位置）");
        } else if ("ICON".equals(kind)) {
            sb.append("设置 ").append(r.optInt("drawCount")).append(" 次 / 该设置位置共调用 ").append(r.optInt("setCount"))
                    .append(" 次(可能含同一代码设置的其他控件)");
        } else {
            sb.append("绘制 ").append(r.optInt("drawCount")).append(" 次 / 该设置位置共调用 ").append(r.optInt("setCount"))
                    .append(" 次(可能含同一代码设置的其他控件)");
        }
        sb.append("   首次 ").append(time(r.optLong("first")))
                .append("  末次 ").append(time(r.optLong("last"))).append('\n');
        String rec = recommend(r);
        if (rec != null) sb.append("推荐 hook 点: ").append(rec).append('\n');
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
        String vis = r.optString("vis");
        JSONArray sc = r.optJSONArray("screen");
        if ("yes".equals(vis) && sc != null && sc.length() > 0) {
            sb.append("● 此刻在屏幕上的文字（").append(sc.length()).append(" 处）：\n");
            for (int i = 0; i < sc.length(); i++) {
                JSONObject so = sc.optJSONObject(i);
                if (so == null) continue;
                JSONArray rc = so.optJSONArray("rect");
                sb.append("  · ").append(oneLine(so.optString("text")));
                if (rc != null && rc.length() == 4) {
                    sb.append("   [").append(rc.optInt(0)).append(',').append(rc.optInt(1)).append(" - ")
                            .append(rc.optInt(2)).append(',').append(rc.optInt(3)).append(']');
                }
                sb.append('\n');
            }
        } else if ("yes".equals(vis)) {
            sb.append("● 最近 3 秒内画过（自绘/Compose，无法查屏幕坐标）\n");
        } else if ("no".equals(vis)) {
            sb.append("○ 此刻不在屏幕上（可能是预加载的、或已离开的页面）\n");
        } else {
            sb.append("? 屏幕可见性未知（自绘/Compose，3 秒内没再画过）\n");
        }
        JSONArray ts = r.optJSONArray("texts");
        int n = ts == null ? 0 : ts.length();
        int more = r.optInt("textsMore");
        String what = "TRACE".equals(kind) ? "调用" : "ICON".equals(kind) ? "图标" : "文字";
        String varLabel = "TRACE".equals(kind) ? "种调用参数（参数 → 返回值）" : "ICON".equals(kind) ? "种图标设置" : "种文字";
        if (n <= 1) {
            sb.append(what).append(": 「").append(oneLine(r.optString("lastText"))).append("」\n");
        } else {
            sb.append("这个位置出现过 ").append(n + more).append(varLabel).append("：\n");
            for (int i = 0; i < n; i++) sb.append("  · ").append(oneLine(ts.optString(i))).append('\n');
            if (more > 0) sb.append("  … 另有 ").append(more).append(" 种未列出\n");
            sb.append("最近一次: 「").append(oneLine(r.optString("lastText"))).append("」\n");
        }
        JSONArray pa = r.optJSONArray("paints");
        if (pa != null && pa.length() > 0) {
            sb.append("画笔/参数（颜色、字号、描边或填充；Compose 文字带 paint 调用的参数）：\n");
            for (int i = 0; i < pa.length(); i++) {
                JSONObject po = pa.optJSONObject(i);
                if (po == null) continue;
                sb.append("  · ").append(oneLine(po.optString("desc"))).append("  ×").append(po.optInt("n")).append('\n');
            }
            int pm = r.optInt("paintsMore");
            if (pm > 0) sb.append("  … 另有 ").append(pm).append(" 种未列出\n");
        }
        JSONArray pos = r.optJSONArray("pos");
        if (pos != null && pos.length() == 2) {
            sb.append("画布内坐标(最近一次，不是屏幕坐标): (").append(pos.optDouble(0)).append(", ").append(pos.optDouble(1)).append(")\n");
        }
        String tc = r.optString("textColor");
        if (!tc.isEmpty()) sb.append("文字颜色(最近一次被设置): ").append(tc).append("   设置位置见左栏末尾\n");
        return sb.toString();
    }

    /** 推荐的 hook 点：优先“没混淆”的（换版本还能用），没有再退而求其次用混淆的。 */
    static String recommend(JSONObject r) {
        String any = null;
        String[] keys = {"drawStack", "setStack", "colorStack"};
        for (String k : keys) {
            JSONObject s = r.optJSONObject(k);
            JSONArray g = s == null ? null : s.optJSONArray("sigs");
            if (g == null) continue;
            for (int i = 0; i < g.length(); i++) {
                String sig = g.optString(i);
                if (sig.contains("类未能加载") || sig.contains("反射失败") || sig.contains("没找到")) continue;
                if (sig.endsWith(Stacks.TAG_STABLE)) return sig.trim();
                if (any == null) any = sig.trim();
            }
        }
        return any == null ? null : any + "   （只有混淆名，App 更新后要重新找）";
    }

    /** 列表里的一行文字摘要：这个位置下的前几种文字 */
    static String samplesLine(JSONObject r, int perItem, int maxItems) {
        JSONArray sc = r.optJSONArray("screen");
        if (sc != null && sc.length() > 0) {
            StringBuilder sb = new StringBuilder("「");
            for (int i = 0; i < sc.length() && i < maxItems; i++) {
                JSONObject so = sc.optJSONObject(i);
                if (i > 0) sb.append(" / ");
                sb.append(clipTo(oneLine(so == null ? "" : so.optString("text")), perItem));
            }
            sb.append("」");
            if (sc.length() > maxItems) sb.append(" 等").append(sc.length()).append("处");
            return sb.toString();
        }
        JSONArray ts = r.optJSONArray("texts");
        int n = ts == null ? 0 : ts.length();
        int total = n + r.optInt("textsMore");
        if (n == 0) return "「" + clipTo(oneLine(r.optString("lastText")), perItem * 2) + "」";
        StringBuilder sb = new StringBuilder("「");
        for (int i = 0; i < n && i < maxItems; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(clipTo(oneLine(ts.optString(i)), perItem));
        }
        sb.append("」");
        if (total > maxItems) sb.append(" 等").append(total).append("种");
        return sb.toString();
    }

    private static String clipTo(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
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
        String kind = r.optString("kind");
        String title = "ICON".equals(kind) ? "设置栈 setImage*/setContentDescription" : "设置栈 setText";
        String s = column(title, r.optJSONObject("setStack"), r.optString("setNote"));
        JSONObject cs = r.optJSONObject("colorStack");
        if (cs != null) {
            s += "\n" + column("文字颜色设置栈 setTextColor（颜色 " + r.optString("textColor") + "）", cs, "");
        } else if (!r.optString("textColor").isEmpty()) {
            s += "\n【文字颜色】" + r.optString("textColor") + "（设置栈被限流，没抓到）\n";
        }
        return s;
    }

    static String drawColumn(Snap.Item it) {
        JSONObject r = it.o;
        String title = "TRACE".equals(r.optString("kind")) ? "调用栈（谁调用了它）" : "绘制栈 draw";
        return column(title, r.optJSONObject("drawStack"), r.optString("drawNote"));
    }

    static String full(Snap.Item it) {
        return header(it) + "\n" + setColumn(it) + "\n" + drawColumn(it);
    }

    /** 只要可 hook 的签名（设置侧、绘制侧各一段） */
    static String sigsOnly(Snap.Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(it.o.optString("view")).append("  文字「").append(oneLine(it.o.optString("lastText"))).append("」\n");
        String rec = recommend(it.o);
        if (rec != null) sb.append("推荐: ").append(rec).append('\n');
        appendSigs(sb, "设置侧", it.o.optJSONObject("setStack"));
        appendSigs(sb, "绘制/调用侧", it.o.optJSONObject("drawStack"));
        appendSigs(sb, "颜色设置侧", it.o.optJSONObject("colorStack"));
        return sb.toString();
    }

    private static void appendSigs(StringBuilder sb, String label, JSONObject s) {
        sb.append(label).append(":\n");
        JSONArray g = s == null ? null : s.optJSONArray("sigs");
        if (g == null || g.length() == 0) {
            sb.append("  (无)\n");
            return;
        }
        for (int pass = 0; pass < 2; pass++) { // 先列稳定的，再列混淆的
            for (int i = 0; i < g.length(); i++) {
                String sig = g.optString(i);
                boolean stable = sig.endsWith(Stacks.TAG_STABLE);
                if ((pass == 0) == stable) sb.append("  ").append(sig).append('\n');
            }
        }
    }
}
