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
        if ("TEXTSET".equals(kind)) return "仅设置";
        if ("STATE".equals(kind)) return "状态变化";
        return kind;
    }

    /** ● 在屏幕上  ○ 不在屏幕上  ? 未知（自绘/Compose 没有 View 可查） */
    static String visMark(JSONObject r) {
        String v = r.optString("vis");
        if ("yes".equals(v)) return "●";
        if ("no".equals(v)) return "○";
        return "?";
    }

    /** 记录所属页面：优先 Activity > Fragment，旧快照没有 page 字段就退回 Activity 简名。 */
    static String rawPageOf(JSONObject r) {
        String p = r.optString("page");
        if (!p.isEmpty()) return p;
        String a = r.optString("activity");
        return a.isEmpty() ? "(启动中)" : simple(a);
    }

    /** 用户自己起的页面名（原始页面名 → 显示名），由 RecordsActivity 读写；多个原始名可以起同一个名字，合成一类。 */
    static final java.util.HashMap<String, String> ALIAS = new java.util.HashMap<>();
    /** 内置的常见页面名（按 Fragment 简名）。只放把握大的，其余让用户自己改。 */
    private static final java.util.HashMap<String, String> DEFAULT_ALIAS = new java.util.HashMap<>();
    static {
        DEFAULT_ALIAS.put("MessagesFragment3", "消息");
        DEFAULT_ALIAS.put("FeedRecommendFragment", "推荐");
        DEFAULT_ALIAS.put("FeedFollowFragment", "关注");
    }

    static String aliasOfRaw(String raw) {
        String a = ALIAS.get(raw);
        if (a != null && !a.isEmpty()) return a;
        int i = raw.lastIndexOf(" > ");
        String leaf = i >= 0 ? raw.substring(i + 3) : raw;
        a = DEFAULT_ALIAS.get(leaf);
        return a == null ? raw : a;
    }

    /** 显示用的页面名（按名称分类用它）：有别名用别名，没有就是原始页面名。 */
    static String pageOf(JSONObject r) {
        return aliasOfRaw(rawPageOf(r));
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
        } else if ("STATE".equals(kind)) {
            sb.append("变化 ").append(r.optInt("drawCount")).append(" 次（含重复）");
        } else if ("ICON".equals(kind)) {
            sb.append("设置 ").append(r.optInt("drawCount")).append(" 次 / 该设置位置共调用 ").append(r.optInt("setCount"))
                    .append(" 次(可能含同一代码设置的其他控件)");
        } else if ("TEXTSET".equals(kind)) {
            sb.append("绘制 0 次（从未绘制）/ 该设置位置共调用 ").append(r.optInt("setCount"))
                    .append(" 次(可能含同一代码设置的其他控件)");
        } else {
            sb.append("绘制 ").append(r.optInt("drawCount")).append(" 次 / 该设置位置共调用 ").append(r.optInt("setCount"))
                    .append(" 次(可能含同一代码设置的其他控件)");
        }
        sb.append("   首次 ").append(time(r.optLong("first")))
                .append("  末次 ").append(time(r.optLong("last"))).append('\n');
        appendRecommend(sb, r);
        String id = r.optString("viewId");
        JSONArray idsA = r.optJSONArray("ids");
        String idTxt;
        if (idsA != null && idsA.length() > 1) {
            StringBuilder ib = new StringBuilder();
            for (int i = 0; i < idsA.length() && i < 12; i++) {
                if (i > 0) ib.append(", ");
                ib.append(idsA.optString(i));
            }
            if (idsA.length() > 12) ib.append(" …");
            idTxt = "   id=" + ib + "（同一段代码设置的 " + idsA.length() + (r.optBoolean("idsFull") ? "+" : "") + " 个控件合并在这一条）";
        } else {
            idTxt = id.isEmpty() ? "" : "   id=" + id;
        }
        sb.append("View: ").append(r.optString("view")).append(idTxt).append('\n');
        String act = r.optString("activity");
        String pg = r.optString("page");
        if (r.optJSONArray("thumbs") != null) sb.append("图标图片: ").append(r.optJSONArray("thumbs").length()).append(" 张（txt 放不了图，见记录页缩略图 / 导出 HTML / JSON 的 thumbs）\n");
        String rawPg = !pg.isEmpty() ? pg : act.isEmpty() ? "(启动中，尚无 Activity)" : act;
        String label = pageOf(r);
        sb.append("页面: ").append(label).append(label.equals(rawPg) ? "" : "（" + rawPg + "）").append('\n');
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
        if ("TEXTSET".equals(kind)) {
            JSONArray ud = r.optJSONArray("unDrawn");
            sb.append("◌ 仅设置·从未绘制：这些文字被 setText 设置过，但所在控件一直没被画出来，")
                    .append("所以不会出现在“绘制”记录里。\n");
            if (ud != null && ud.length() > 0) {
                sb.append("未绘制的控件（").append(ud.length()).append(" 个；文字是它们【当前】的，原因是它们没被画的原因）：\n");
                for (int i = 0; i < ud.length(); i++) {
                    JSONObject uo = ud.optJSONObject(i);
                    if (uo == null) continue;
                    sb.append("  · 「").append(oneLine(uo.optString("text"))).append("」  尺寸 ")
                            .append(uo.optInt("w")).append('×').append(uo.optInt("h"))
                            .append("  原因: ").append(uo.optString("reason")).append('\n');
                }
            }
        } else if ("yes".equals(vis) && sc != null && sc.length() > 0) {
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
        String what = "TRACE".equals(kind) ? "调用" : "ICON".equals(kind) ? "图标" : "STATE".equals(kind) ? "状态" : "文字";
        String varLabel = "TRACE".equals(kind) ? "种调用参数（this + 参数 → 返回值）" : "ICON".equals(kind) ? "种图标设置"
                : "STATE".equals(kind) ? "种状态变化" : "种文字";
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
        if (!tc.isEmpty()) sb.append("文字颜色(最近一次被设置): ").append(tc).append(LEAN ? "\n" : "   设置位置见左栏末尾\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 推荐 hook 点

    private static final java.util.regex.Pattern LAYER = java.util.regex.Pattern.compile("@栈第(\\d+)层");

    private static int layerOf(String sig) {
        java.util.regex.Matcher m = LAYER.matcher(sig);
        return m.find() ? Integer.parseInt(m.group(1)) : 99;
    }

    /** 去掉末尾的层数和标签，只留“类#方法(参数):返回类型”。 */
    private static String clean(String sig) {
        String s = sig;
        String[] tags = {Stacks.TAG_STABLE, Stacks.TAG_WIDGET, Stacks.TAG_INFRA, Stacks.TAG_OBF};
        for (String t : tags) {
            if (s.endsWith(t)) {
                s = s.substring(0, s.length() - t.length());
                break;
            }
        }
        return LAYER.matcher(s).replaceAll("").trim();
    }

    private static boolean unresolved(String s) {
        return s.contains("类未能加载") || s.contains("反射失败") || s.contains("没找到");
    }

    /**
     * 从一侧的签名里挑推荐：业务代码里没混淆的 > 通用控件方法（要按 View 过滤）> 只有混淆名。
     * 基础设施帧（消息分发、LiveData、异步包装）不推荐。层数 = 距离设置/绘制点的 App 栈层（不含系统帧），越小越直接。
     */
    static java.util.List<String> pickLines(java.util.List<String> sigs, boolean setSide) {
        String biz = null, widget = null, obf = null;
        int bizL = 99, widL = 99, obfL = 99;
        for (String s : sigs) {
            if (unresolved(s)) continue;
            int l = layerOf(s);
            if (s.endsWith(Stacks.TAG_STABLE)) {
                // 离得太远（栈第 8 层以后）的稳定帧是上层触发者（事件总线、页面初始化…），不当推荐
                if (l <= 8 && l < bizL) { bizL = l; biz = s; }
            } else if (s.endsWith(Stacks.TAG_WIDGET)) {
                if (l < widL) { widL = l; widget = s; }
            } else if (s.endsWith(Stacks.TAG_OBF)) {
                if (l < obfL) { obfL = l; obf = s; }
            }
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (biz != null) {
            String t = clean(biz) + "   [稳定，栈第" + bizL + "层]";
            // 只有设置侧才提醒：绘制侧靠近底层的混淆帧本来就是 Canvas 包装，不是“触发者”问题
            if (setSide && obfL < bizL) {
                t += "   ⚠ 更靠近这里的只有混淆帧（栈第" + obfL + "层），它在更上层：hook 后先确认只在目标控件触发";
            }
            out.add(t);
        }
        if (widget != null) {
            out.add((biz != null ? "备选(通用控件方法)  " : "") + clean(widget)
                    + "   [通用控件方法，栈第" + widL + "层：hook 后要按 View id/类过滤，否则影响所有同类控件]");
        }
        if (biz == null && widget == null && obf != null) {
            out.add(clean(obf) + "   （只有混淆名，栈第" + obfL + "层，App 更新后要重新找）");
        }
        return out;
    }

    private static java.util.List<String> sigList(JSONObject stack) {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        JSONArray g = stack == null ? null : stack.optJSONArray("sigs");
        if (g != null) for (int i = 0; i < g.length(); i++) l.add(g.optString(i));
        return l;
    }

    /** 设置侧、绘制/调用侧各给推荐（图标只有设置侧）。 */
    static void appendRecommend(StringBuilder sb, JSONObject r) {
        String kind = r.optString("kind");
        JSONObject ss = r.optJSONObject("setStack");
        if (ss != null) {
            for (String line : pickLines(sigList(ss), true)) sb.append("推荐[设置侧] ").append(line).append('\n');
        }
        // 系统方法永远不会被混淆，当兜底：但每个同类控件都会进来，必须按 View 类 / id 过滤
        String view = r.optString("view");
        String vid = r.optString("viewId");
        String flt = "按 View 类 " + simple(view) + (vid.isEmpty() ? "" : "、id " + vid) + " 过滤";
        if ("TEXTVIEW".equals(kind) || "TEXTSET".equals(kind)) {
            sb.append("备选[设置侧·系统方法，不会被混淆] android.widget.TextView#setText(java.lang.CharSequence,")
                    .append("android.widget.TextView$BufferType,boolean,int)   ").append(flt).append('\n');
        } else if ("STATE".equals(kind)) {
            sb.append("备选[系统方法，不会被混淆] android.view.View#setAlpha(float) / #setVisibility(int) / #setSelected(boolean)   ")
                    .append(flt).append("（谁改的看设置栈）\n");
        } else if ("ICON".equals(kind)) {
            if (r.optString("lastText").startsWith("setContentDescription")) {
                sb.append("备选[设置侧·系统方法，不会被混淆] android.view.View#setContentDescription(java.lang.CharSequence)   ")
                        .append(flt).append('\n');
            } else {
                sb.append("备选[设置侧·系统方法，不会被混淆] android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)")
                        .append("   ").append(flt).append("（资源名要另外 hook 子类的 setImageResource(int)）\n");
            }
        }
        JSONObject ds = r.optJSONObject("drawStack");
        if (ds != null && !"ICON".equals(kind)) {
            String label = "TRACE".equals(kind) ? "调用侧" : "绘制侧";
            for (String line : pickLines(sigList(ds), false)) sb.append("推荐[").append(label).append("] ").append(line).append('\n');
        }
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

    /** 导出时的去重表：同一份栈 / 同一份签名在整份导出里只完整写一次，后面的记录写“同 #N”。 */
    static final class Dedup {
        final java.util.HashMap<String, Integer> frames = new java.util.HashMap<>();
        final java.util.HashMap<String, Integer> sigs = new java.util.HashMap<>();
    }

    /** 一栏：设置栈或绘制栈。stack 为 null 时显示 note。 */
    static String column(String title, JSONObject stack, String note) {
        return column(title, stack, note, null, 0);
    }

    static String column(String title, JSONObject stack, String note, Dedup dd, int recId) {
        StringBuilder sb = new StringBuilder();
        if (stack == null) {
            sb.append("【").append(title).append("】\n").append(note == null ? "" : note).append('\n');
            return sb.toString();
        }
        sb.append("【").append(title).append("】线程 ").append(stack.optString("thread")).append('\n');
        JSONArray f = stack.optJSONArray("frames");
        StringBuilder fk = new StringBuilder();
        if (f != null) for (int i = 0; i < f.length(); i++) fk.append(f.optString(i)).append('\n');
        fk.append(stack.optInt("more"));
        Integer same = dd == null ? null : dd.frames.get(fk.toString());
        if (same != null && f != null && f.length() > 0) {
            sb.append("  （调用栈与 #").append(same).append(" 完全相同，不重复列出）\n");
        } else {
            if (dd != null && f != null && f.length() > 0) dd.frames.put(fk.toString(), recId);
            if (f != null) for (int i = 0; i < f.length(); i++) sb.append("  at ").append(f.optString(i)).append('\n');
            int more = stack.optInt("more");
            if (more > 0) sb.append("  … 另有 ").append(more).append(" 帧\n");
        }
        if (stack.optBoolean("xmlOnly")) {
            sb.append("\nXML 布局里写死的设置：栈里只有控件构造方法和布局加载，没有业务代码，不用于 hook。\n")
                    .append("要改它：hook 控件类的 onDraw / setText / setImageDrawable，再按 View 类 + id 过滤。\n");
            return sb.toString();
        }
        JSONArray g = stack.optJSONArray("sigs");
        sb.append("\n可 hook 的方法签名：\n");
        if (g == null || g.length() == 0) {
            sb.append("  (栈顶没有 App 帧，或正在解析)\n");
        } else {
            StringBuilder sk = new StringBuilder();
            for (int i = 0; i < g.length(); i++) sk.append(g.optString(i)).append('\n');
            Integer ss = dd == null ? null : dd.sigs.get(sk.toString());
            if (ss != null) {
                sb.append("  （签名与 #").append(ss).append(" 完全相同，不重复列出）\n");
            } else {
                if (dd != null) dd.sigs.put(sk.toString(), recId);
                for (int i = 0; i < g.length(); i++) sb.append("  ").append(g.optString(i)).append('\n');
            }
        }
        return sb.toString();
    }

    static String setColumn(Snap.Item it) {
        return setColumn(it, null);
    }

    static String setColumn(Snap.Item it, Dedup dd) {
        JSONObject r = it.o;
        String kind = r.optString("kind");
        int id = r.optInt("id");
        String title = "ICON".equals(kind) ? "设置栈 setImage*/setContentDescription"
                : "STATE".equals(kind) ? "设置栈（谁改了 alpha/visibility/selected）" : "设置栈 setText";
        String s = column(title, r.optJSONObject("setStack"), r.optString("setNote"), dd, id);
        JSONObject cs = r.optJSONObject("colorStack");
        if (cs != null && !LEAN) {
            s += "\n" + column("文字颜色设置栈 setTextColor（颜色 " + r.optString("textColor") + "）", cs, "", dd, id);
        } else if (!LEAN && !r.optString("textColor").isEmpty()) {
            s += "\n【文字颜色】" + r.optString("textColor") + "（设置栈被限流，没抓到）\n";
        }
        return s;
    }

    static String drawColumn(Snap.Item it) {
        return drawColumn(it, null);
    }

    static String drawColumn(Snap.Item it, Dedup dd) {
        JSONObject r = it.o;
        String title = "TRACE".equals(r.optString("kind")) ? "调用栈（谁调用了它）" : "绘制栈 draw";
        return column(title, r.optJSONObject("drawStack"), r.optString("drawNote"), dd, r.optInt("id"));
    }

    static String full(Snap.Item it) {
        return header(it) + "\n" + setColumn(it) + "\n" + drawColumn(it);
    }

    /** 导出整份文件用：重复的栈和签名只写一次。 */
    static String full(Snap.Item it, Dedup dd) {
        if (!LEAN) return header(it) + "\n" + setColumn(it, dd) + "\n" + drawColumn(it, dd);
        // 精简：文字 / 图标 / 状态变化都有“设置栈”，绘制栈是 onDraw 的噪声，不写；自绘、Compose、方法追踪没有设置栈，绘制栈照写
        String k = it.o.optString("kind");
        boolean skipDraw = "TEXTVIEW".equals(k) || "ICON".equals(k) || "STATE".equals(k) || "TEXTSET".equals(k);
        return header(it) + "\n" + setColumn(it, dd) + (skipDraw ? "" : "\n" + drawColumn(it, dd));
    }

    // ------------------------------------------------------------ hook 清单（给接入模块用）

    private static void appendHooks(StringBuilder sb, String label, java.util.List<String> sigs) {
        java.util.ArrayList<String> ok = new java.util.ArrayList<>();
        for (String s : sigs) {
            // 解析失败的、基础设施帧（消息分发 / LiveData / 异步包装）、通用控件方法（另有兜底行）都不列
            if (unresolved(s) || s.endsWith(Stacks.TAG_INFRA) || s.endsWith(Stacks.TAG_WIDGET)) continue;
            ok.add(s);
        }
        java.util.Collections.sort(ok, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(layerOf(a), layerOf(b));
            }
        });
        java.util.ArrayList<String> pick = new java.util.ArrayList<>();
        boolean hasStable = false;
        for (int i = 0; i < ok.size() && i < 3; i++) {
            pick.add(ok.get(i));
            if (ok.get(i).endsWith(Stacks.TAG_STABLE)) hasStable = true;
        }
        if (!hasStable) { // 最近的稳定帧（类和方法都没混淆，换版本也在）再多给一个
            for (int i = 3; i < ok.size(); i++) {
                if (ok.get(i).endsWith(Stacks.TAG_STABLE) && layerOf(ok.get(i)) <= 12) {
                    pick.add(ok.get(i));
                    break;
                }
            }
        }
        if (pick.isEmpty()) return;
        sb.append("  ").append(label).append("（近→远）:\n");
        for (String s : pick) {
            sb.append("    ").append(clean(s)).append("   [").append(s.endsWith(Stacks.TAG_STABLE) ? "稳定" : "混淆")
                    .append("，栈").append(layerOf(s)).append("]\n");
        }
    }

    /** hook 清单里的一条：这个控件是什么 + 可以 hook 哪里。没有任何可用 hook 返回 null。 */
    static String hookEntry(Snap.Item it) {
        JSONObject r = it.o;
        String kind = r.optString("kind");
        StringBuilder body = new StringBuilder();
        JSONObject ss = r.optJSONObject("setStack");
        appendHooks(body, "设置侧", sigList(ss));
        boolean drawSide = "CANVAS".equals(kind) || "COMPOSE".equals(kind) || "TRACE".equals(kind) || ss == null;
        if (drawSide) appendHooks(body, "TRACE".equals(kind) ? "调用侧" : "绘制侧", sigList(r.optJSONObject("drawStack")));
        StringBuilder rec = new StringBuilder();
        appendRecommend(rec, r);
        for (String line : rec.toString().split("\n")) { // 系统方法兜底（不会被混淆，但要按类 / id 过滤）
            if (line.startsWith("备选[")) body.append("  ").append(line).append('\n');
        }
        if (body.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(r.optInt("id")).append(" [").append(kindLabel(kind)).append("] ")
                .append(samplesLine(r, 14, 6)).append("  ×").append(r.optInt("drawCount")).append('\n');
        String id = r.optString("viewId");
        sb.append("  控件: ").append(simple(r.optString("view"))).append(id.isEmpty() ? "" : "  id=" + id);
        JSONArray ids = r.optJSONArray("ids");
        if (ids != null && ids.length() > 1) sb.append("（同一段代码设置了 ").append(ids.length()).append(" 个控件）");
        String tc = r.optString("textColor");
        if (!tc.isEmpty()) sb.append("  颜色 ").append(tc);
        sb.append('\n');
        if (r.optJSONArray("thumbs") != null) sb.append("  图标图片: 见记录页缩略图 / 导出HTML\n");
        sb.append(body);
        return sb.toString();
    }

    /** 精简导出：不写绘制栈和文字颜色设置栈（颜色值本身还在）。由记录页的复选框控制。 */
    static volatile boolean LEAN = true;

    /** 只要可 hook 的签名（设置侧、绘制侧各一段） */
    static String sigsOnly(Snap.Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(it.o.optString("view")).append("  文字「").append(oneLine(it.o.optString("lastText"))).append("」\n");
        appendRecommend(sb, it.o);
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
        for (int pass = 0; pass < 4; pass++) { // 业务稳定 → 通用控件 → 混淆 → 基础设施
            for (int i = 0; i < g.length(); i++) {
                String sig = g.optString(i);
                int rank = sig.endsWith(Stacks.TAG_STABLE) ? 0 : sig.endsWith(Stacks.TAG_WIDGET) ? 1
                        : sig.endsWith(Stacks.TAG_INFRA) ? 3 : 2;
                if (rank == pass) sb.append("  ").append(sig).append('\n');
            }
        }
    }
}
