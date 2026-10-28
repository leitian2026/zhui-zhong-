package com.textrec.lsp;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import com.textrec.lsp.ConfigCheck.Entry;
import com.textrec.lsp.ConfigCheck.Issue;

/** 配置之间的关系：父子开关、只对方法追踪有意义的项、排除名单、任务清单配套……（纯 Java） */
final class CheckCross {
    private CheckCross() {}


    // ------------------------------------------------------------ 配置之间的关系

    static final class Ctx {
        final LinkedHashMap<String, Entry> explicit = new LinkedHashMap<>();
        final HashMap<String, String> preset = new HashMap<>();
        String presetName;
        int presetLine;
        boolean hasTrace, hasTasks;

        Ctx(List<Entry> es, List<String> taskRaws) {
            for (Entry e : es) explicit.put(e.key, e);
            Entry pe = explicit.get("preset");
            if (pe != null && pe.value != null) {
                presetName = pe.value.trim();
                presetLine = pe.line;
                for (String[] kv : Presets.pairs(presetName)) preset.put(kv[0], kv[1]);
            }
            String tm = explicitVal("traceMethods");
            hasTrace = tm != null && !traceItems(tm).isEmpty();
            hasTasks = !taskRaws.isEmpty() || ConfigCheck.opt().tasksElsewhere;
        }

        String explicitVal(String k) {
            Entry e = explicit.get(k);
            return e == null ? null : (e.value == null ? "" : e.value.trim());
        }

        String get(String k) {
            String v = explicitVal(k);
            if (v != null) return v;
            v = preset.get(k);
            if (v != null) return v.trim();
            ConfigSchema.F f = ConfigSchema.get(k);
            return f == null ? "" : f.def;
        }

        boolean on(String k) {
            return Relations.isOn(ConfigSchema.get(k), get(k));
        }

        /** 显式写了 true，或预设把它设成了 true（默认值不算）。 */
        boolean setTrue(String k) {
            String ev = explicitVal(k);
            if (ev != null) return ev.equalsIgnoreCase("true");
            String pv = preset.get(k);
            return pv != null && pv.trim().equalsIgnoreCase("true");
        }

        int line(String k) {
            Entry e = explicit.get(k);
            return e == null ? presetLine : e.line;
        }

        String where(String k) {
            Entry e = explicit.get(k);
            if (e != null) return (e.line > 0 ? "第 " + e.line + " 行" : "你") + "写的是 " + CheckText.q(CheckText.clip(e.value == null ? "" : e.value.trim(), 20));
            String pv = preset.get(k);
            if (pv != null) return "预设 " + CheckText.q(presetName) + " 把它设成了 " + CheckText.q(pv.trim());
            ConfigSchema.F f = ConfigSchema.get(k);
            return "没写，用默认值 " + CheckText.q(f == null ? "" : f.def);
        }
    }

    /** 清单里真正的项：去掉空行和 // 注释行。 */
    static List<String> traceItems(String v) {
        ArrayList<String> out = new ArrayList<>();
        if (v == null) return out;
        for (String s : v.split("\\r?\\n|\\r")) {
            String t = s.trim();
            if (!t.isEmpty() && !t.startsWith("//")) out.add(t);
        }
        return out;
    }

    /** 每一项的「类#方法」目标（去掉 @选项 和括号）。 */
    static List<String> traceTargets(String v) {
        ArrayList<String> out = new ArrayList<>();
        for (String t : traceItems(v)) {
            int at = CheckTrace.optStart(t);
            String target = (at >= 0 ? t.substring(0, at) : t).trim();
            int p = target.indexOf('(');
            if (p > 0) target = target.substring(0, p);
            out.add(target.trim());
        }
        return out;
    }

    /** 这个值是不是「用户想要它生效」的值：开关写了 true；整数写了和默认不同的数；文字非空且和默认不同。 */
    private static String names(ConfigSchema.F f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.terms.length; i++) sb.append(i > 0 ? (f.any ? " / " : " + ") : "").append(CheckText.q(f.terms[i]));
        return sb.toString();
    }

    static boolean meaningful(ConfigSchema.F f, String v) {
        if (f.type.equals("bool")) return v.equalsIgnoreCase("true");
        if (f.type.equals("string")) return !traceItems(v).isEmpty() && !v.equals(f.def);
        try {
            return Long.parseLong(v) != Long.parseLong(f.def);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    static void crossChecks(List<Issue> out, List<Entry> es, List<String> taskRaws) {
        Ctx cx = new Ctx(es, taskRaws);

        // ---- 1. 前提不满足：子项写了，但它依赖的开关没开（关系递归，见 Relations）
        for (Entry e : cx.explicit.values()) {
            ConfigSchema.F f = ConfigSchema.get(e.key);
            if (f == null || f.terms.length == 0) continue;
            String v = e.value == null ? "" : e.value.trim();
            if (!meaningful(f, v)) continue;
            if (!Relations.dead(f, cx::on)) continue;
            int i = Relations.firstUnmet(f, cx::on);
            String term = f.terms[i];
            String cause = f.inverse[i] ? term : Relations.rootCause(term, cx::on);
            String child = CheckText.q(e.key + (v.length() > 24 || v.contains("\n") ? "" : "=" + v));
            ConfigSchema.F cf = ConfigSchema.get(cause);
            if (e.key.equals("keywords")) {
                out.add(new Issue(e.line, true, CheckText.q("keywords") + " 写了，但 " + CheckText.q("keywordMode") + " 不是 true（" + cx.where("keywordMode")
                        + "），关键字根本不会被用，目标 App 会记录全部文字（很卡）。要按关键字记，加一行 " + CheckText.q("keywordMode=true") + "；要记全部文字就删掉 keywords"));
            } else if (f.inverse[i]) {
                out.add(new Issue(e.line, false, child + " 不起作用：它只在 " + CheckText.q(term + "=false") + " 时才有效（" + cx.where(term) + "，分文件存开着时各类记录用各自的上限）。要用它就先写 " + CheckText.q(term + "=false") + "，否则删掉这一行"));
            } else {
                String need = f.terms.length > 1 ? (f.any ? "它需要 " + names(f) + " 里至少一个开着" : "它需要 " + names(f) + " 同时开着") : "它是 " + CheckText.q(term) + " 的子项";
                String why = cause.equals(term)
                        ? CheckText.q(term) + " 现在是关着的（" + cx.where(term) + "）"
                        : CheckText.q(term) + " 自己也不起作用，因为它依赖的 " + CheckText.q(cause) + " 是关着的（" + cx.where(cause) + "）";
                String fix = cf != null && cf.type.equals("bool") ? "先加 " + CheckText.q(cause + "=true") : "先把 " + CheckText.q(cause) + " 设成大于 0 的数";
                out.add(new Issue(e.line, false, child + " 不起作用：" + need + "，而" + why + "。要用它，" + fix + "；不想用就删掉这一行"));
            }
        }

        // ---- 2. 只对方法追踪有意义的项，却没写方法追踪
        if (!cx.hasTrace) {
            for (Entry e : cx.explicit.values()) {
                ConfigSchema.F f = ConfigSchema.get(e.key);
                if (f == null || !f.requires.equals("traceMethods")) continue;
                String v = e.value == null ? "" : e.value.trim();
                if (!meaningful(f, v)) continue;
                if (e.key.equals("windowMs") && cx.on("windowAll")) continue; // 窗口也管文字等记录
                out.add(new Issue(e.line, false, CheckText.q(e.key + (v.length() > 24 || v.contains("\n") ? "" : "=" + v)) + " 只对方法追踪有意义，但没有写 " + CheckText.q("traceMethods") + "（或写的全是注释），所以不会起任何作用。要么补上方法追踪，要么删掉这一行"));
            }
        }

        // ---- 3. 方法追踪独占：其他记录开关会在目标 App 里被自动关掉
        if (cx.hasTrace && !"false".equalsIgnoreCase(cx.get("traceExclusive"))) {
            String[] others = {"traceSet", "traceDraw", "traceColor", "traceIcons", "autoParams", "traceSetOnly", "traceState"};
            StringBuilder on = new StringBuilder();
            int ln = 0;
            for (String o : others) {
                if (cx.setTrue(o)) {
                    on.append(on.length() > 0 ? "、" : "").append(o);
                    if (ln == 0) ln = cx.line(o);
                }
            }
            if (on.length() > 0) {
                out.add(new Issue(ln, false, "写了 " + CheckText.q("traceMethods") + "，而 " + CheckText.q("traceExclusive") + " 默认是 true（方法追踪独占）：" + on + " 这些开关即使是 true，在目标 App 里也会被自动当成关闭，记录里只会有方法追踪。"
                        + "想让它们一起记，加一行 " + CheckText.q("traceExclusive=false") + "；只想追踪方法就把这些开关删掉或写 false"));
            }
        }

        // ---- 3b. 当前模式下根本不会记文字 / 图标 / 状态：只对这些记录生效的过滤没有用
        // （代码里：关键字过滤只在文字 / 图标 / 状态记录路径上判断，控件过滤也只管这几类；方法追踪不看它们）
        {
            String[] contentSwitches = {"traceSet", "traceDraw", "traceColor", "traceIcons", "autoParams", "traceSetOnly", "traceState"};
            boolean exclusiveActive = cx.hasTrace && !"false".equalsIgnoreCase(cx.get("traceExclusive"));
            boolean anyContent = false;
            for (String o : contentSwitches) if (cx.on(o)) anyContent = true;
            if (exclusiveActive || !anyContent) {
                String why = exclusiveActive
                        ? "写了 " + CheckText.q("traceMethods") + " 且 " + CheckText.q("traceExclusive") + " 没关（默认 true），目标 App 里文字 / 图标 / 状态记录都是关的，现在只会记方法追踪"
                        : "文字 / 图标 / 颜色 / 状态这些记录开关都关了，现在只会记方法追踪";
                String fix = "要按这个条件找文字或图标：" + (exclusiveActive ? "去掉 traceMethods，或写 " + CheckText.q("traceExclusive=false") + "，" : "") + "并打开 " + CheckText.q("traceSet") + " / " + CheckText.q("traceDraw") + " 等记录开关";
                Entry kwE = cx.explicit.get("keywords");
                boolean kwOn = cx.on("keywordMode") && (cx.explicitVal("keywordMode") != null || kwE != null);
                if (kwOn) {
                    int ln = cx.explicit.containsKey("keywordMode") ? cx.line("keywordMode") : kwE.line;
                    out.add(new Issue(ln, false, CheckText.q("keywordMode") + " / " + CheckText.q("keywords") + " 只对文字 / 图标 / 状态记录生效，方法追踪不受它限制，所以现在写了也不起作用。" + why + "。" + fix));
                }
                Entry vf = cx.explicit.get("viewFilter");
                if (vf != null && !traceItems(vf.value).isEmpty()) {
                    out.add(new Issue(vf.line, false, CheckText.q("viewFilter") + " 只对文字 / 图标 / 状态 / 绘制记录生效，方法追踪不看它，所以现在写了也不起作用。" + why + "。" + fix));
                }
            }
        }

        // ---- 4. keywordMode / keywords / regex
        if (cx.on("keywordMode") && traceItems(cx.get("keywords")).isEmpty()) {
            int ln = cx.line("keywordMode");
            out.add(new Issue(ln, false, CheckText.q("keywordMode=true") + " 但没有写 " + CheckText.q("keywords") + "（或为空），不会命中任何文字，什么都记不到。补上关键字，或去掉 keywordMode"));
        }
        Entry kw = cx.explicit.get("keywords");
        if (kw != null && kw.value != null) {
            boolean rx = cx.on("regex");
            if (rx) {
                for (String k : kw.value.split("[\\n\\r]+")) {
                    if (k.trim().isEmpty()) continue;
                    try {
                        Pattern.compile(k);
                    } catch (Throwable t) {
                        out.add(new Issue(kw.line, true, CheckText.q("keywords") + " 里的正则 " + CheckText.q(CheckText.clip(k, 30)) + " 无效：" + CheckText.clip(String.valueOf(t.getMessage()).split("\\n")[0], 60) + "。" + (kw.block ? "块格式里正则写成平时的样子（" + CheckText.q("\\d+") + "），不用双反斜杠" : "Properties 里每个正则反斜杠要写两个：" + CheckText.q("\\\\d+"))));
                    }
                    if (k.indexOf(',') >= 0) {
                        out.add(new Issue(kw.line, false, CheckText.q("regex=true") + " 时 keywords 只按换行分隔，" + CheckText.q(CheckText.clip(k, 30)) + " 里的逗号是正则的一部分（匹配字面逗号），不是分隔符。要写多个关键字请分成多行"));
                    }
                }
            } else {
                for (String k : kw.value.split("[,\\n\\r]+")) {
                    String t = k.trim();
                    if (t.matches(".*(\\\\[dwsDWS]|\\.\\*|\\.\\+|\\[[^\\]]+\\]|\\^|\\$).*") && t.length() > 1) {
                        out.add(new Issue(kw.line, false, CheckText.q("keywords") + " 的 " + CheckText.q(CheckText.clip(t, 30)) + " 看起来是正则，但 " + CheckText.q("regex") + " 不是 true，它会被当成普通文字去匹配，大概率什么都匹配不上。要用正则就加 " + CheckText.q("regex=true") + "（并且每行一个）"));
                        break;
                    }
                }
            }
        }

        // ---- 5. 线程设置互相矛盾
        String tf = cx.explicitVal("threadFilter");
        if (tf != null && !tf.isEmpty()) {
            int tm = 0;
            try {
                tm = Integer.parseInt(cx.get("threadMode"));
            } catch (NumberFormatException ignored) {
            }
            boolean hasMain = false;
            for (String w : tf.split("[,\\n\\r]+")) if (w.trim().toLowerCase(Locale.ROOT).contains("main")) hasMain = true;
            if (tm == 1 && !hasMain) {
                out.add(new Issue(cx.line("threadFilter"), false, CheckText.q("threadMode=1") + "（只记主线程）和 " + CheckText.q("threadFilter") + " 里没有 main 的线程名矛盾：主线程的名字是 main，其他线程名都会被 threadMode 先挡掉，什么也记不到"));
            } else if (tm == 2 && hasMain) {
                out.add(new Issue(cx.line("threadFilter"), false, CheckText.q("threadMode=2") + "（只记后台线程）和 " + CheckText.q("threadFilter") + " 里的 main 矛盾：主线程已经被 threadMode 挡掉了"));
            }
        }

        // ---- 6. 排除名单把要追踪的方法排除了
        excludeVsTrace(out, cx);

        // ---- 7. 任务清单和配置配套
        if (!cx.hasTasks) {
            Entry tm = cx.explicit.get("traceMethods");
            if (tm != null && tm.value != null) {
                for (String it : traceItems(tm.value)) {
                    int at = CheckTrace.optStart(it);
                    if (at >= 0 && it.substring(at).matches("(?s).*(^|[\\s,@])seq(=\\d+)?(\\s|,|$).*")) {
                        out.add(new Issue(tm.line, false, "用了 " + CheckText.q("@seq") + "，但没有任务清单。序列事件只在任务里记录（每个任务阶段一份），没有任务就记不到。去写任务清单（做 1 件事也要写）"));
                        break;
                    }
                }
            }
            if (cx.explicitVal("recOutside") != null && cx.explicitVal("recOutside").equalsIgnoreCase("true"))
                out.add(new Issue(cx.line("recOutside"), false, CheckText.q("recOutside=true") + " 是「任务外也记录」，没有任务清单就没有「任务内 / 任务外」之分，写了不起作用"));
            if (cx.on("viewDump") && (cx.explicitVal("viewDump") != null || cx.preset.containsKey("viewDump")))
                out.add(new Issue(cx.line("viewDump"), false, CheckText.q("viewDump=true") + " 在「任务开始 / 结束」时各抓一份视图树，没有任务清单就只有在记录页手动点「标记」时才会抓。要抓就写任务清单"));
        }

        // ---- 8. 其他容易写错的单项
        String en = cx.explicitVal("enabled");
        if (en != null && en.equalsIgnoreCase("false"))
            out.add(new Issue(cx.line("enabled"), false, CheckText.q("enabled=false") + " 会让目标 App 不记录任何东西。不是故意暂停就删掉这一行"));
        if (cx.explicitVal("epoch") != null && !cx.explicitVal("epoch").isEmpty())
            out.add(new Issue(cx.line("epoch"), false, CheckText.q("epoch") + " 是「清空旧记录」的内部标记，值和上次不同就会清空目标 App 里已有的记录。一般不要手写，点「应用并重新记录」时程序会自己更新它"));
        String vk = cx.explicitVal("viewDumpKey");
        if (vk != null && vk.isEmpty())
            out.add(new Issue(cx.line("viewDumpKey"), false, CheckText.q("viewDumpKey") + " 是空的，会回退成默认的两个类名（抖音视频文案）。要抓别的区域就填那里的控件类名 / id 里的词"));

        // ---- 9. watch 的方法必须在 traceMethods 里
        Entry wt = cx.explicit.get("watch"), tmE = cx.explicit.get("traceMethods");
        if (wt != null && wt.value != null && !wt.glued) {
            String tmv = tmE == null || tmE.value == null ? "" : tmE.value;
            CheckTrace.watchVsTrace(out, wt.value, tmv, wt.line);
        }
    }

    static void excludeVsTrace(List<Issue> out, Ctx cx) {
        Entry ex = cx.explicit.get("excludeList");
        if (ex == null || ex.value == null || !cx.hasTrace) return;
        List<String> targets = traceTargets(cx.explicitVal("traceMethods"));
        int reported = 0;
        for (String raw : ex.value.split("[,\\n\\r]+")) {
            String item = raw.trim();
            if (item.isEmpty() || item.startsWith("//")) continue;
            for (String tg : targets) {
                int h = tg.indexOf('#');
                if (h <= 0) continue;
                String c = tg.substring(0, h), m = tg.substring(h + 1);
                boolean hit;
                int ih = item.indexOf('#');
                if (ih >= 0) {
                    String ic = item.substring(0, ih), im = item.substring(ih + 1);
                    hit = (ic.isEmpty() || ic.equals(c)) && (im.equals("*") || im.equals(m));
                } else {
                    hit = c.equals(item) || c.startsWith(item);
                }
                if (hit && reported < 5) {
                    reported++;
                    out.add(new Issue(ex.line, false, "traceMethods 里的 " + CheckText.q(tg) + " 被 excludeList 里的 " + CheckText.q(item) + " 排除了，这个钩点永远记不到东西。要追它就把这项从 excludeList 里删掉（或改窄）"));
                }
            }
        }
    }
}
