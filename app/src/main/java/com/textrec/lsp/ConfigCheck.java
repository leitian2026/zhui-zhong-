package com.textrec.lsp;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 配置校验（1.30.0，纯 Java，不依赖 Android）：粘贴配置 / 任务清单、方法追踪清单写漏、写错、写得不规范时，
 * 给出准确的错误原因（第几行、写了什么、哪里不对、应该怎么写），而不是静默忽略。
 *
 * 为什么需要：RecConfig 读配置时，认不出的 key 直接忽略、认不出的数字用默认值、认不出的布尔值当 false；
 * 拆分粘贴文本时，不是「key=value」格式的行一律当任务清单。所以漏一个字母、漏一个等号、多一个空格都不会报错，只是悄悄不生效。
 * 这里把这些「悄悄不生效」的情况一条条列出来。error = 一定不会按你想的生效；warn = 能生效但很可能不是你想要的。
 */
final class ConfigCheck {
    private ConfigCheck() {}

    static final class Issue {
        final int line; // 粘贴文本里的行号（从 1 数）；0 = 不针对某一行
        final boolean error;
        final String msg;

        Issue(int line, boolean error, String msg) {
            this.line = line;
            this.error = error;
            this.msg = msg;
        }
    }

    // ---- 以下三张表由 RecConfig.fill() 生成：新增配置项时记得同步（ConfigCheckSync 检查见 README）
    static final String[] BOOL_KEYS = {
            "enabled", "keywordMode", "regex", "traceSet", "traceDraw", "traceColor",
            "traceIcons", "autoParams", "traceSetOnly", "traceState", "traceOnly", "windowAll",
            "excludeInfra", "valueClock", "traceDump", "viewDump", "viewDumpSet", "viewDumpAuto", "viewTap",
            "annotFrames", "treeOn", "watchAuto", "annotOwners", "annotSweep", "tlOn",
            "tlOps", "tlSamples", "recOutside", "traceExclusive", "traceCompose", "composeObj",
            "composeDump", "tlTouch", "tlKey", "tlPage", "tlViewTree", "smpText",
            "smpState", "smpIcon", "smpTrace", "chainOn", "chainAsync", "chainEffects",
            "chainDown", "setText", "setClear", "drawTextView", "drawCanvas", "drawCompose",
            "colorPaint", "colorSet", "iconImage", "iconDesc", "stateAlpha", "stateVisible",
            "stateSelected", "tlClass", "chainCoroutine", "splitFiles"};

    /** {key, 最小, 最大, 默认}；默认 -1 = 默认值不是固定数字 */
    static final String[][] INT_KEYS = {
            {"maxSetOnly", "10", "5000", "500"}, {"windowMs", "0", "600000", "0"}, {"threadMode", "0", "2", "0"},
            {"traceDumpEvery", "1", "1000", "25"}, {"traceDumpLines", "10", "2000", "40"}, {"traceDumpDepth", "0", "6", "3"},
            {"viewDumpUp", "0", "12", "5"}, {"viewDumpDepth", "1", "10", "5"}, {"viewDumpLines", "50", "3000", "400"},
            {"treeDepth", "1", "8", "4"}, {"treeNodes", "20", "600", "200"}, {"treeEvery", "1", "100000", "10"},
            {"annotBudgetMs", "5", "500", "40"}, {"annotPerKey", "1", "100", "6"}, {"traceStackDepth", "1", "80", "30"},
            {"maxPerKind", "20", "20000", "500"}, {"chainEvery", "1", "100000", "20"}, {"chainMaxDown", "10", "1000", "120"},
            {"quietCount", "0", "10000000", "0"}, {"quietSpanMs", "100", "3600000", "3000"}, {"stackDepth", "1", "60", "12"},
            {"perKeyStacks", "1", "50", "3"}, {"sampleEvery", "10", "100000", "200"}, {"stacksPerSec", "1", "5000", "1000"},
            {"maxRecords", "10", "20000", "2000"}, {"flushSec", "1", "60", "3"}, {"composeDumpEvery", "1", "100000", "50"},
            {"composeDumpLines", "5", "300", "60"}, {"capTextView", "10", "20000", "800"}, {"capCanvas", "10", "20000", "300"},
            {"capCompose", "10", "20000", "400"}, {"capClear", "10", "20000", "300"}, {"capIcon", "10", "20000", "300"},
            {"capState", "10", "20000", "300"}, {"capTrace", "10", "20000", "600"}, {"depthSet", "0", "60", "0"},
            {"depthDraw", "0", "60", "0"}, {"depthColor", "0", "60", "0"}, {"depthIcon", "0", "60", "0"},
            {"depthState", "0", "60", "0"}};

    static final String[] STR_KEYS = {"keywords", "traceMethods", "viewDumpKey", "watch", "excludeList", "threadFilter", "pageFilter", "viewFilter", "valueFilter", "dumpPrefixes"};

    private static final Set<String> BOOLS = new HashSet<>(java.util.Arrays.asList(BOOL_KEYS));
    private static final Set<String> STRS = new HashSet<>(java.util.Arrays.asList(STR_KEYS));
    private static final Map<String, String[]> INTS = new HashMap<>();
    private static final List<String> ALL_KEYS = new ArrayList<>();

    /** 只能写在方法追踪清单里的选项。 */
    private static final String[] TRACE_OPTS = {"count", "args", "stack", "depth", "every", "tag"};
    /** 逗号 / 换行分隔的列表型配置项。 */
    private static final Set<String> LIST_KEYS = new HashSet<>(java.util.Arrays.asList(
            "keywords", "traceMethods", "watch", "excludeList", "threadFilter", "pageFilter", "viewFilter", "valueFilter", "viewDumpKey", "dumpPrefixes"));

    static {
        for (String[] r : INT_KEYS) INTS.put(r[0], r);
        ALL_KEYS.addAll(BOOLS);
        ALL_KEYS.addAll(INTS.keySet());
        ALL_KEYS.addAll(STRS);
        ALL_KEYS.add("epoch");
    }

    // ------------------------------------------------------------ 小工具

    static int dist(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** 在 cands 里找最像 w 的；差得太远返回 null。大小写不同算差 0（要提示大小写）。 */
    static String nearest(String w, Iterable<String> cands) {
        String best = null;
        int bd = Integer.MAX_VALUE;
        String wl = w.toLowerCase(Locale.ROOT);
        for (String c : cands) {
            int d = dist(wl, c.toLowerCase(Locale.ROOT));
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        int lim = w.length() >= 9 ? 3 : (w.length() >= 5 ? 2 : 1);
        return best != null && bd <= lim ? best : null;
    }

    private static String q(String s) {
        return "`" + s + "`";
    }

    private static String clip(String s, int n) {
        String t = s.replace("\n", "\\n").replace("\r", "");
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    private static final String FULLWIDTH = "＝：，；｜＠＃（）【】";

    /** 第一个全角标点的说明；没有返回 null。 */
    private static String fullwidthIn(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (FULLWIDTH.indexOf(ch) >= 0) {
                String half;
                switch (ch) {
                    case '＝': half = "="; break;
                    case '：': half = ":"; break;
                    case '，': half = ","; break;
                    case '；': half = ";"; break;
                    case '｜': half = "|"; break;
                    case '＠': half = "@"; break;
                    case '＃': half = "#"; break;
                    case '（': half = "("; break;
                    case '）': half = ")"; break;
                    case '【': half = "["; break;
                    default: half = "]"; break;
                }
                return "第 " + (i + 1) + " 个字符是全角的 " + q(String.valueOf(ch)) + "，要换成半角的 " + q(half);
            }
        }
        return null;
    }

    private static boolean endsWithOddBackslash(String s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    private static final Pattern KEY_LINE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\s*[=:].*");
    private static final Pattern KEY_ONLY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*[=:](.*)$");

    private static final class Entry {
        final int line;
        final String key;
        final String raw;      // 物理行（含续行）原文
        String value;          // Properties 解码后的值
        boolean glued;         // 续行把两项直接粘在了一起

        Entry(int line, String key, String raw) {
            this.line = line;
            this.key = key;
            this.raw = raw;
        }
    }

    // ------------------------------------------------------------ 粘贴框（配置 + 任务清单）

    /**
     * 校验粘贴框里的整段文本。拆分规则和 {@link RecConfig#splitPaste(String)} 完全一致：
     * 以「英文标识符 + = 或 :」开头的是配置行，# 开头是注释，[tasks] / [任务清单] 之后全是任务，其余都是任务清单。
     */
    static List<Issue> checkPaste(String text) {
        ArrayList<Issue> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\\r?\\n", -1);
        ArrayList<Entry> entries = new ArrayList<>();
        ArrayList<int[]> taskIdx = new ArrayList<>(); // {行号下标}
        boolean forceTasks = false;
        String lastListKey = null;
        int lastCfgLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            int no = i + 1;
            if (t.isEmpty()) continue;
            String tl = t.toLowerCase(Locale.ROOT);
            if (tl.equals("[tasks]") || t.equals("[任务清单]")) {
                forceTasks = true;
                continue;
            }
            if (t.startsWith("[") && t.endsWith("]") && t.length() <= 12 && !t.contains("|")) {
                out.add(new Issue(no, true, "方括号标记 " + q(t) + " 不认识。分隔任务清单只认 " + q("[tasks]") + " 或 " + q("[任务清单]") + "（写错了，这一行会被当成任务名）"));
                continue;
            }
            if (t.startsWith("```")) {
                out.add(new Issue(no, true, "这一行是 Markdown 代码围栏「```」，复制 AI 回复时把它也带进来了；不删掉会被当成一个叫「" + clip(t, 12) + "」的任务。只复制代码块里面的内容"));
                continue;
            }
            if (!forceTasks && KEY_LINE.matcher(t).matches() && !t.startsWith("//")) {
                StringBuilder raw = new StringBuilder(line);
                int start = no;
                boolean cont = endsWithOddBackslash(line);
                while (cont && i + 1 < lines.length) {
                    i++;
                    raw.append('\n').append(lines[i]);
                    String nt = lines[i].trim();
                    cont = endsWithOddBackslash(lines[i]);
                    if (nt.isEmpty()) {
                        out.add(new Issue(i + 1, true, "上一行（第 " + (i) + " 行）末尾的反斜杠 " + q("\\") + " 表示「下一行接着写」，但这一行是空的。要么删掉行尾的反斜杠，要么把后面的内容接上"));
                        break;
                    }
                    if (KEY_LINE.matcher(nt).matches()) {
                        out.add(new Issue(i + 1, true, "第 " + (i) + " 行末尾有反斜杠 " + q("\\") + "（续行），所以这一行 " + q(clip(nt, 30)) + " 被并进了上一个配置项的值里，而不是一个新的配置项。去掉上一行末尾多余的反斜杠"));
                    }
                }
                java.util.regex.Matcher m = KEY_ONLY.matcher(t);
                String key = m.matches() ? m.group(1) : t;
                entries.add(new Entry(start, key, raw.toString()));
                lastListKey = LIST_KEYS.contains(key) ? key : null;
                lastCfgLine = i + 1;
            } else if (t.startsWith("#") && !forceTasks) {
                // 注释
            } else {
                checkTaskBoundLine(out, no, t, lastListKey, lastCfgLine, forceTasks);
                taskIdx.add(new int[]{i});
            }
        }
        checkEntries(out, entries);
        // 任务清单
        ArrayList<int[]> tl = new ArrayList<>();
        for (int[] ti : taskIdx) {
            String t = lines[ti[0]].trim();
            if (t.startsWith("//")) continue;
            tl.add(new int[]{ti[0]});
        }
        checkTasks(out, lines, tl);
        crossChecks(out, entries, lines);
        java.util.Collections.sort(out, (a, b) -> a.line != b.line ? Integer.compare(a.line, b.line) : Boolean.compare(b.error, a.error));
        return out;
    }

    /** 不是「key=value」格式、因而被当成任务清单的行：看它是不是其实想写配置。 */
    private static void checkTaskBoundLine(List<Issue> out, int no, String t, String lastListKey, int lastCfgLine, boolean forced) {
        if (t.startsWith("//")) return;
        if (t.startsWith("/") && !t.startsWith("//")) {
            out.add(new Issue(no, true, "注释要用两个斜杠 " + q("//") + " 开头，这一行只有一个 " + q("/") + "，会被当成任务名"));
            return;
        }
        if (t.contains("＝") || t.contains("：")) {
            String f = fullwidthIn(t);
            out.add(new Issue(no, true, "这一行被当成任务清单，因为它不是「英文key=值」的格式：" + (f == null ? "" : f + "。") + "配置项的等号必须是半角 " + q("=")));
            return;
        }
        java.util.regex.Matcher fm = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)").matcher(t);
        if (fm.find() && !t.contains("|") && !t.contains("｜")) {
            String w = fm.group(1);
            String rest = t.substring(fm.end());
            String k = ALL_KEYS.contains(w) ? w : (w.length() >= 4 ? nearest(w, ALL_KEYS) : null);
            if (k != null) {
                String val = rest.trim();
                if (val.startsWith("-") || val.startsWith("=")) val = val.substring(1).trim();
                String guess = val.isEmpty() ? k + "=…" : k + "=" + val;
                String why = rest.isEmpty() ? "key 后面没有等号和值" : (rest.matches("^\\s+.*") ? "key 和值之间用空格代替了等号" : "key 后面不是 " + q("=") + " 或 " + q(":") + "，而是 " + q(clip(rest.trim(), 6)));
                out.add(new Issue(no, true, "这一行被当成任务清单，不会当配置读：" + why + (k.equals(w) ? "" : "，而且 " + q(w) + " 不是配置项名（可能少 / 多 / 错了字母）")
                        + "。你是不是想写 " + q(clip(guess, 60)) + "？配置项必须是 " + q("key=值") + "，key 紧挨着 " + q("=")));
                return;
            }
        }
        if (t.contains("=") && !t.contains("|")) {
            String first = t.substring(0, t.indexOf('='));
            out.add(new Issue(no, true, "这一行含 " + q("=") + " 但开头 " + q(clip(first, 20)) + " 不是合法的配置项名（只能是英文字母 / 数字 / 下划线，且不能以数字开头，前面不能有 - * • ` 引号 空格以外的符号），所以被当成了任务名"));
            return;
        }
        if (!t.contains("|") && !t.contains("｜") && lastListKey != null && no - lastCfgLine <= 3) {
            out.add(new Issue(no, true, "这一行紧跟在 " + q(lastListKey + "=") + " 后面，但没有写 " + q("key=") + "，所以被当成了任务名，不是 " + lastListKey + " 的第二项。"
                    + "同一个配置项的多个值要么用逗号隔开写在同一行，要么行尾加反斜杠 " + q("\\") + " 续行，要么用 " + q("\\n") + " 隔开"));
            return;
        }
        if (t.matches("^[A-Za-z_$][\\w$.]*[.$][\\w$.]*#[^|]*$")) {
            out.add(new Issue(no, true, "这一行看起来是方法追踪的 " + q("类#方法") + "，但它没有写在 " + q("traceMethods=") + " 里，会被当成任务名。写成 " + q("traceMethods=" + clip(t, 40))));
        }
    }

    private static boolean isBoolWord(String v, String w) {
        return v.equalsIgnoreCase(w);
    }

    private static void checkEntries(List<Issue> out, List<Entry> entries) {
        Map<String, Entry> seen = new LinkedHashMap<>();
        for (Entry e : entries) {
            // 解码值（Properties 的转义规则）
            try {
                Properties p = new Properties();
                p.load(new StringReader(e.raw));
                e.value = p.getProperty(e.key);
                if (e.value == null) {
                    // key 带了奇怪字符导致 Properties 解析出别的 key，退回手工取
                    java.util.Enumeration<?> names = p.propertyNames();
                    e.value = names.hasMoreElements() ? p.getProperty((String) names.nextElement()) : "";
                }
            } catch (java.io.IOException ex) {
                continue;
            } catch (IllegalArgumentException ex) {
                out.add(new Issue(e.line, true, q(e.key) + " 的值里有不完整的 " + q("\\u") + " 转义：" + q("\\u") + " 后面必须紧跟 4 位十六进制数字（如 " + q("\\u4e2d") + "）。路径 / 反斜杠要写成 " + q("\\\\")));
                continue;
            }
            boolean glued = checkGlue(out, e);
            e.glued = glued;
            Entry prev = seen.get(e.key);
            if (prev != null) {
                out.add(new Issue(e.line, false, q(e.key) + " 写了两次（第 " + prev.line + " 行和第 " + e.line + " 行），后面的覆盖前面的，只有第 " + e.line + " 行生效"));
            }
            seen.put(e.key, e);
            checkOne(out, e);
        }
    }

    /**
     * 行尾反斜杠 = 续行：下一行直接接在这一行后面，中间不补换行也不补逗号。
     * 所以列表型配置项（traceMethods / keywords…）分行写时，行尾反斜杠前面必须自己写 \n（或逗号），否则两项会粘成一项。
     */
    private static boolean checkGlue(List<Issue> out, Entry e) {
        if (!LIST_KEYS.contains(e.key)) return false;
        String[] ls = e.raw.split("\n", -1);
        boolean any = false;
        for (int i = 0; i + 1 < ls.length; i++) {
            String cur = ls[i];
            if (!endsWithOddBackslash(cur)) continue;
            String head = cur.substring(0, cur.length() - 1);
            String next = ls[i + 1].replaceAll("^\\s+", "");
            if (head.trim().isEmpty() || next.isEmpty()) continue;
            boolean sepOk = head.endsWith("\\n") || head.trim().endsWith(",") || next.startsWith(",") || next.startsWith("\\n");
            if (sepOk) continue;
            String a = head.trim(), b = next;
            out.add(new Issue(e.line + i, true, q(e.key) + " 第 " + (e.line + i) + " 行末尾的反斜杠 " + q("\\") + " 只是把下一行直接接在后面，不会换行：" + q(clip(a.substring(Math.max(0, a.length() - 16)) + b.substring(0, Math.min(b.length(), 16)), 40))
                    + " 会粘成一项，" + (e.key.equals("traceMethods") ? "多条方法追踪就会变成一条错的" : "两个词会变成一个词")
                    + "。要分成两项，在行尾反斜杠前面加 " + q("\\n") + "（写成 " + q(clip(a, 30) + "\\n\\") + "）" + (e.key.equals("traceMethods") ? "" : "，或加逗号")));
            any = true;
        }
        return any;
    }

    private static void checkOne(List<Issue> out, Entry e) {
        String key = e.key, v = e.value == null ? "" : e.value;
        int ln = e.line;
        String vt = v.trim();
        boolean isBool = BOOLS.contains(key), isInt = INTS.containsKey(key), isStr = STRS.contains(key), isEpoch = key.equals("epoch");
        if (!isBool && !isInt && !isStr && !isEpoch) {
            // 表里没有，但 RecConfig 里有这个公开字段（以后新增了配置项、忘了更新上面的表）：按字段类型检查，不当成未知
            Class<?> ft = fieldType(key);
            if (ft == boolean.class) isBool = true;
            else if (ft == int.class || ft == long.class) isInt = true;
            else if (ft == String.class) isStr = true;
        }
        if (!isBool && !isInt && !isStr && !isEpoch) {
            String k = nearest(key, ALL_KEYS);
            if (k != null && !k.equals(key)) {
                boolean caseOnly = k.equalsIgnoreCase(key);
                out.add(new Issue(ln, true, "不认识的配置项 " + q(key) + "，会被直接忽略。" + (caseOnly ? "配置项名区分大小写，应写成 " + q(k) : "你是不是想写 " + q(k) + "？（" + diffHint(key, k) + "）")));
            } else {
                out.add(new Issue(ln, true, "不认识的配置项 " + q(key) + "，会被直接忽略（没有哪个配置项和它像）。配置项名见 ai_guide.md 第 8 章"));
            }
            return;
        }
        String fw = isStr ? null : fullwidthIn(vt);
        if (fw != null) out.add(new Issue(ln, true, q(key) + " 的值 " + q(clip(vt, 20)) + " 里有全角符号：" + fw));
        if (vt.startsWith("=") || vt.startsWith(":")) {
            out.add(new Issue(ln, true, q(key) + " 的值以 " + q(vt.substring(0, 1)) + " 开头（" + q(clip(vt, 20)) + "），多写了一个等号 / 冒号：应写成 " + q(key + "=" + vt.substring(1).trim())));
            return;
        }
        if (isBool) {
            String lv = vt.toLowerCase(Locale.ROOT);
            if (lv.equals("true") || lv.equals("false")) return;
            String hint;
            if (lv.isEmpty()) hint = "值是空的，会被当成 false。要开写 " + q(key + "=true") + "，要关写 " + q(key + "=false");
            else if (lv.equals("yes") || lv.equals("on") || lv.equals("1") || lv.equals("y") || lv.equals("t") || lv.equals("开") || lv.equals("是"))
                hint = "只认英文 true / false，" + q(vt) + " 会被当成 false（关）。要开写 " + q("true");
            else if (lv.equals("no") || lv.equals("off") || lv.equals("0") || lv.equals("n") || lv.equals("f") || lv.equals("关") || lv.equals("否"))
                hint = "只认英文 true / false，" + q(vt) + " 会被当成 false。要关请写 " + q("false");
            else if (dist(lv, "true") <= 2) hint = "拼写不对，会被当成 false（关）。你是不是想写 " + q("true") + "？";
            else if (dist(lv, "false") <= 2) hint = "拼写不对，会被当成 false。你是不是想写 " + q("false") + "？";
            else hint = "不是 true 也不是 false，会被当成 false（关）";
            out.add(new Issue(ln, true, q(key) + " 是开关，值 " + q(clip(vt, 20)) + "：" + hint));
            return;
        }
        if (isInt || isEpoch) {
            if (vt.isEmpty()) {
                out.add(new Issue(ln, true, q(key) + " 的值是空的，会用默认值" + defText(key) + "。要么填整数，要么整行删掉"));
                return;
            }
            long n;
            try {
                n = Long.parseLong(vt);
            } catch (NumberFormatException ex) {
                String why;
                if (vt.matches("-?\\d+\\.\\d+")) why = "是小数，只认整数";
                else if (vt.matches("-?[\\dOo]*[Oo][\\dOo]*") && vt.matches(".*\\d.*")) why = "里有字母 " + q(vt.replaceAll("[^Oo]", "").substring(0, 1)) + "，是不是把数字 0 写成了字母 o？";
                else if (vt.matches("-?[\\dlI]*[lI][\\dlI]*") && vt.matches(".*\\d.*")) why = "里有字母 " + q(vt.replaceAll("[^lI]", "").substring(0, 1)) + "，是不是把数字 1 写成了字母？";
                else if (vt.matches("-?\\d+\\s*[A-Za-z\u4e00-\u9fa5]+")) why = "后面带了单位 " + q(vt.replaceAll("^-?\\d+\\s*", "")) + "，只能写纯数字（单位已经在配置项名里：Ms = 毫秒，Sec = 秒）";
                else if (vt.matches("-?[\\d,]+") && vt.contains(",")) why = "数字里有逗号分隔符，要写成纯数字";
                else if (vt.matches(".*\\d.*") && vt.matches(".*[Oo].*")) why = "含有字母 O / o（是不是把数字 0 写成了字母 o？）";
                else if (vt.matches(".*\\d.*") && vt.matches(".*[lI].*")) why = "含有字母 l / I（是不是把数字 1 写成了字母？）";
                else why = "不是整数";
                out.add(new Issue(ln, true, q(key) + " 要填整数，" + q(clip(vt, 20)) + " " + why + "；这个值会被忽略，改用默认值" + defText(key)));
                return;
            }
            String[] r = INTS.get(key);
            if (r != null) {
                long lo = Long.parseLong(r[1]), hi = Long.parseLong(r[2]);
                if (n < lo || n > hi) {
                    out.add(new Issue(ln, false, q(key) + " = " + n + " 超出允许范围 " + lo + "～" + hi + "，会被改成 " + (n < lo ? lo : hi)));
                }
            }
            return;
        }
        // 字符串项
        String ph = placeholderIn(v);
        if (ph != null) out.add(new Issue(ln, true, q(key) + " 的值里留着没替换的占位文字 " + q(ph) + "。不知道要填什么就整行删掉"));
        if (LIST_KEYS.contains(key) && !key.equals("traceMethods")) {
            String f2 = fullwidthIn(v);
            if (f2 != null) out.add(new Issue(ln, true, q(key) + " 是用逗号 / 换行分隔的列表，" + f2 + "（全角逗号不会被当成分隔符，整串会被当成一个词）"));
        }
        if (key.equals("traceMethods") && !e.glued) checkTraceMethods(out, v, ln, "traceMethods", false);
        if (key.equals("watch") && !e.glued) checkWatch(out, v, ln, false);
        if (key.equals("keywords")) {
            // 正则是否生效要看同一份文本里的 regex 开关，在 crossChecks 里查
        }
    }

    private static Class<?> fieldType(String key) {
        try {
            return Class.forName("com.textrec.lsp.RecConfig").getField(key).getType();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String defText(String key) {
        String[] r = INTS.get(key);
        return r == null || "-1".equals(r[3]) ? "" : " " + r[3];
    }

    private static String diffHint(String w, String k) {
        if (w.length() == k.length() - 1) {
            for (int i = 0; i < k.length(); i++) {
                String t = k.substring(0, i) + k.substring(i + 1);
                if (t.equals(w)) return "少了第 " + (i + 1) + " 个字母 " + q(String.valueOf(k.charAt(i)));
            }
        } else if (w.length() == k.length() + 1) {
            for (int i = 0; i < w.length(); i++) {
                String t = w.substring(0, i) + w.substring(i + 1);
                if (t.equals(k)) return "多了第 " + (i + 1) + " 个字母 " + q(String.valueOf(w.charAt(i)));
            }
        } else if (w.length() == k.length()) {
            for (int i = 0; i < w.length(); i++) {
                if (w.charAt(i) != k.charAt(i)) return "第 " + (i + 1) + " 个字母应是 " + q(String.valueOf(k.charAt(i))) + "，写成了 " + q(String.valueOf(w.charAt(i)));
            }
        }
        return "拼写有出入";
    }

    private static String placeholderIn(String v) {
        String[] pats = {"你的", "xxx", "XXX", "<", "待填", "TODO", "todo", "示例", "占位", "这里填", "填这里", "类名", "方法名"};
        for (String p : pats) {
            if (v.contains(p)) {
                if (p.equals("<") && (v.contains("<init>") || v.contains("<clinit>"))) continue;
                int i = v.indexOf(p);
                int e = Math.min(v.length(), i + 10);
                return v.substring(Math.max(0, i - 2), e).replace("\n", " ");
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 方法追踪清单

    private static final Pattern CLASS_OK = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");
    private static final Pattern METHOD_OK = Pattern.compile("^[A-Za-z_$<][A-Za-z0-9_$<>\\-]*$");

    private static int optStart(String t) {
        for (int i = 1; i < t.length(); i++) if (t.charAt(i) == '@' && Character.isWhitespace(t.charAt(i - 1))) return i - 1;
        return -1;
    }

    /** 校验方法追踪清单的每一条。line = 用来报告的行号（粘贴框里是配置项所在行，界面输入框里是 0）。 */
    static void checkTraceMethods(List<Issue> out, String value, int line, String what, boolean uiBox) {
        if (value == null) return;
        String[] items = value.split("\\r?\\n|\\r");
        HashMap<String, Integer> seen = new HashMap<>();
        int shown = 0;
        for (int i = 0; i < items.length; i++) {
            String t = items[i].trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            String pre = (uiBox ? "方法追踪第 " + (i + 1) + " 行" : what + " 第 " + (i + 1) + " 条") + " " + q(clip(t, 50)) + "：";
            ArrayList<String> errs = new ArrayList<>(), warns = new ArrayList<>();
            checkTraceLine(t, errs, warns);
            for (String m : errs) out.add(new Issue(line, true, pre + m));
            for (String m : warns) out.add(new Issue(line, false, pre + m));
            int at = optStart(t);
            String target = (at < 0 ? t : t.substring(0, at)).trim();
            int pp = target.indexOf('(');
            if (pp > 0) target = target.substring(0, pp).trim();
            Integer prev = seen.get(target);
            if (prev != null) out.add(new Issue(line, false, pre + "和第 " + prev + " 条是同一个钩点 " + q(target) + "，重复了（后面的选项覆盖前面的）"));
            else seen.put(target, i + 1);
        }
    }

    private static void checkTraceLine(String t, List<String> errs, List<String> warns) {
        if (t.startsWith("/") && !t.startsWith("//")) {
            errs.add("注释要用两个斜杠 " + q("//") + "，这里只有一个 " + q("/") + "，会被当成要追踪的类");
            return;
        }
        String fw = fullwidthIn(t);
        if (fw != null) errs.add(fw);
        if (t.startsWith("`") || t.endsWith("`") || t.startsWith("\"") || t.startsWith("'") || t.startsWith("- ") || t.startsWith("* ")) {
            errs.add("行首 / 行尾有多余的符号 " + q(t.substring(0, 1)) + "（引号、反引号、列表符号都不能带）");
        }
        int at = optStart(t);
        String target = (at < 0 ? t : t.substring(0, at)).trim();
        String opts = at < 0 ? "" : t.substring(at).trim();
        if (at < 0) {
            int a = t.indexOf('@');
            if (a > 0) {
                errs.add("第 " + (a + 1) + " 个字符的 " + q("@") + " 前面没有空格：它会变成方法名的一部分，选项不生效。应写成 " + q(t.substring(0, a) + " " + t.substring(a)));
                target = t.substring(0, a).trim();
            }
        }
        int p = target.indexOf('(');
        if (p >= 0) {
            if (target.indexOf(')', p) < 0) errs.add("括号没有闭合：" + q("(") + " 后面缺 " + q(")") + "（括号里的参数类型会被忽略，所有同名重载都会追，可以直接不写括号）");
            target = target.substring(0, p).trim();
        } else if (target.contains(")")) {
            errs.add("有 " + q(")") + " 却没有 " + q("("));
        }
        int h = target.indexOf('#');
        if (h < 0) {
            String hint = "";
            int ld = target.lastIndexOf('.');
            if (ld > 0 && !target.contains(" ")) hint = "；如果最后一段 " + q(target.substring(ld + 1)) + " 是方法名，应写成 " + q(target.substring(0, ld) + "#" + target.substring(ld + 1));
            errs.add("缺少 " + q("#") + "：写法是 " + q("类全名#方法名") + "（如 android.view.View#setAlpha），追踪整个类写 " + q("类全名#*") + hint);
            return;
        }
        if (target.indexOf('#', h + 1) >= 0) {
            errs.add("出现了不止一个 " + q("#") + "，一行只能写一个「类#方法」");
            return;
        }
        String cls = target.substring(0, h), m = target.substring(h + 1);
        if (!cls.equals(cls.trim()) || !m.equals(m.trim())) errs.add(q("#") + " 两边不能有空格（现在是 " + q(clip(target, 40)) + "）");
        cls = cls.trim();
        m = m.trim();
        if (cls.isEmpty()) {
            errs.add(q("#") + " 前面没有类名");
        } else {
            String bad = badClass(cls);
            if (bad != null) errs.add(bad);
            else if (cls.indexOf('.') < 0) warns.add("类名 " + q(cls) + " 没有包名；除非目标类真的在默认包，否则要写全名（包名.类名）");
        }
        if (m.isEmpty()) {
            errs.add(q("#") + " 后面没有方法名（追踪整个类写 " + q("*") + "，构造方法写 " + q("<init>") + "）");
        } else if (!m.equals("*") && !METHOD_OK.matcher(m).matches()) {
            int bi = 0;
            for (; bi < m.length(); bi++) if (!METHOD_OK.matcher(m.substring(bi, bi + 1)).matches() && "<>-".indexOf(m.charAt(bi)) < 0 && !Character.isLetterOrDigit(m.charAt(bi)) && m.charAt(bi) != '_' && m.charAt(bi) != '$') break;
            errs.add("方法名 " + q(m) + " 第 " + (Math.min(bi, m.length() - 1) + 1) + " 个字符 " + q(String.valueOf(m.charAt(Math.min(bi, m.length() - 1)))) + " 不合法（方法名只能有字母 / 数字 / _ / $，构造方法是 " + q("<init>") + "，Compose 的方法名可带 " + q("-") + "）");
        } else if (m.equalsIgnoreCase("init") && !m.equals("init")) {
            warns.add("构造方法要写 " + q("<init>") + "（带尖括号）");
        } else if (m.equals("constructor") || m.equals("new")) {
            warns.add("构造方法要写 " + q("<init>") + "，" + q(m) + " 只会去找名字叫 " + q(m) + " 的方法");
        }
        checkOpts(opts, errs, warns);
    }

    private static String badClass(String cls) {
        if (CLASS_OK.matcher(cls).matches()) {
            if (cls.endsWith(".class") || cls.endsWith(".java") || cls.endsWith(".kt")) return "类名不要带后缀 " + q(cls.substring(cls.lastIndexOf('.'))) + "（现在它会被当成包名的一层）";
            return null;
        }
        if (cls.contains("/")) return "类名里用了 " + q("/") + "，要用点 " + q(".") + " 分隔：" + q(cls.replace('/', '.'));
        if (cls.startsWith("L") && cls.endsWith(";")) return "这是 JVM 签名写法（L…;），要写成普通类全名：" + q(cls.substring(1, cls.length() - 1).replace('/', '.'));
        if (cls.contains(" ")) return "类名里有空格（第 " + (cls.indexOf(' ') + 1) + " 个字符），类名中间不能断开";
        if (cls.startsWith(".") || cls.endsWith(".") || cls.contains("..")) return "类名里的点 " + q(".") + " 位置不对（开头 / 结尾 / 连续两个点）：" + q(cls);
        for (int i = 0; i < cls.length(); i++) {
            char c = cls.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.')) return "类名第 " + (i + 1) + " 个字符 " + q(String.valueOf(c)) + " 不合法";
        }
        if (Character.isDigit(cls.charAt(0))) return "类名不能以数字开头";
        return "类名写法不合法：" + q(cls);
    }

    private static void checkOpts(String opts, List<String> errs, List<String> warns) {
        if (opts.isEmpty()) return;
        boolean count = false, args = false;
        for (String w : opts.split("[@,\\s]+")) {
            if (w.isEmpty()) continue;
            String name = w.contains("=") ? w.substring(0, w.indexOf('=')) : w;
            String val = w.contains("=") ? w.substring(w.indexOf('=') + 1) : null;
            boolean known = false;
            for (String o : TRACE_OPTS) if (o.equals(name)) known = true;
            if (!known) {
                String k = nearest(name, java.util.Arrays.asList(TRACE_OPTS));
                errs.add("未知选项 " + q("@" + name) + "，会被忽略" + (k != null ? "。你是不是想写 " + q("@" + k + (k.equals("count") || k.equals("args") ? "" : "=…")) + "？" : "。可用选项：@count @args @stack=N @every=N @tag=N / @tag=ret"));
                continue;
            }
            if (name.equals("count")) {
                count = true;
                if (val != null) errs.add(q("@count") + " 不带值，不能写 " + q("=" + val));
            } else if (name.equals("args")) {
                args = true;
                if (val != null) errs.add(q("@args") + " 不带值，不能写 " + q("=" + val));
            } else if (name.equals("tag")) {
                if (val == null) errs.add(q("@tag") + " 后面要写 " + q("=数字") + "（第几个参数，从 0 数）或 " + q("=ret") + "（返回值）");
                else if (!val.equals("ret") && !val.matches("\\d+")) errs.add(q("@tag=" + val) + " 的值只能是非负整数或 " + q("ret") + (dist(val, "ret") <= 1 ? "；是不是想写 " + q("@tag=ret") + "？" : ""));
            } else {
                if (val == null) {
                    errs.add(q("@" + name) + " 后面要写 " + q("=数字") + "，如 " + q("@" + name + "=4"));
                } else if (!val.matches("\\d+")) {
                    errs.add(q("@" + name + "=" + val) + " 的值 " + q(val) + " 不是正整数，会被忽略");
                } else {
                    long n = Long.parseLong(val);
                    long hi = name.equals("every") ? 100000 : 60;
                    if (n < 1) errs.add(q("@" + name + "=" + val) + " 必须 ≥ 1，会被忽略");
                    else if (n > hi) warns.add(q("@" + name + "=" + val) + " 超出上限 " + hi + "，会被改成 " + hi);
                }
            }
        }
        if (count && args) warns.add("同时写了 " + q("@count") + " 和 " + q("@args") + "，只有 " + q("@count") + " 生效（只计数，不记参数）");
    }

    // ------------------------------------------------------------ 观察点（watch）

    private static final Pattern WATCH_FIELD = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    /** 校验观察点路径：根 this / ret / argN，后面是 .字段 / [下标] / {键} / (LiveData) (ref) (lazy)。返回错误原因，没问题返回 null。 */
    static String badPath(String p) {
        if (p.isEmpty()) return "路径是空的";
        if (p.indexOf(' ') >= 0 || p.indexOf('\t') >= 0) return "路径里不能有空格（第 " + (p.indexOf(' ') + 1) + " 个字符）";
        String fw = fullwidthIn(p);
        if (fw != null) return fw;
        int cut = p.length();
        for (char ch : new char[]{'.', '[', '{', '('}) {
            int i = p.indexOf(ch);
            if (i >= 0) cut = Math.min(cut, i);
        }
        String root = p.substring(0, cut);
        if (!(root.equals("this") || root.equals("ret") || root.matches("arg\\d+"))) {
            String hint = "";
            if (root.startsWith("args") || root.startsWith("param")) hint = "；参数写成 arg0、arg1（从 0 数），不是 " + q(root);
            else if (root.equals("result") || root.equals("return")) hint = "；返回值写成 " + q("ret");
            else if (root.equals("self") || root.equals("thisObject")) hint = "；调用对象写成 " + q("this");
            else {
                String k = nearest(root, java.util.Arrays.asList("this", "ret", "arg0", "arg1", "arg2"));
                if (k != null) hint = "；是不是想写 " + q(k) + "？";
            }
            return "路径必须以 this、ret 或 argN 开头，你写的是 " + q(root) + hint;
        }
        int i = cut;
        while (i < p.length()) {
            char ch = p.charAt(i);
            int j;
            if (ch == '.') {
                j = i + 1;
                while (j < p.length() && ".[{(".indexOf(p.charAt(j)) < 0) j++;
                String name = p.substring(i + 1, j);
                if (name.isEmpty()) return "第 " + (i + 1) + " 个字符的 " + q(".") + " 后面没有字段名（多写了点，或点在结尾）";
                if (!WATCH_FIELD.matcher(name).matches()) return "字段名 " + q(name) + " 不合法（只能是字母 / 数字 / _ / $，不能以数字开头）";
            } else if (ch == '[') {
                j = p.indexOf(']', i);
                if (j < 0) return "第 " + (i + 1) + " 个字符的 " + q("[") + " 没有闭合的 " + q("]");
                if (!p.substring(i + 1, j).matches("\\d+")) return "方括号里要是整数下标，你写的是 " + q("[" + p.substring(i + 1, j) + "]");
                j++;
            } else if (ch == '{') {
                j = p.indexOf('}', i);
                if (j < 0) return "第 " + (i + 1) + " 个字符的 " + q("{") + " 没有闭合的 " + q("}");
                j++;
            } else if (ch == '(') {
                j = p.indexOf(')', i);
                if (j < 0) return "第 " + (i + 1) + " 个字符的 " + q("(") + " 没有闭合的 " + q(")");
                String tok = p.substring(i + 1, j);
                if (!(tok.equals("LiveData") || tok.equals("ref") || tok.equals("lazy"))) {
                    String k = nearest(tok, java.util.Arrays.asList("LiveData", "ref", "lazy"));
                    return "括号里只能是 LiveData / ref / lazy，你写的是 " + q("(" + tok + ")") + (k != null ? "；是不是想写 " + q("(" + k + ")") + "？" : "");
                }
                j++;
            } else {
                return "第 " + (i + 1) + " 个字符 " + q(String.valueOf(ch)) + " 不合法：路径里字段之间用点 " + q(".") + "，列表元素用 " + q("[0]") + "，Map 元素用 " + q("{键}");
            }
            i = j;
        }
        return null;
    }

    /** 校验 watch=：每行「类#方法:路径」。uiBox = 界面输入框里的（按行号报），否则是粘贴框里的（按条报）。 */
    static void checkWatch(List<Issue> out, String value, int line, boolean uiBox) {
        if (value == null) return;
        String[] items = value.split("\\r?\\n|\\r");
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < items.length; i++) {
            String t = items[i].trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            String pre = (uiBox ? "观察点第 " + (i + 1) + " 行 " : "watch 第 " + (i + 1) + " 条 ") + q(clip(t, 50)) + "：";
            int c = t.indexOf(':');
            if (c < 0) {
                out.add(new Issue(line, true, pre + "缺少冒号：写法是 " + q("类#方法:路径") + "，如 " + q("com.a.B#foo:arg0.mAweme.userDigg")));
                continue;
            }
            String tk = t.substring(0, c), path = t.substring(c + 1);
            if (!tk.equals(tk.trim()) || !path.equals(path.trim())) out.add(new Issue(line, true, pre + "冒号两边不能有空格"));
            tk = tk.trim();
            path = path.trim();
            ArrayList<String> errs = new ArrayList<>(), warns = new ArrayList<>();
            int h = tk.indexOf('#');
            if (h < 0) {
                errs.add("冒号前面要是 " + q("类#方法") + "（缺少 " + q("#") + "），而且这个方法必须同时写在方法追踪里，不然读不到根对象");
            } else {
                String cls = tk.substring(0, h), m = tk.substring(h + 1);
                String bad = cls.isEmpty() ? q("#") + " 前面没有类名" : badClass(cls);
                if (bad != null) errs.add(bad);
                if (m.isEmpty() || m.equals("*") || !METHOD_OK.matcher(m).matches()) errs.add("方法名 " + q(m) + " 不合法（观察点要指定到具体的方法，不能用 " + q("*") + "）");
            }
            String bp = badPath(path);
            if (bp != null) errs.add(bp);
            for (String m : errs) out.add(new Issue(line, true, pre + m));
            for (String m : warns) out.add(new Issue(line, false, pre + m));
            if (!seen.add(t)) out.add(new Issue(line, false, pre + "重复了"));
        }
    }

    /** 观察点引用的方法必须在方法追踪清单里（或整类追踪），否则永远读不到根对象。 */
    static void watchVsTrace(List<Issue> out, String w, String tm, int line) {
        if (w == null || w.trim().isEmpty()) return;
        HashSet<String> traced = new HashSet<>();
        if (tm != null) {
            for (String s : tm.split("\\r?\\n|\\r")) {
                String t = s.trim();
                if (t.isEmpty() || t.startsWith("//")) continue;
                int at = optStart(t);
                String target = (at < 0 ? t : t.substring(0, at)).trim();
                int p = target.indexOf('(');
                if (p > 0) target = target.substring(0, p).trim();
                traced.add(target);
            }
        }
        for (String s : w.split("\\r?\\n|\\r")) {
            String t = s.trim();
            int c = t.indexOf(':');
            if (t.isEmpty() || t.startsWith("//") || c <= 0) continue;
            String tk = t.substring(0, c).trim();
            int h = tk.indexOf('#');
            if (h <= 0) continue;
            if (!traced.contains(tk) && !traced.contains(tk.substring(0, h) + "#*")) {
                out.add(new Issue(line, true, "watch 里的 " + q(tk) + " 没有写在 traceMethods 里：没被追踪的方法拿不到根对象（this / 参数 / 返回值），这个观察点永远读不到值。把 " + q(tk) + " 也加进 traceMethods"));
            }
        }
    }

    // ------------------------------------------------------------ 任务清单

    private static void checkTasks(List<Issue> out, String[] lines, List<int[]> idx) {
        Set<String> names = new HashSet<>();
        boolean first = true;
        for (int[] ti : idx) {
            String raw = lines[ti[0]];
            String t = raw.trim();
            int no = ti[0] + 1;
            if (t.startsWith("```") || (t.startsWith("[") && t.endsWith("]") && t.length() <= 12 && !t.contains("|"))) continue; // 上面已经报过
            if (t.contains("｜")) {
                out.add(new Issue(no, true, "任务清单的分隔符要用半角竖线 " + q("|") + "，这一行用了全角的 " + q("｜") + "，整行会被当成一个任务名（后面的提示 / 秒数都读不到）"));
                continue;
            }
            String[] p = t.split("\\|", -1);
            String name = p[0].trim();
            boolean noWait = name.startsWith("!");
            if (noWait) {
                name = name.substring(1).trim();
                if (!first) out.add(new Issue(no, false, "名称前的 " + q("!") + " 只对清单第一件事有意义（冷启动时一打开浮窗就开始），这一行不是第一件，" + q("!") + " 不起作用"));
            }
            first = false;
            boolean base = name.startsWith("@基线");
            if (name.startsWith("@") && !base) {
                String k = name.startsWith("@baseline") || name.toLowerCase(Locale.ROOT).startsWith("@base") ? "；基线要写成中文 " + q("@基线") : (dist(name.length() > 3 ? name.substring(0, 3) : name, "@基线") <= 1 ? "；是不是想写 " + q("@基线") + "？" : "");
                out.add(new Issue(no, true, "任务名以 " + q("@") + " 开头只能是 " + q("@基线") + "（安静基线），你写的是 " + q(clip(name, 12)) + "，会被当成一个普通任务名" + k));
            }
            if (name.isEmpty()) {
                out.add(new Issue(no, true, "任务名是空的（第一段 " + q("|") + " 前面没有内容）：" + q(clip(t, 30))));
                continue;
            }
            if (name.contains("=")) out.add(new Issue(no, false, "任务名里有 " + q("=") + "（" + q(clip(name, 20)) + "）；如果它其实是配置项，请检查是不是 key 写错了"));
            if (!base && !names.add(name)) out.add(new Issue(no, false, "任务名 " + q(clip(name, 20)) + " 和前面的重复了，导出里两件事会难分清"));
            if (p.length > 4) out.add(new Issue(no, false, "任务清单每行最多 4 段（名称|提示|秒数|空），这一行有 " + p.length + " 段，第 5 段起会被忽略；提示里如果有 " + q("|") + " 请换成别的符号"));
            if (p.length > 2) {
                String s = p[2].trim();
                if (!s.isEmpty()) {
                    try {
                        int secs = Integer.parseInt(s);
                        if (secs < 0) out.add(new Issue(no, true, "第 3 段（秒数） " + q(s) + " 是负数"));
                        else if (secs > 600) out.add(new Issue(no, false, "第 3 段（秒数）是 " + secs + " 秒，超过 10 分钟，是不是多写了一个 0？"));
                        else if (base && secs == 0) out.add(new Issue(no, false, "基线秒数是 0，会改用默认 10 秒"));
                    } catch (NumberFormatException ex) {
                        String why = s.matches("\\d+\\s*[sS秒]") ? "不要带单位，只写数字" : (s.matches("-?\\d+\\.\\d+") ? "是小数，只认整数" : "不是整数");
                        out.add(new Issue(no, true, "第 3 段（秒数）" + q(clip(s, 12)) + " " + why + "，会被当成 0（不自动结束）"));
                    }
                }
            }
            if (p.length > 3) {
                String f = p[3].trim();
                int repsSeen = 0;
                for (String tk : f.split("[\\s,，]+")) {
                    if (tk.isEmpty()) continue;
                    if (tk.equals("空") || tk.equals("0") || tk.equals("可空") || tk.equals("对照")) continue;
                    if (tk.matches("^[xX×*]\\d+$")) {
                        repsSeen++;
                        int n = Integer.parseInt(tk.substring(1));
                        if (n < 1) out.add(new Issue(no, true, "第 4 段 " + q(tk) + " 的遍数必须 ≥ 1"));
                        else if (n > 10) out.add(new Issue(no, false, "第 4 段 " + q(tk) + " 超过上限 10 遍，会被改成 10"));
                        if (base) out.add(new Issue(no, false, "基线任务不能做多遍，" + q(tk) + " 会被忽略"));
                        continue;
                    }
                    String k = tk.equalsIgnoreCase("empty") || tk.equalsIgnoreCase("null") || tk.equalsIgnoreCase("none") ? "；对照任务要写中文 " + q("空") : "";
                    if (tk.matches("^[xX×*]\\D.*") || tk.matches("^\\d+[xX×*]$")) k = "；做多遍的写法是 " + q("x3") + "（x 在前，后面接遍数）";
                    out.add(new Issue(no, true, "第 4 段只能写 " + q("空") + " / " + q("可空") + " / " + q("对照") + " / " + q("0") + "（对照任务，允许 0 条记录）或 "
                            + q("x3") + "（同一件事连做 3 遍），你写的是 " + q(clip(tk, 12)) + k));
                }
                if (repsSeen > 1) out.add(new Issue(no, false, "第 4 段写了不止一个 " + q("xN") + "，只有最后一个生效"));
            }
            if (p.length == 1 && t.length() > 25) {
                out.add(new Issue(no, false, "这一行没有任何 " + q("|") + "，整行 " + q(clip(t, 24)) + " 都会被当成任务名（提示 / 秒数都没有）。如果想写提示，格式是 " + q("名称|提示|秒数")));
            }
        }
    }

    // ------------------------------------------------------------ 配置之间的矛盾

    private static String val(List<Entry> es, String key) {
        String v = null;
        for (Entry e : es) if (e.key.equals(key)) v = e.value == null ? "" : e.value.trim();
        return v;
    }

    private static int lineOf(List<Entry> es, String key) {
        int l = 0;
        for (Entry e : es) if (e.key.equals(key)) l = e.line;
        return l;
    }

    private static boolean on(List<Entry> es, String key) {
        String v = val(es, key);
        return v != null && v.equalsIgnoreCase("true");
    }

    private static void crossChecks(List<Issue> out, List<Entry> es, String[] lines) {
        watchVsTrace(out, val(es, "watch"), val(es, "traceMethods"), lineOf(es, "watch"));
        String tm = val(es, "traceMethods");
        boolean hasTrace = false;
        if (tm != null) for (String s : tm.split("\\r?\\n|\\r")) if (!s.trim().isEmpty() && !s.trim().startsWith("//")) hasTrace = true;
        if (hasTrace && !"false".equalsIgnoreCase(val(es, "traceExclusive") == null ? "" : val(es, "traceExclusive"))) {
            String[] others = {"traceSet", "traceDraw", "traceColor", "traceIcons", "traceState", "autoParams"};
            StringBuilder sb = new StringBuilder();
            for (String o : others) if (on(es, o)) sb.append(sb.length() > 0 ? "、" : "").append(o);
            if (sb.length() > 0) out.add(new Issue(lineOf(es, sb.toString().split("、")[0]), false, "写了 traceMethods，而 traceExclusive 默认是开的（方法追踪独占），所以 " + sb + "=true 在目标 App 里会被当成关闭，记录里只剩方法追踪。想让它们一起记，加一行 " + q("traceExclusive=false")));
        }
        if (!hasTrace) {
            String[] needs = {"traceDump", "valueClock", "chainOn"};
            for (String n : needs) {
                if (on(es, n)) out.add(new Issue(lineOf(es, n), false, q(n + "=true") + " 只对方法追踪有意义，但没有写 " + q("traceMethods=") + "（或写了但是空的），开了也记不到东西"));
            }
        }
        String km = val(es, "keywordMode"), kw = val(es, "keywords"), rg = val(es, "regex");
        if ("true".equalsIgnoreCase(km == null ? "" : km) && (kw == null || kw.isEmpty())) {
            out.add(new Issue(lineOf(es, "keywordMode"), false, q("keywordMode=true") + " 但没有写 " + q("keywords=") + "（或是空的），关键字模式下没有关键字，什么文字都匹配不上。要抓全部文字写 " + q("keywordMode=false")));
        }
        if ("true".equalsIgnoreCase(rg == null ? "" : rg) && !"true".equalsIgnoreCase(km == null ? "" : km)) {
            out.add(new Issue(lineOf(es, "regex"), false, q("regex=true") + " 只在 " + q("keywordMode=true") + " 时才起作用，现在 keywordMode 不是 true，正则不会被用"));
        }
        if ("true".equalsIgnoreCase(rg == null ? "" : rg) && kw != null) {
            int k = 0;
            for (String s : kw.split("\\r?\\n|\\r")) {
                k++;
                String t = s.trim();
                if (t.isEmpty()) continue;
                try {
                    Pattern.compile(t);
                } catch (Throwable ex) {
                    String m = ex.getMessage() == null ? "" : ex.getMessage().split("\\n")[0];
                    out.add(new Issue(lineOf(es, "keywords"), true, "keywords 第 " + k + " 项 " + q(clip(t, 30)) + " 不是合法的正则：" + m + "。（正则模式下关键字只能用 " + q("\\n") + " 隔开，不能用逗号；字面的 " + q(".") + " " + q("(") + " " + q("+") + " 前面要加反斜杠）"));
                }
            }
        } else if (kw != null && kw.contains("，")) {
            // 全角逗号已在 checkOne 里报过
        }
        if (on(es, "viewDump") && "".equals(val(es, "viewDumpKey"))) {
            out.add(new Issue(lineOf(es, "viewDumpKey"), false, q("viewDumpKey") + " 是空的，视图树不知道以哪个控件为准；不写这一行就用默认值"));
        }
        String ex = val(es, "traceExclusive");
        if (ex != null && !ex.isEmpty() && !hasTrace && ex.equalsIgnoreCase("false")) {
            // traceExclusive=false 没有 traceMethods 时没有意义，但无害
        }
    }

    // ------------------------------------------------------------ 输出

    static boolean hasError(List<Issue> l) {
        for (Issue i : l) if (i.error) return true;
        return false;
    }

    static int count(List<Issue> l, boolean error) {
        int n = 0;
        for (Issue i : l) if (i.error == error) n++;
        return n;
    }

    /** 给人看的文本：先错误后警告，每条带行号。 */
    static String format(List<Issue> l) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean err = pass == 0;
            int c = count(l, err);
            if (c == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(err ? "✗ 错误 " : "⚠ 警告 ").append(c).append(" 条").append(err ? "（这些一定不会按你想的生效）" : "（能生效，但很可能不是你想要的）").append('\n');
            for (Issue i : l) {
                if (i.error != err) continue;
                n++;
                sb.append(n).append(". ").append(i.line > 0 ? "第 " + i.line + " 行：" : "").append(i.msg).append('\n');
            }
        }
        return sb.toString();
    }
}
