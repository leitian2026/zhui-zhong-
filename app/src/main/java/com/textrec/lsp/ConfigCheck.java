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

/**
 * 配置校验（纯 Java，不依赖 Android）：粘贴配置 / 任务清单、方法追踪清单写漏、写错、写得不规范时，
 * 给出准确的错误原因（第几行、写了什么、哪里不对、应该怎么写），而不是静默忽略。
 *
 * 为什么需要：RecConfig 读配置时，认不出的 key 直接忽略、认不出的数字用默认值、认不出的布尔值当 false；
 * 拆分粘贴文本时，不是「key=value」格式的行一律当任务清单。所以漏一个字母、漏一个等号、多一个空格都不会报错，只是悄悄不生效。
 * 这里把这些「悄悄不生效」的情况一条条列出来。error = 一定不会按你想的生效；warn = 能生效但很可能不是你想要的。
 *
 * 检查分三层：
 *  1. 写法：拼写、全角符号、数字 / 布尔格式、@选项、方法追踪行、任务清单行（单个字段 / 单行就能判断的）；
 *  2. 关系：父子开关（子项开着、父开关关着）、只对方法追踪有意义的项没写方法追踪、方法追踪被排除名单排除、任务清单和配置不配套；
 *  3. 现实：类名是不是文档里的示例、是不是没在已有记录里出现过（类是否存在离线查不了，真正的结果在应用后由目标 App 回读，见首页「实际生效」）。
 * 字段表来自自动生成的 {@link ConfigSchema}（RecConfig.java + tools/field_meta.txt），不会和源码漂移；CI 里还会把文档示例逐个跑一遍。
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

    private static final Set<String> BOOLS = new HashSet<>();
    private static final Set<String> STRS = new HashSet<>();
    /** key → {key, 最小, 最大, 默认} */
    private static final Map<String, String[]> INTS = new HashMap<>();
    private static final List<String> ALL_KEYS = new ArrayList<>();

    /** 已有记录 / 钩点签名里出现过的类名（首页读取目标 App 状态时学到的）；null 或空 = 不做「类名查表」。 */
    static volatile Set<String> knownClasses = null;

    /** 只能写在方法追踪清单里的选项。 */
    private static final String[] TRACE_OPTS = {"count", "args", "stack", "depth", "every", "tag", "seq"};
    /** 逗号 / 换行分隔的列表型配置项。 */
    private static final Set<String> LIST_KEYS = new HashSet<>(Arrays.asList(PasteBlocks.LIST_SECTIONS));
    /** 文档里出现过的示例类名前缀：AI 容易原样抄进配置。 */
    private static final String[] EXAMPLE_CLASSES = {"com.a.B", "com.a.b", "com.demo", "com.example", "com.xxx", "com.foo", "com.test.", "org.example", "你的"};
    /** 仅测试用：文档里的正确示例要用 com.demo.* 这类示例类名，校验文档时临时放行。 */
    static volatile boolean allowExampleClasses = false;

    static {
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            ALL_KEYS.add(f.key);
            if (f.type.equals("bool")) BOOLS.add(f.key);
            else if (f.type.equals("string")) STRS.add(f.key);
            else if (f.hasRange) INTS.put(f.key, new String[]{f.key, String.valueOf(f.min), String.valueOf(f.max), f.def});
        }
        ALL_KEYS.add("preset");
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
        final String raw;      // 旧格式：物理行（含续行）原文；块格式为 null
        String value;          // 解码后的值（旧格式按 Properties 规则解码；块格式本来就是字面值）
        boolean glued;         // 旧格式：续行把两项直接粘在了一起
        boolean block;         // 来自块格式
        String section;        // 块格式：config 或列表段名
        int[] itemLines;       // 块格式列表段：每一项所在的行号

        Entry(int line, String key, String raw) {
            this.line = line;
            this.key = key;
            this.raw = raw;
        }
    }

    // ------------------------------------------------------------ 粘贴框（配置 + 任务清单）

    /**
     * 校验粘贴框里的整段文本。拆分规则和 {@link PasteBlocks#split(String)} 完全一致：
     * 有 [config] / [traceMethods] 这类段名的是块格式；否则是旧格式（以「英文标识符 + = 或 :」开头的是配置行，# 开头是注释，
     * [tasks] / [任务清单] 之后全是任务，其余都是任务清单）。
     */
    static List<Issue> checkPaste(String text) {
        if (text == null) return new ArrayList<>();
        if (PasteBlocks.isBlock(text)) return checkBlockText(text);
        return checkOldText(text);
    }

    private static List<Issue> checkBlockText(String text) {
        ArrayList<Issue> out = new ArrayList<>();
        PasteBlocks.Parsed p = PasteBlocks.parse(text);
        for (PasteBlocks.Msg m : p.msgs) out.add(new Issue(m.line, m.error, m.text));
        ArrayList<Entry> entries = new ArrayList<>();
        for (PasteBlocks.Item it : p.items) {
            Entry e = new Entry(it.line, it.key, null);
            e.value = it.value;
            e.block = true;
            e.section = it.section;
            e.itemLines = it.itemLines;
            entries.add(e);
        }
        checkEntries(out, entries);
        ArrayList<String> tr = new ArrayList<>();
        ArrayList<Integer> tn = new ArrayList<>();
        for (int i = 0; i < p.taskLines.size(); i++) {
            if (p.taskLines.get(i).trim().startsWith("//")) continue;
            tr.add(p.taskLines.get(i));
            tn.add(p.taskLineNos.get(i));
        }
        checkTasks(out, tr, tn);
        crossChecks(out, entries, tr);
        sortIssues(out);
        return out;
    }

    private static List<Issue> checkOldText(String text) {
        ArrayList<Issue> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n", -1);
        ArrayList<Entry> entries = new ArrayList<>();
        ArrayList<Integer> taskIdx = new ArrayList<>();
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
                boolean cont = PasteBlocks.endsWithOddBackslash(line);
                while (cont && i + 1 < lines.length) {
                    i++;
                    raw.append('\n').append(lines[i]);
                    String nt = lines[i].trim();
                    cont = PasteBlocks.endsWithOddBackslash(lines[i]);
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
                taskIdx.add(i);
            }
        }
        checkEntries(out, entries);
        ArrayList<String> tr = new ArrayList<>();
        ArrayList<Integer> tn = new ArrayList<>();
        for (int ti : taskIdx) {
            if (lines[ti].trim().startsWith("//")) continue;
            tr.add(lines[ti]);
            tn.add(ti + 1);
        }
        checkTasks(out, tr, tn);
        crossChecks(out, entries, tr);
        sortIssues(out);
        return out;
    }

    private static void sortIssues(List<Issue> out) {
        java.util.Collections.sort(out, (a, b) -> a.line != b.line ? Integer.compare(a.line, b.line) : Boolean.compare(b.error, a.error));
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

    private static void checkEntries(List<Issue> out, List<Entry> entries) {
        Map<String, Entry> seen = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.value == null) {
                // 旧格式：按 Properties 的转义规则解码
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
                e.glued = checkGlue(out, e);
            }
            Entry prev = seen.get(e.key);
            if (prev != null) {
                boolean list = LIST_KEYS.contains(e.key);
                if (list) {
                    out.add(new Issue(e.line, true, q(e.key) + " 写了两次（第 " + prev.line + " 行和第 " + e.line + " 行）。后面的会把前面的整个覆盖掉，第 " + prev.line
                            + " 行里的所有项都丢了，只剩第 " + e.line + " 行。" + (e.block || prev.block
                            ? "把它们合并：放进同一个 " + q("[" + e.key + "]") + " 段，一行一项"
                            : "把它们合并成一个配置项：写在同一行用 " + q("\\n") + " 隔开（或块格式的 " + q("[" + e.key + "]") + " 段）")));
                } else {
                    out.add(new Issue(e.line, false, q(e.key) + " 写了两次（第 " + prev.line + " 行和第 " + e.line + " 行），后面的覆盖前面的，只有第 " + e.line + " 行生效"));
                }
            }
            seen.put(e.key, e);
            checkOne(out, e);
        }
    }

    private static boolean endsWithNewlineEscape(String s) {
        if (!s.endsWith("n")) return false;
        int n = 0;
        for (int i = s.length() - 2; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    private static boolean startsWithNewlineEscape(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == '\\') n++;
        return n % 2 == 1 && n < s.length() && s.charAt(n) == 'n';
    }

    /**
     * 行尾反斜杠 = 续行：下一行直接接在这一行后面，中间不补换行也不补逗号。
     * 所以列表型配置项（traceMethods / keywords…）分行写时，行尾反斜杠前面必须自己写 \n（或逗号），否则两项会粘成一项。
     * 注意 {@code \\n}（两个反斜杠）在 Properties 里是字面的反斜杠加 n，不是换行，不能算分隔。
     */
    private static boolean checkGlue(List<Issue> out, Entry e) {
        if (!LIST_KEYS.contains(e.key)) return false;
        String[] ls = e.raw.split("\n", -1);
        boolean any = false;
        for (int i = 0; i + 1 < ls.length; i++) {
            String cur = ls[i];
            if (!PasteBlocks.endsWithOddBackslash(cur)) continue;
            String head = cur.substring(0, cur.length() - 1);
            String next = ls[i + 1].replaceAll("^\\s+", "");
            if (head.trim().isEmpty() || next.isEmpty()) continue;
            boolean sepOk = endsWithNewlineEscape(head) || head.trim().endsWith(",") || next.startsWith(",") || startsWithNewlineEscape(next);
            if (sepOk) continue;
            String a = head.trim(), b = next;
            boolean doubled = head.endsWith("\\\\n");
            out.add(new Issue(e.line + i, true, q(e.key) + " 第 " + (e.line + i) + " 行末尾的反斜杠 " + q("\\") + " 只是把下一行直接接在后面，不会换行：" + q(clip(a.substring(Math.max(0, a.length() - 16)) + b.substring(0, Math.min(b.length(), 16)), 40))
                    + " 会粘成一项，" + (e.key.equals("traceMethods") ? "多条方法追踪就会变成一条错的" : "两个词会变成一个词")
                    + (doubled ? "。你写的是 " + q("\\\\n") + "（两个反斜杠），在 Properties 里它是字面的反斜杠加 n，不是换行；换行只写一个反斜杠 " + q("\\n")
                    : "。要分成两项，在行尾反斜杠前面加 " + q("\\n") + "（写成 " + q(clip(a, 30) + "\\n\\") + "）" + (e.key.equals("traceMethods") ? "" : "，或加逗号"))
                    + "。更省事的办法：改用块格式，每个列表一个 " + q("[" + e.key + "]") + " 段，一行一项，什么都不用转义"));
            any = true;
        }
        return any;
    }

    private static void checkPreset(List<Issue> out, int ln, String v) {
        if (v.isEmpty()) {
            out.add(new Issue(ln, true, q("preset") + " 的值是空的。可用预设：" + Presets.names() + "。不用预设就整行删掉"));
        } else if (Presets.get(v) == null) {
            String k = nearest(v, Presets.nameList());
            out.add(new Issue(ln, true, "不认识的预设 " + q(v) + "，会被忽略（预设写错就等于没写，它展开的那些开关一个都不会设）。"
                    + (k != null ? "你是不是想写 " + q("preset=" + k) + "？" : "") + "可用预设：" + Presets.names()));
        }
    }

    private static void checkOne(List<Issue> out, Entry e) {
        String key = e.key, v = e.value == null ? "" : e.value;
        int ln = e.line;
        String vt = v.trim();
        if (key.equals("preset")) {
            checkPreset(out, ln, vt);
            return;
        }
        if (key.equals("traceOnly")) {
            out.add(new Issue(ln, false, q("traceOnly") + " 已废弃，写了也忽略。想只记方法追踪，用 " + q("preset=trace") + "（或把 traceSet / traceDraw / traceColor / autoParams / traceIcons / traceSetOnly / traceState 都写成 false）"));
            return;
        }
        boolean isBool = BOOLS.contains(key), isInt = INTS.containsKey(key), isStr = STRS.contains(key), isEpoch = key.equals("epoch");
        if (!isBool && !isInt && !isStr && !isEpoch) {
            String k = nearest(key, ALL_KEYS);
            if (k != null && !k.equals(key)) {
                boolean caseOnly = k.equalsIgnoreCase(key);
                out.add(new Issue(ln, true, "不认识的配置项 " + q(key) + "，会被直接忽略。" + (caseOnly ? "配置项名区分大小写，应写成 " + q(k) : "你是不是想写 " + q(k) + "？（" + diffHint(key, k) + "）")));
            } else {
                out.add(new Issue(ln, true, "不认识的配置项 " + q(key) + "，会被直接忽略（没有哪个配置项和它像）。配置项名见《写配置手册》的字段表，不要自己造"));
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
            if (lv.matches("^(true|false)\\s+(#|//|--).*"))
                hint = "后面跟了行内注释。配置里不支持行内注释（" + q("#") + " 后面的内容会变成值的一部分，整个值就不是 true / false 了，会被当成 false）；注释要单独占一行";
            else if (lv.isEmpty()) hint = "值是空的，会被当成 false。要开写 " + q(key + "=true") + "，要关写 " + q(key + "=false");
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
                if (vt.matches("-?\\d+\\s+(#|//|--).*")) why = "后面跟了行内注释，配置里不支持行内注释，注释要单独占一行";
                else if (vt.matches("-?\\d+\\.\\d+")) why = "是小数，只认整数";
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
        if (LIST_KEYS.contains(key) && !key.equals("keywords") && v.contains("\\n")) {
            out.add(new Issue(ln, true, q(key) + " 的值里有字面的 " + q("\\n") + "（反斜杠加 n 两个字符），它不是换行。"
                    + (e.block ? "块格式里一行只能写一项，要写多项就分成多行"
                    : "Properties 里 " + q("\\\\n") + "（两个反斜杠）才会解出这两个字符；换行要写一个反斜杠 " + q("\\n") + "。更省事的办法是改用块格式（" + q("[" + key + "]") + " 段，一行一项）")));
        }
        if (key.equals("keywords") && e.block && !"config".equals(e.section)) {
            for (String s : v.split("\n")) {
                String t = s.trim();
                if (t.startsWith("//") || t.startsWith("#")) {
                    out.add(new Issue(ln, false, q("[keywords]") + " 段里每一行都是一个关键字，没有注释语法：" + q(clip(t, 24)) + " 会被当成关键字。要删掉这行"));
                    break;
                }
            }
        }
        if (key.equals("traceMethods") && !e.glued) checkTraceMethods(out, v, ln, "traceMethods", false, e.itemLines);
        if (key.equals("watch") && !e.glued) checkWatch(out, v, ln, false, e.itemLines);
        if (key.equals("excludeList")) checkExcludeItems(out, ln, v);
        if (key.equals("dumpPrefixes")) checkPrefixItems(out, ln, v);
        if (key.equals("threadFilter") || key.equals("pageFilter") || key.equals("viewFilter")) checkWordItems(out, ln, key, v);
    }

    private static String defText(String key) {
        String[] r = INTS.get(key);
        return r == null || "-1".equals(r[3]) ? "" : " " + r[3];
    }

    // ------------------------------------------------------------ 手写过滤列表

    private static void checkExcludeItems(List<Issue> out, int ln, String value) {
        for (String raw : value.split("[,\\n\\r]+")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            String pre = q("excludeList") + " 的 " + q(clip(t, 40)) + "：";
            String fw = fullwidthIn(t);
            if (fw != null) {
                out.add(new Issue(ln, true, pre + fw));
                continue;
            }
            if (t.indexOf(' ') >= 0 || t.indexOf('\t') >= 0) {
                out.add(new Issue(ln, true, pre + "里面有空格。一项就是一个 " + q("类#方法") + " / " + q("类#*") + " / " + q("#方法") + " / 类名或包名前缀，项与项之间用逗号或换行分隔"));
                continue;
            }
            if (t.indexOf('@') >= 0) {
                out.add(new Issue(ln, true, pre + "排除名单不支持 " + q("@选项") + "（@count 之类只能写在 traceMethods 的行尾）"));
                continue;
            }
            if (t.indexOf('(') >= 0 || t.indexOf(')') >= 0) {
                out.add(new Issue(ln, true, pre + "不要带括号参数，排除的是方法名，所有同名重载一起排除"));
                continue;
            }
            int h = t.indexOf('#');
            if (h >= 0) {
                String c = t.substring(0, h), m = t.substring(h + 1);
                if (m.indexOf('#') >= 0) out.add(new Issue(ln, true, pre + "出现了不止一个 " + q("#")));
                else if (m.isEmpty()) out.add(new Issue(ln, true, pre + q("#") + " 后面没有方法名（排除整个类写 " + q("类#*") + "）"));
                else if (!m.equals("*") && !METHOD_OK.matcher(m).matches()) out.add(new Issue(ln, true, pre + "方法名 " + q(m) + " 不合法"));
                else if (c.isEmpty() && m.equals("*")) out.add(new Issue(ln, true, pre + q("#*") + " 会排除所有类的所有方法，等于什么都不记"));
                else if (!c.isEmpty()) {
                    String bad = badClass(c);
                    if (bad != null) out.add(new Issue(ln, true, pre + bad));
                }
            } else if (!t.matches("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z0-9_$]*)*\\.?$")) {
                out.add(new Issue(ln, true, pre + "不是合法的类名 / 包名前缀（要排除某个方法写 " + q("类#方法") + "，任何类里的某方法写 " + q("#方法") + "）"));
            }
        }
    }

    private static void checkPrefixItems(List<Issue> out, int ln, String value) {
        for (String raw : value.split("[,\\n\\r]+")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            if (!t.matches("^[A-Za-z_$][A-Za-z0-9_$.]*$")) {
                out.add(new Issue(ln, true, q("dumpPrefixes") + " 的 " + q(clip(t, 40)) + " 不是包名 / 类名前缀（只能是字母 / 数字 / _ / $ / 点，如 " + q("androidx.compose.ui.text.") + "；不能带 # 、* 、空格）"));
            }
        }
    }

    private static void checkWordItems(List<Issue> out, int ln, String key, String value) {
        for (String raw : value.split("[,\\n\\r]+")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.matches(".*(\\.\\*|\\\\|\\^|\\$|\\[|\\]|\\||\\(|\\)|\\+|\\?).*")) {
                out.add(new Issue(ln, false, q(key) + " 的 " + q(clip(t, 30)) + " 里有正则符号。这个字段是「包含这个词」的子串匹配，不是正则，这些符号会被当成字面字符，很可能什么都匹配不上"));
            }
        }
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
        checkTraceMethods(out, value, line, what, uiBox, null);
    }

    /** itemLines：块格式里每一项所在的真实行号（和 value 按行一一对应），null 时所有项都报 line。 */
    static void checkTraceMethods(List<Issue> out, String value, int line, String what, boolean uiBox, int[] itemLines) {
        if (value == null) return;
        String[] items = value.split("\\r?\\n|\\r");
        HashMap<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < items.length; i++) {
            String t = items[i].trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int ln = itemLines != null && i < itemLines.length ? itemLines[i] : line;
            String pre = (uiBox ? "方法追踪第 " + (i + 1) + " 行" : what + " 第 " + (i + 1) + " 条") + " " + q(clip(t, 50)) + "：";
            ArrayList<String> errs = new ArrayList<>(), warns = new ArrayList<>();
            checkTraceLine(t, errs, warns);
            for (String m : errs) out.add(new Issue(ln, true, pre + m));
            for (String m : warns) out.add(new Issue(ln, false, pre + m));
            int at = optStart(t);
            String target = (at < 0 ? t : t.substring(0, at)).trim();
            int pp = target.indexOf('(');
            if (pp > 0) target = target.substring(0, pp).trim();
            Integer prev = seen.get(target);
            if (prev != null) out.add(new Issue(ln, false, pre + "和第 " + prev + " 条是同一个钩点 " + q(target) + "，重复了（后面的选项覆盖前面的）"));
            else seen.put(target, i + 1);
        }
    }

    private static boolean isExampleClass(String cls) {
        for (String p : EXAMPLE_CLASSES) {
            if (p.endsWith(".") ? cls.startsWith(p) : (cls.equals(p) || cls.startsWith(p + ".") || (!p.contains(".") && cls.startsWith(p)))) return true;
        }
        return false;
    }

    private static boolean isSystemClass(String cls) {
        return cls.startsWith("android.") || cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("androidx.")
                || cls.startsWith("kotlin.") || cls.startsWith("kotlinx.") || cls.startsWith("dalvik.") || cls.startsWith("com.google.android.");
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
        int sp = -1;
        for (int k = 0; k < target.length(); k++) if (Character.isWhitespace(target.charAt(k))) { sp = k; break; }
        if (sp > 0 && target.charAt(sp - 1) != '#') {
            int nx = sp;
            while (nx < target.length() && Character.isWhitespace(target.charAt(nx))) nx++;
            if (nx < target.length() && target.charAt(nx) != '#') {
                errs.add("「类#方法」后面有多余的文字 " + q(clip(target.substring(sp).trim(), 30)) + "：注释必须单独占一行（以 " + q("//") + " 开头），行尾选项必须以 " + q("@") + " 开头，一行只能写一个钩点");
                target = target.substring(0, sp).trim();
            }
        }
        if (target.indexOf(',') >= 0) {
            errs.add("一行只能写一个钩点，" + q(clip(target, 40)) + " 里有逗号；多个钩点要分行写（块格式写在 " + q("[traceMethods]") + " 段里，一行一个）");
            return;
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
            else if (!allowExampleClasses && isExampleClass(cls)) errs.add("类名 " + q(cls) + " 是文档 / 示例里的占位类名，目标 App 里不存在。类名只能从导出的记录或钩点签名里逐字复制，不能自己编");
            else if (cls.indexOf('.') < 0) warns.add("类名 " + q(cls) + " 没有包名；除非目标类真的在默认包，否则要写全名（包名.类名）");
            else {
                Set<String> kn = knownClasses;
                if (kn != null && !kn.isEmpty() && !kn.contains(cls) && !isSystemClass(cls))
                    warns.add("类 " + q(cls) + " 没有在已有记录 / 钩点签名里出现过。类名要逐字从导出里复制，不能凭印象写（混淆名每个版本都不一样）；如果是刚找到的新类，可以忽略这条，应用后看首页「实际生效」里它有没有装上");
            }
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
        boolean count = false, args = false, seq = false;
        for (String w : opts.split("[@,\\s]+")) {
            if (w.isEmpty()) continue;
            String name = w.contains("=") ? w.substring(0, w.indexOf('=')) : w;
            String val = w.contains("=") ? w.substring(w.indexOf('=') + 1) : null;
            boolean known = false;
            for (String o : TRACE_OPTS) if (o.equals(name)) known = true;
            if (!known) {
                String k = nearest(name, java.util.Arrays.asList(TRACE_OPTS));
                errs.add("未知选项 " + q("@" + name) + "，会被忽略" + (k != null ? "。你是不是想写 " + q("@" + k + (k.equals("count") || k.equals("args") || k.equals("seq") ? "" : "=…")) + "？" : "。可用选项：@count @args @stack=N @every=N @tag=N / @tag=ret @seq / @seq=N"));
                continue;
            }
            if (name.equals("count")) {
                count = true;
                if (val != null) errs.add(q("@count") + " 不带值，不能写 " + q("=" + val));
            } else if (name.equals("args")) {
                args = true;
                if (val != null) errs.add(q("@args") + " 不带值，不能写 " + q("=" + val));
            } else if (name.equals("seq")) {
                seq = true;
                if (val != null) {
                    if (!val.matches("\\d+")) errs.add(q("@seq=" + val) + " 的值 " + q(val) + " 不是正整数，会被忽略（按默认 200 条）");
                    else if (Long.parseLong(val) < 1) errs.add(q("@seq=" + val) + " 必须 ≥ 1，会被忽略（按默认 200 条）");
                    else if (Long.parseLong(val) > 1000) warns.add(q("@seq=" + val) + " 超出上限 1000，会被改成 1000");
                }
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
        if (seq && count) warns.add("同时写了 " + q("@seq") + " 和 " + q("@count") + "：序列事件照记，但 " + q("@count") + " 的记录里不会有调用栈");
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
        checkWatch(out, value, line, uiBox, null);
    }

    static void checkWatch(List<Issue> out, String value, int line, boolean uiBox, int[] itemLines) {
        if (value == null) return;
        String[] items = value.split("\\r?\\n|\\r");
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < items.length; i++) {
            String t = items[i].trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int ln = itemLines != null && i < itemLines.length ? itemLines[i] : line;
            String pre = (uiBox ? "观察点第 " + (i + 1) + " 行 " : "watch 第 " + (i + 1) + " 条 ") + q(clip(t, 50)) + "：";
            int c = t.indexOf(':');
            if (c < 0) {
                out.add(new Issue(ln, true, pre + "缺少冒号：写法是 " + q("类#方法:路径") + "，如 " + q("com.a.B#foo:arg0.mAweme.userDigg")));
                continue;
            }
            String tk = t.substring(0, c), path = t.substring(c + 1);
            if (!tk.equals(tk.trim()) || !path.equals(path.trim())) out.add(new Issue(ln, true, pre + "冒号两边不能有空格"));
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
            for (String m : errs) out.add(new Issue(ln, true, pre + m));
            for (String m : warns) out.add(new Issue(ln, false, pre + m));
            if (!seen.add(t)) out.add(new Issue(ln, false, pre + "重复了"));
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

    private static void checkTasks(List<Issue> out, List<String> raws, List<Integer> nos) {
        Set<String> names = new HashSet<>();
        boolean first = true;
        for (int ii = 0; ii < raws.size(); ii++) {
            String raw = raws.get(ii);
            String t = raw.trim();
            int no = nos.get(ii);
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
            boolean isFirstTask = first;
            first = false;
            boolean base = name.startsWith("@基线");
            if (base && !isFirstTask) out.add(new Issue(no, false, "基线 " + q("@基线") + " 最好放在清单第一件：它记的是「什么都不做」时的背景噪声，放在后面，前面的任务就没法拿它当对照"));
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

    // ------------------------------------------------------------ 配置之间的关系

    private static final class Ctx {
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
            hasTasks = !taskRaws.isEmpty();
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
            ConfigSchema.F f = ConfigSchema.get(k);
            String v = get(k);
            if (f == null) return !v.isEmpty();
            if (f.type.equals("bool")) return v.equalsIgnoreCase("true");
            if (f.type.equals("string")) return !v.isEmpty();
            try {
                return Long.parseLong(v) != 0;
            } catch (NumberFormatException ex) {
                try {
                    return Long.parseLong(f.def) != 0;
                } catch (NumberFormatException ex2) {
                    return false;
                }
            }
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
            if (e != null) return (e.line > 0 ? "第 " + e.line + " 行" : "你") + "写的是 " + q(clip(e.value == null ? "" : e.value.trim(), 20));
            String pv = preset.get(k);
            if (pv != null) return "预设 " + q(presetName) + " 把它设成了 " + q(pv.trim());
            ConfigSchema.F f = ConfigSchema.get(k);
            return "没写，用默认值 " + q(f == null ? "" : f.def);
        }
    }

    /** 清单里真正的项：去掉空行和 // 注释行。 */
    private static List<String> traceItems(String v) {
        ArrayList<String> out = new ArrayList<>();
        if (v == null) return out;
        for (String s : v.split("\\r?\\n|\\r")) {
            String t = s.trim();
            if (!t.isEmpty() && !t.startsWith("//")) out.add(t);
        }
        return out;
    }

    /** 每一项的「类#方法」目标（去掉 @选项 和括号）。 */
    private static List<String> traceTargets(String v) {
        ArrayList<String> out = new ArrayList<>();
        for (String t : traceItems(v)) {
            int at = optStart(t);
            String target = (at >= 0 ? t.substring(0, at) : t).trim();
            int p = target.indexOf('(');
            if (p > 0) target = target.substring(0, p);
            out.add(target.trim());
        }
        return out;
    }

    /** 这个值是不是「用户想要它生效」的值：开关写了 true；整数写了和默认不同的数；文字非空且和默认不同。 */
    private static boolean meaningful(ConfigSchema.F f, String v) {
        if (f.type.equals("bool")) return v.equalsIgnoreCase("true");
        if (f.type.equals("string")) return !traceItems(v).isEmpty() && !v.equals(f.def);
        try {
            return Long.parseLong(v) != Long.parseLong(f.def);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static void crossChecks(List<Issue> out, List<Entry> es, List<String> taskRaws) {
        Ctx cx = new Ctx(es, taskRaws);

        // ---- 1. 父子开关：子项写了，父开关却关着 / 不满足，子项不会起作用
        for (Entry e : cx.explicit.values()) {
            ConfigSchema.F f = ConfigSchema.get(e.key);
            if (f == null || f.parent.isEmpty()) continue;
            ConfigSchema.F pf = ConfigSchema.get(f.parent);
            if (pf == null) continue;
            String v = e.value == null ? "" : e.value.trim();
            if (!meaningful(f, v)) continue;
            boolean pOn = cx.on(f.parent);
            boolean dead = f.parentInverse ? pOn : !pOn;
            if (!dead) continue;
            String child = q(e.key + (v.length() > 24 || v.contains("\n") ? "" : "=" + v));
            if (e.key.equals("keywords")) {
                out.add(new Issue(e.line, true, q("keywords") + " 写了，但 " + q("keywordMode") + " 不是 true（" + cx.where("keywordMode")
                        + "），关键字根本不会被用，目标 App 会记录全部文字（很卡）。要按关键字记，加一行 " + q("keywordMode=true") + "；要记全部文字就删掉 keywords"));
            } else if (f.parentInverse) {
                out.add(new Issue(e.line, false, child + " 不起作用：它只在 " + q(f.parent + "=false") + " 时才有效（" + cx.where(f.parent) + "，分文件存开着时各类记录用各自的上限）。要用它就先写 " + q(f.parent + "=false") + "，否则删掉这一行"));
            } else {
                String fix = pf.type.equals("bool") ? "先加 " + q(f.parent + "=true") : "先把 " + q(f.parent) + " 设成大于 0 的数";
                out.add(new Issue(e.line, false, child + " 不起作用：它是 " + q(f.parent) + " 的子项，而 " + q(f.parent) + " 现在是关着的（" + cx.where(f.parent) + "）。要用它，" + fix + "；不想用就删掉这一行"));
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
                out.add(new Issue(e.line, false, q(e.key + (v.length() > 24 || v.contains("\n") ? "" : "=" + v)) + " 只对方法追踪有意义，但没有写 " + q("traceMethods") + "（或写的全是注释），所以不会起任何作用。要么补上方法追踪，要么删掉这一行"));
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
                out.add(new Issue(ln, false, "写了 " + q("traceMethods") + "，而 " + q("traceExclusive") + " 默认是 true（方法追踪独占）：" + on + " 这些开关即使是 true，在目标 App 里也会被自动当成关闭，记录里只会有方法追踪。"
                        + "想让它们一起记，加一行 " + q("traceExclusive=false") + "；只想追踪方法就把这些开关删掉或写 false"));
            }
        }

        // ---- 4. keywordMode / keywords / regex
        if (cx.on("keywordMode") && traceItems(cx.get("keywords")).isEmpty()) {
            int ln = cx.line("keywordMode");
            out.add(new Issue(ln, false, q("keywordMode=true") + " 但没有写 " + q("keywords") + "（或为空），不会命中任何文字，什么都记不到。补上关键字，或去掉 keywordMode"));
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
                        out.add(new Issue(kw.line, true, q("keywords") + " 里的正则 " + q(clip(k, 30)) + " 无效：" + clip(String.valueOf(t.getMessage()).split("\\n")[0], 60) + "。" + (kw.block ? "块格式里正则写成平时的样子（" + q("\\d+") + "），不用双反斜杠" : "Properties 里每个正则反斜杠要写两个：" + q("\\\\d+"))));
                    }
                    if (k.indexOf(',') >= 0) {
                        out.add(new Issue(kw.line, false, q("regex=true") + " 时 keywords 只按换行分隔，" + q(clip(k, 30)) + " 里的逗号是正则的一部分（匹配字面逗号），不是分隔符。要写多个关键字请分成多行"));
                    }
                }
            } else {
                for (String k : kw.value.split("[,\\n\\r]+")) {
                    String t = k.trim();
                    if (t.matches(".*(\\\\[dwsDWS]|\\.\\*|\\.\\+|\\[[^\\]]+\\]|\\^|\\$).*") && t.length() > 1) {
                        out.add(new Issue(kw.line, false, q("keywords") + " 的 " + q(clip(t, 30)) + " 看起来是正则，但 " + q("regex") + " 不是 true，它会被当成普通文字去匹配，大概率什么都匹配不上。要用正则就加 " + q("regex=true") + "（并且每行一个）"));
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
                out.add(new Issue(cx.line("threadFilter"), false, q("threadMode=1") + "（只记主线程）和 " + q("threadFilter") + " 里没有 main 的线程名矛盾：主线程的名字是 main，其他线程名都会被 threadMode 先挡掉，什么也记不到"));
            } else if (tm == 2 && hasMain) {
                out.add(new Issue(cx.line("threadFilter"), false, q("threadMode=2") + "（只记后台线程）和 " + q("threadFilter") + " 里的 main 矛盾：主线程已经被 threadMode 挡掉了"));
            }
        }

        // ---- 6. 排除名单把要追踪的方法排除了
        excludeVsTrace(out, cx);

        // ---- 7. 任务清单和配置配套
        if (!cx.hasTasks) {
            Entry tm = cx.explicit.get("traceMethods");
            if (tm != null && tm.value != null) {
                for (String it : traceItems(tm.value)) {
                    int at = optStart(it);
                    if (at >= 0 && it.substring(at).matches("(?s).*(^|[\\s,@])seq(=\\d+)?(\\s|,|$).*")) {
                        out.add(new Issue(tm.line, false, "用了 " + q("@seq") + "，但没有任务清单。序列事件只在任务里记录（每个任务阶段一份），没有任务就记不到。去写任务清单（做 1 件事也要写）"));
                        break;
                    }
                }
            }
            if (cx.explicitVal("recOutside") != null && cx.explicitVal("recOutside").equalsIgnoreCase("true"))
                out.add(new Issue(cx.line("recOutside"), false, q("recOutside=true") + " 是「任务外也记录」，没有任务清单就没有「任务内 / 任务外」之分，写了不起作用"));
            if (cx.on("viewDump") && (cx.explicitVal("viewDump") != null || cx.preset.containsKey("viewDump")))
                out.add(new Issue(cx.line("viewDump"), false, q("viewDump=true") + " 在「任务开始 / 结束」时各抓一份视图树，没有任务清单就只有在记录页手动点「标记」时才会抓。要抓就写任务清单"));
        }

        // ---- 8. 其他容易写错的单项
        String en = cx.explicitVal("enabled");
        if (en != null && en.equalsIgnoreCase("false"))
            out.add(new Issue(cx.line("enabled"), false, q("enabled=false") + " 会让目标 App 不记录任何东西。不是故意暂停就删掉这一行"));
        if (cx.explicitVal("epoch") != null && !cx.explicitVal("epoch").isEmpty())
            out.add(new Issue(cx.line("epoch"), false, q("epoch") + " 是「清空旧记录」的内部标记，值和上次不同就会清空目标 App 里已有的记录。一般不要手写，点「应用并重新记录」时程序会自己更新它"));
        String vk = cx.explicitVal("viewDumpKey");
        if (vk != null && vk.isEmpty())
            out.add(new Issue(cx.line("viewDumpKey"), false, q("viewDumpKey") + " 是空的，会回退成默认的两个类名（抖音视频文案）。要抓别的区域就填那里的控件类名 / id 里的词"));

        // ---- 9. watch 的方法必须在 traceMethods 里
        Entry wt = cx.explicit.get("watch"), tmE = cx.explicit.get("traceMethods");
        if (wt != null && wt.value != null && !wt.glued) {
            String tmv = tmE == null || tmE.value == null ? "" : tmE.value;
            watchVsTrace(out, wt.value, tmv, wt.line);
        }
    }

    private static void excludeVsTrace(List<Issue> out, Ctx cx) {
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
                    out.add(new Issue(ex.line, false, "traceMethods 里的 " + q(tg) + " 被 excludeList 里的 " + q(item) + " 排除了，这个钩点永远记不到东西。要追它就把这项从 excludeList 里删掉（或改窄）"));
                }
            }
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

    // ------------------------------------------------------------ 首页表单 / 给 AI 的报告

    /** 校验首页表单里的一份配置（带任务清单）：转成块格式文本再走同一套检查，问题不带行号。 */
    static List<Issue> checkForm(RecConfig c, String tasks) {
        ArrayList<Issue> out = new ArrayList<>();
        for (Issue i : checkPaste(PasteBlocks.export(c, tasks))) out.add(new Issue(0, i.error, i.msg));
        return out;
    }

    /** 「复制错误报告给 AI」：校验结果 + 带行号的原文 + 修改要求，粘给 AI 后它只改被点到的行。 */
    static String reportForAi(String text, List<Issue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("TextRec 校验了我粘贴的配置，结果在下面。请只修改被点到的行，其余内容保持原样；")
                .append("改完把完整的配置（配置 + 任务清单）整块重新发给我，仍然放在同一个代码块里。")
                .append("不要自己造类名和配置项名；类名只能从我给你的导出记录里逐字复制。\n\n");
        sb.append("===== 校验结果（行号对应下面的原文）=====\n").append(format(issues)).append('\n');
        sb.append("===== 我粘贴的原文 =====\n");
        String[] ls = (text == null ? "" : text).split("\\r?\\n", -1);
        for (int i = 0; i < ls.length; i++) sb.append(i + 1).append("| ").append(ls[i]).append('\n');
        return sb.toString();
    }
}
